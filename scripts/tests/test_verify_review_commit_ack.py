"""Commit-ACK drill safety and evidence tests; no actual process, PostgreSQL or network."""
import copy
from contextlib import ExitStack, contextmanager, redirect_stdout, redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_commit_ack',
    Path(__file__).absolute().parents[1] / 'verify-review-commit-ack.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
BEHAVIOR = ('sameLiveWarRecovered', 'databaseProcessUnchanged', 'committedBeforeClientFailure',
            'savedRowsReusedWithoutAi', 'twoIssuesAndSafeCheckpoint')
FINAL_BEHAVIOR = ('finalCompletionGateSelected', 'finalCompletionCommittedBeforeClientFailure',
                  'terminalSuccessUnchangedAfterClientFailure', 'completedRequestNotRetried',
                  'sameLiveWarRemainedTerminal', 'databaseProcessUnchanged', 'committedBeforeClientFailure',
                  'savedRowsPreservedWithoutExtraAi', 'twoIssuesAndSafeCheckpoint', 'requestActorAndReceptionPreserved')


class CommitAckDrillTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='commit-ack-fixture-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port=18102,
            timeout_seconds=180, startup_timeout_seconds=45, plain_postgres_protocol=True)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items() if key != 'plain_postgres_protocol'
                for part in ('--' + key.replace('_', '-'), str(value))]

    @staticmethod
    def initial():
        return {'request': {'requestId': 'same-request', 'actor': 7, 'source': 'MANUAL', 'state': 'RUNNING', 'attempts': 1},
                'ownership': {'requestedAt': 'before', 'claim': 'same-claim', 'run': 11},
                'commits': [MODULE.HELPERS.SHA_A], 'issues': 1, 'assignees': [7], 'cursor': None,
                'runs': [{'state': 'RUNNING', 'commits': 1}],
                'progress': {'runId': 11, 'stage': 'REVIEWING', 'saved': 1, 'lastSavedAt': 'saved-A'}}

    @classmethod
    def committed(cls):
        result = cls.initial()
        result.update(commits=[MODULE.HELPERS.SHA_A, MODULE.HELPERS.SHA_B], issues=2, assignees=[7, 7],
                      runs=[{'state': 'RUNNING', 'commits': 2}])
        result['progress'].update(saved=2, lastSavedAt='saved-B')
        return result

    def test_committed_state_requires_both_durable_results_and_unchanged_unfinished_claim(self):
        initial, committed = self.initial(), self.committed()
        MODULE.assert_committed(committed, initial)
        mutations = [('request', 'requestId', 'new'), ('request', 'actor', 8), ('request', 'state', 'SUCCEEDED'),
            ('request', 'attempts', 2), ('ownership', 'requestedAt', 'new'), ('ownership', 'claim', 'new'),
            ('ownership', 'run', 12), ('root', 'commits', [MODULE.HELPERS.SHA_A]), ('root', 'issues', 1),
            ('root', 'assignees', [7, 8]), ('root', 'cursor', MODULE.HELPERS.SHA_B),
            ('root', 'runs', [{'state': 'FAILED', 'commits': 2}]), ('progress', 'stage', 'FINALIZING'),
            ('progress', 'saved', 1), ('progress', 'runId', 12), ('progress', 'lastSavedAt', None)]
        for location, key, value in mutations:
            changed = copy.deepcopy(committed)
            (changed if location == 'root' else changed[location])[key] = value
            with self.subTest(location=location, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_committed(changed, initial)

    def test_fault_requires_exact_commit_and_idle_acknowledgements_without_truthy_substitutes(self):
        valid = {'failed': False, 'faultState': 'DROPPING', 'commitAcknowledgementsDropped': 1, 'readyForQueryIdleDropped': True}
        self.assertTrue(MODULE.acknowledgement_dropped(Mock(metrics=Mock(return_value=valid))))
        for key, values in {'failed': [True, None], 'faultState': ['ARMED', 'HEALED'],
                           'commitAcknowledgementsDropped': [None, 0, 2, True, '1'],
                           'readyForQueryIdleDropped': [False, None, 1]}.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertFalse(MODULE.acknowledgement_dropped(Mock(metrics=Mock(return_value=valid | {key: value}))))

    def test_saved_row_proof_includes_two_actual_assignment_audits(self):
        valid = {'commits': [{}, {}], 'issues': [{}, {}],
                 'assignments': [{'action': 'ISSUES_ASSIGNED'}, {'action': 'ISSUES_ASSIGNED'}, {'action': 'ISSUE_ASSIGNEE_FALLBACK'}]}
        MODULE.assert_saved_rows(valid)
        for assignments in (None, [], [{'action': 'ISSUES_ASSIGNED'}], [{'action': 'ISSUE_ASSIGNEE_FALLBACK'}] * 2,
                            [{'action': 'ISSUES_ASSIGNED'}] * 3):
            with self.subTest(assignments=assignments), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_saved_rows(valid | {'assignments': assignments})

    @classmethod
    def terminal(cls):
        result = cls.committed()
        result['request']['state'] = 'SUCCEEDED'
        result['cursor'] = MODULE.HELPERS.SHA_B
        result['runs'] = [{'state': 'SUCCEEDED', 'commits': 2}]
        result['progress']['stage'] = 'FINALIZING'
        return result

    def test_final_completion_requires_terminal_state_without_new_claim_or_retry(self):
        initial, terminal = self.initial(), self.terminal()
        MODULE.assert_final_completion(terminal, initial)
        mutations = [('request', 'requestId', 'replacement-request'), ('request', 'actor', 8),
            ('request', 'source', 'SCHEDULED'), ('request', 'state', 'RUNNING'), ('request', 'attempts', 2),
            ('ownership', 'requestedAt', 'later'), ('ownership', 'claim', 'replacement-claim'), ('ownership', 'run', 12),
            ('root', 'commits', [MODULE.HELPERS.SHA_A]), ('root', 'issues', 1), ('root', 'assignees', [7, 8]),
            ('root', 'cursor', None), ('root', 'runs', [{'state': 'FAILED', 'commits': 2}, {'state': 'SUCCEEDED', 'commits': 0}]),
            ('progress', 'stage', 'REVIEWING'), ('progress', 'saved', 0), ('progress', 'runId', 12),
            ('progress', 'lastSavedAt', None)]
        for location, key, value in mutations:
            changed = copy.deepcopy(terminal)
            (changed if location == 'root' else changed[location])[key] = value
            with self.subTest(location=location, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_final_completion(changed, initial)

    def test_cli_defaults_to_per_commit_and_accepts_only_the_two_explicit_phases(self):
        with patch.object(MODULE.LINUX, 'unused_port'):
            self.assertEqual('per-commit', MODULE.arguments(self.argv()).phase)
            for phase in ('per-commit', 'final-completion'):
                self.assertEqual(phase, MODULE.arguments(self.argv(phase=phase)).phase)
        for phase in ('final', 'claim', '', 'private-invalid-phase'):
            with self.subTest(phase=phase), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                MODULE.arguments(self.argv(phase=phase))
            port.assert_not_called()

    def test_final_gate_rejects_invalid_project_identifiers_before_sql_or_process_start(self):
        database = Mock()
        observer = MODULE.OwnedObserver(database, SCHEMA)
        with patch.object(MODULE.subprocess, 'Popen') as spawn:
            for value in (True, 0, -1, '1;DROP', None):
                with self.subTest(project=value), self.assertRaises(MODULE.VerificationError):
                    observer.acquire_final_completion_gate(value)
            database.sql.assert_not_called()
            spawn.assert_not_called()

    def test_final_gate_waits_only_for_the_owned_request_terminal_commit(self):
        database = Mock()
        observer = MODULE.OwnedObserver(database, SCHEMA)
        with patch.object(observer, '_acquire_row', return_value=123) as acquire, \
                patch.object(MODULE.subprocess, 'Popen') as spawn:
            self.assertEqual(123, observer.acquire_final_completion_gate(7))
        database.sql.assert_called_once()
        query = ' '.join(database.sql.call_args.args[0].split())
        self.assertIn('CREATE TABLE ' + SCHEMA + '.commit_ack_final_gate', query)
        self.assertIn('AFTER UPDATE ON ' + SCHEMA + '.review_request', query)
        self.assertIn('DEFERRABLE INITIALLY DEFERRED FOR EACH ROW', query)
        self.assertIn("WHEN (OLD.state='RUNNING' AND NEW.state='SUCCEEDED' AND NEW.project_id=7)", query)
        self.assertIn('WHERE project_id=NEW.project_id FOR UPDATE', query)
        self.assertIn('IF NOT FOUND THEN RAISE EXCEPTION', query)
        self.assertNotIn('public.', query)
        acquire.assert_called_once_with(7, 'commit_ack_final_gate', 'project_id')
        spawn.assert_not_called()
        database.reset_mock()
        observer.process = Mock()
        with self.assertRaises(MODULE.VerificationError):
            observer.acquire_final_completion_gate(7)
        database.sql.assert_not_called()

    @classmethod
    def terminal_rows(cls):
        return {'project': {'id': 1, 'last_reviewed_sha': MODULE.HELPERS.SHA_B},
            'request': {'project_id': 1, 'request_id': 'same-request', 'requested_by': 7, 'source': 'MANUAL',
                'state': 'SUCCEEDED', 'attempt_count': 1, 'requested_at': 'before', 'claim_token': 'same-claim',
                'run_id': 11, 'finished_at': 'completed-at', 'result_code': 'BATCH_COMPLETED'},
            'runs': [{'id': 11, 'project_id': 1, 'status': 'SUCCEEDED', 'reviewed_commits': 2,
                'progress_stage': 'FINALIZING', 'last_saved_at': 'saved-B', 'finished_at': 'completed-at', 'error_message': None}],
            'commits': [{'id': 21, 'project_id': 1, 'commit_sha': MODULE.HELPERS.SHA_A},
                        {'id': 22, 'project_id': 1, 'commit_sha': MODULE.HELPERS.SHA_B}],
            'issues': [{'id': 41, 'project_id': 1, 'reviewed_commit_id': 21, 'assignee_id': 7},
                       {'id': 42, 'project_id': 1, 'reviewed_commit_id': 22, 'assignee_id': 7}],
            'audits': [{'id': 31, 'action': 'REVIEW_STARTED', 'target_type': 'REVIEW_RUN', 'target_id': 11},
                       {'id': 32, 'action': 'ISSUES_ASSIGNED', 'target_type': 'REVIEWED_COMMIT', 'target_id': 21},
                       {'id': 33, 'action': 'ISSUES_ASSIGNED', 'target_type': 'REVIEWED_COMMIT', 'target_id': 22},
                       {'id': 34, 'action': 'REVIEW_SUCCEEDED', 'target_type': 'REVIEW_RUN', 'target_id': 11}]}

    def test_final_raw_row_proof_requires_one_completion_and_exact_durable_relationships(self):
        initial, rows = self.initial(), self.terminal_rows()
        MODULE.assert_final_rows(rows, initial)
        mutations = [('project', 'last_reviewed_sha', None), ('request', 'state', 'RUNNING'),
            ('request', 'attempt_count', 2), ('request', 'result_code', 'REVIEW_FAILED'),
            ('request', 'request_id', 'different-request'), ('request', 'requested_by', 8), ('request', 'source', 'SCHEDULED'),
            ('request', 'requested_at', 'later'), ('request', 'claim_token', 'new-claim'), ('request', 'run_id', 12),
            ('request', 'finished_at', None), ('request', 'project_id', 2),
            ('runs', 'status', 'FAILED'), ('runs', 'reviewed_commits', 0), ('runs', 'progress_stage', 'REVIEWING'),
            ('runs', 'last_saved_at', None), ('runs', 'finished_at', 'different-time'), ('runs', 'error_message', 'unexpected'),
            ('runs', 'project_id', 2), ('runs', 'id', 12),
            ('commits', 'project_id', 2), ('commits', 'commit_sha', 'c' * 40),
            ('issues', 'project_id', 2), ('issues', 'assignee_id', 8), ('issues', 'reviewed_commit_id', 999)]
        for section, key, value in mutations:
            changed = copy.deepcopy(rows)
            target = changed[section][0] if isinstance(changed[section], list) else changed[section]
            target[key] = value
            with self.subTest(section=section, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_final_rows(changed, initial)

    def test_final_row_proof_rejects_duplicated_missing_or_wrong_completion_audits_and_rows(self):
        rows, initial = self.terminal_rows(), self.initial()
        changes = []
        for section in ('runs', 'commits', 'issues'):
            for replacement in ([], rows[section] + [dict(rows[section][0])]):
                changed = copy.deepcopy(rows)
                changed[section] = replacement
                changes.append(changed)
        for key, value in (('target_id', 12), ('target_type', 'PROJECT'), ('action', 'REVIEW_FAILED')):
            changed = copy.deepcopy(rows)
            changed['audits'][-1][key] = value
            changes.append(changed)
        for audits in (rows['audits'][:-1], rows['audits'] + [dict(rows['audits'][-1])],
                       rows['audits'] + [{'id': 35, 'action': 'REVIEW_FAILED', 'target_type': 'REVIEW_RUN', 'target_id': 11}],
                       rows['audits'] + [dict(rows['audits'][0])], rows['audits'] + [dict(rows['audits'][1])]):
            changed = copy.deepcopy(rows)
            changed['audits'] = audits
            changes.append(changed)
        for index, changed in enumerate(changes):
            with self.subTest(case=index), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_final_rows(changed, initial)

    def test_final_row_reader_uses_only_the_fixed_owned_schema_and_project(self):
        database = Mock(sql=Mock(return_value=json.dumps(self.terminal_rows())))
        self.assertEqual(self.terminal_rows(), MODULE.stored_final_rows(database, SCHEMA, 1))
        query = database.sql.call_args.args[0]
        for table in ('project', 'review_request', 'review_run', 'reviewed_commit', 'review_issue', 'audit_event'):
            self.assertIn(SCHEMA + '.' + table, query)
        self.assertNotIn('app_user', query)
        for schema, project in (('public', 1), (SCHEMA, True), (SCHEMA, 0), (SCHEMA, -1), (SCHEMA, '1;DROP')):
            database.reset_mock()
            with self.subTest(schema=schema, project=project), self.assertRaises(MODULE.VerificationError):
                MODULE.stored_final_rows(database, schema, project)
            database.sql.assert_not_called()

    def test_cli_is_bounded_fresh_local_and_explicitly_plain_protocol(self):
        variants = ({'port': 1023}, {'port': 65536}, {'timeout_seconds': 89}, {'timeout_seconds': 601},
                    {'startup_timeout_seconds': 14}, {'startup_timeout_seconds': 121},
                    {'report': self.workspace / 'outside.json'}, {'report': self.workspace / '.local/../outside.json'},
                    {'war': self.args.java}, {'psql': self.workspace / 'missing'})
        for changes in variants:
            with self.subTest(changes=changes), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**changes))
            port.assert_not_called()
        with patch.object(MODULE.LINUX, 'unused_port') as port:
            args = MODULE.arguments(self.argv())
        self.assertIs(args.plain_postgres_protocol, True)
        port.assert_called_once_with(18102)
        self.args.report.write_text('prior evidence', encoding='utf-8')
        with patch.object(MODULE.LINUX, 'unused_port') as port, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        port.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'prior evidence')

    def test_blocking_backend_requires_exact_observer_and_one_numeric_waiter(self):
        database = Mock()
        database.sql.side_effect = ['1', '123']
        self.assertEqual(MODULE.blocking_backend(database, SCHEMA, 99), 123)
        text = '\n'.join(call.args[0] for call in database.sql.call_args_list)
        self.assertIn("application_name='" + SCHEMA + "-observer'", text)
        self.assertIn('usename=current_user AND datname=current_database()', text)
        self.assertIn('99=ANY(pg_blocking_pids(pid))', text)
        for responses in (['0'], ['2'], ['1', '123\n456'], ['1', 'private-value'], ['1', '0']):
            database.sql.side_effect = responses
            with self.subTest(responses=responses), self.assertRaises(MODULE.VerificationError):
                MODULE.blocking_backend(database, SCHEMA, 99)
        database.sql.side_effect = ['1', '']
        self.assertIsNone(MODULE.blocking_backend(database, SCHEMA, 99))
        for schema, pid in (('public', 99), (SCHEMA, True), (SCHEMA, 0), (SCHEMA, 2147483648)):
            database.reset_mock()
            with self.subTest(schema=schema, pid=pid), self.assertRaises(MODULE.VerificationError):
                MODULE.blocking_backend(database, schema, pid)
            database.sql.assert_not_called()

    def test_observer_rejects_invalid_identifiers_before_spawning(self):
        with patch.object(MODULE.subprocess, 'Popen') as process:
            with self.assertRaises(MODULE.VerificationError):
                MODULE.OwnedObserver(Mock(), 'public')
            observer = MODULE.OwnedObserver(Mock(), SCHEMA)
            for value in (True, 0, -1, '1;DROP'):
                with self.subTest(value=value), self.assertRaises(MODULE.VerificationError):
                    observer.acquire(value)
            process.assert_not_called()

    def test_observer_startup_application_name_overrides_inherited_value_without_mutating_source(self):
        original = {'PGAPPNAME': 'unrelated-client',
                    'PGOPTIONS': '-c statement_timeout=5000 -c lock_timeout=5000',
                    'PGPASSWORD': 'synthetic-private'}
        database = types.SimpleNamespace(environment=dict(original), command=['synthetic-psql', '-X', '-A', '-t'])
        observer = MODULE.OwnedObserver(database, SCHEMA)
        process = Mock(poll=Mock(return_value=None))
        with patch.object(MODULE.subprocess, 'Popen', return_value=process) as spawn, \
                patch.object(MODULE.threading, 'Thread') as reader, \
                patch.object(observer, '_next', side_effect=['ACK_PID_123_' + observer.token,
                                                            'ACK_LOCKED_' + observer.token]):
            self.assertEqual(observer.acquire(7), 123)
        environment = spawn.call_args.kwargs['env']
        self.assertEqual(environment['PGAPPNAME'], SCHEMA + '-observer')
        self.assertEqual(environment['PGOPTIONS'], original['PGOPTIONS'] + ' -c idle_in_transaction_session_timeout=10000')
        self.assertNotIn('application_name=', environment['PGOPTIONS'])
        self.assertIsNot(environment, database.environment)
        self.assertEqual(database.environment, original)
        self.assertEqual(spawn.call_args.args[0], database.command + ['-q'])
        self.assertTrue(observer.locked)
        reader.return_value.start.assert_called_once_with()

    def test_observer_reader_accepts_only_bounded_private_marker_protocol(self):
        for mode in ('valid', 'unknown', 'oversized', 'unterminated', 'too-many'):
            observer = MODULE.OwnedObserver(Mock(), SCHEMA)
            valid = 'ACK_PID_123_' + observer.token + '\nACK_LOCKED_' + observer.token + '\n'
            text = {'valid': valid, 'unknown': 'private SQL error\n', 'oversized': 'x' * 300 + '\n',
                    'unterminated': 'ACK_LOCKED_' + observer.token, 'too-many': ('\n' * 17)}[mode]
            observer.process = Mock(stdout=io.StringIO(text))
            observer._read()
            with self.subTest(mode=mode):
                self.assertEqual(observer.reader_failed.is_set(), mode != 'valid')
                if mode == 'valid':
                    self.assertEqual(observer.lines.get_nowait(), 'ACK_PID_123_' + observer.token)
                    self.assertEqual(observer.lines.get_nowait(), 'ACK_LOCKED_' + observer.token)
                else:
                    self.assertTrue(observer.lines.empty())

    def test_observer_cleanup_rolls_back_then_only_terminates_its_owned_process_if_needed(self):
        observer = MODULE.OwnedObserver(Mock(), SCHEMA)
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired('synthetic', 3), subprocess.TimeoutExpired('synthetic', 2), 0]
        process.kill.side_effect = lambda: setattr(process.poll, 'return_value', 0)
        observer.process = process
        observer.close()
        process.stdin.write.assert_called_once_with('ROLLBACK;\n\\q\n')
        process.terminate.assert_called_once_with()
        process.kill.assert_called_once_with()
        self.assertTrue(observer.stopped())

    @contextmanager
    def run_fixture(self):
        database = Mock(user='reviewer_test', password='synthetic-private', url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration')
        dropped, events = [], []
        def sql(text):
            if text.startswith('DROP SCHEMA '):
                dropped.append(text)
            return 'reviewer_integration' if text == 'SELECT current_database();' else ''
        database.sql.side_effect = sql
        fixture = Mock(start=Mock(return_value='http://127.0.0.1:18103'))
        war = Mock(starts=1, process=None)
        war.stop.side_effect = lambda **_: events.append('war')
        observer = Mock(stopped=Mock(return_value=True))
        observer.close.side_effect = lambda: events.append('observer')
        proxy = Mock(port=55440)
        proxy.start.return_value = proxy
        proxy.metrics.return_value = {'mode': 'CLOSED', 'active': 0, 'failed': False}
        report = {'checks': {}}
        with ExitStack() as stack:
            stack.enter_context(patch.object(MODULE.HELPERS, 'Database', return_value=database))
            stack.enter_context(patch.object(MODULE.HELPERS, 'Fixture', return_value=fixture))
            war_factory = stack.enter_context(patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war))
            proxy_factory = stack.enter_context(patch.object(MODULE.PROXY, 'PgCommitAckProxy', return_value=proxy))
            stack.enter_context(patch.object(MODULE, 'OwnedObserver', return_value=observer))
            fingerprint = stack.enter_context(patch.object(MODULE.RATE, 'public_fingerprints', return_value={'table': 'private-fingerprint'}))
            stack.enter_context(patch.object(MODULE.NETWORK, 'server_identity', return_value='private-pid-start'))
            stack.enter_context(patch.object(MODULE.SHARED, 'schema_identity',
                side_effect=lambda *_: None if dropped else {'oid': 123, 'owner': 'reviewer_test'}))
            verify = stack.enter_context(patch.object(MODULE.SHARED, 'verify_schema'))
            sessions = stack.enter_context(patch.object(MODULE, 'owned_sessions', return_value=0))
            stack.enter_context(patch.object(MODULE.HELPERS, 'bounded_wait',
                side_effect=lambda check, *_: MODULE.require(check(), 'Synthetic sessions remain')))
            exercise = stack.enter_context(patch.object(MODULE, 'exercise'))
            yield types.SimpleNamespace(database=database, fixture=fixture, war=war, observer=observer, proxy=proxy,
                report=report, dropped=dropped, events=events, verify=verify, sessions=sessions, exercise=exercise,
                fingerprint=fingerprint, proxy_factory=proxy_factory, war_factory=war_factory)

    def test_normal_cleanup_releases_observer_first_and_verifies_schema_and_all_owned_sessions(self):
        with self.run_fixture() as fixture:
            MODULE.run(self.args, fixture.report)
            self.assertEqual(fixture.events, ['observer', 'war'])
            self.assertEqual(fixture.verify.call_count, 2)
            self.assertEqual(len(fixture.dropped), 1)
            schema = fixture.war_factory.call_args.args[2]
            self.assertEqual(fixture.dropped, ['DROP SCHEMA ' + schema + ' CASCADE;'])
            self.assertEqual(fixture.proxy_factory.call_args.kwargs,
                {'expected_application_name': schema, 'expected_user': 'reviewer_test', 'expected_database': 'reviewer_integration'})
            self.assertTrue(all(fixture.report['checks'][key] is True for key in MODULE.CLEANUP))
            self.assertFalse(fixture.report['cleanupFailed'])
            for secret in ('synthetic-private', 'private-fingerprint', 'private-pid-start'):
                self.assertNotIn(secret, json.dumps(fixture.report))

    def test_live_owned_resources_or_changed_identity_prevent_schema_and_work_deletion(self):
        for mode in ('observer', 'war', 'proxy', 'sessions', 'identity'):
            with self.subTest(mode=mode), self.run_fixture() as fixture:
                if mode == 'observer':
                    fixture.observer.close.side_effect = RuntimeError('private observer failure')
                elif mode == 'war':
                    fixture.war.process = Mock(poll=Mock(return_value=None))
                elif mode == 'proxy':
                    fixture.proxy.close.side_effect = RuntimeError('private proxy failure')
                elif mode == 'sessions':
                    fixture.sessions.return_value = 1
                else:
                    fixture.verify.side_effect = [None, MODULE.VerificationError('Changed schema')]
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.run(self.args, fixture.report)
                self.assertTrue(fixture.report['cleanupFailed'])
                self.assertEqual(fixture.dropped, [])
                self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])
                fixture.fixture.release_first_b.set.assert_called_once_with()

    def test_interruption_keeps_its_failure_after_safe_cleanup(self):
        with self.run_fixture() as fixture:
            fixture.exercise.side_effect = KeyboardInterrupt()
            with self.assertRaises(KeyboardInterrupt):
                MODULE.run(self.args, fixture.report)
            self.assertTrue(all(fixture.report['checks'][key] is True for key in MODULE.CLEANUP))
            self.assertEqual(fixture.events, ['observer', 'war'])

    def test_proxy_is_never_started_on_database_port_collision(self):
        with self.run_fixture() as fixture:
            fixture.database.url = 'jdbc:postgresql://127.0.0.1:18102/reviewer_integration'
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            fixture.proxy_factory.assert_not_called()
            fixture.war_factory.assert_not_called()
            self.assertTrue(fixture.report['checks']['ownedSchemaRemoved'])

    def test_explicit_final_phase_dispatch_does_not_run_the_per_commit_scenario(self):
        args = types.SimpleNamespace(**vars(self.args), phase='final-completion')
        with self.run_fixture() as fixture, patch.object(MODULE, 'exercise_final_completion') as final:
            MODULE.run(args, fixture.report)
            final.assert_called_once()
            fixture.exercise.assert_not_called()
            self.assertTrue(all(fixture.report['checks'][name] is True for name in MODULE.CLEANUP))

    def test_final_gate_interruption_preserves_failure_and_releases_all_owned_resources(self):
        args = types.SimpleNamespace(**vars(self.args), phase='final-completion')
        with self.run_fixture() as fixture, patch.object(MODULE, 'exercise_final_completion', side_effect=KeyboardInterrupt()):
            with self.assertRaises(KeyboardInterrupt):
                MODULE.run(args, fixture.report)
            self.assertEqual(['observer', 'war'], fixture.events)
            self.assertEqual(1, len(fixture.dropped))
            self.assertTrue(all(fixture.report['checks'][name] is True for name in MODULE.CLEANUP))

    def test_owned_war_plain_protocol_is_explicit_and_inherited_configuration_is_not_used(self):
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55440/reviewer_integration',
            user='reviewer_test', password='synthetic-private', sql=Mock(return_value='1'))
        war = MODULE.HELPERS.OwnedWar(self.args, database, SCHEMA, 'http://127.0.0.1:18103',
                                    self.workspace, self.workspace, 100)
        with patch.dict(MODULE.os.environ, {'JAVA_TOOL_OPTIONS': 'private-agent', 'AI_API_KEY': 'private-key'}), \
                patch.object(MODULE.HELPERS.time, 'monotonic', return_value=0), \
                patch.object(MODULE.HELPERS.subprocess, 'Popen') as process, \
                patch.object(MODULE.HELPERS, 'bounded_wait'), patch.object(MODULE.HELPERS.urllib.request, 'build_opener'):
            war.start(True)
        self.addCleanup(war.log_stream.close)
        environment = process.call_args.kwargs['env']
        self.assertEqual(environment['DB_URL'], database.url + '?currentSchema=' + SCHEMA + '&ApplicationName=' + SCHEMA
                         + '&sslmode=disable&gssEncMode=disable&assumeMinServerVersion=17')
        self.assertNotIn('JAVA_TOOL_OPTIONS', environment)
        self.assertEqual(environment['AI_API_KEY'], '')
        self.assertEqual(environment['JDBC_SOCKET_TIMEOUT_SECONDS'], '10')

    @staticmethod
    def successful_report(report):
        report['cleanupFailed'] = False
        report['checks'].update(dict.fromkeys((*MODULE.CLEANUP, *BEHAVIOR), True))
        report['proxy'] = {'commitAcknowledgementsDropped': 1, 'readyForQueryIdleDropped': True, 'targetClientClosed': True}

    def test_main_requires_fault_behavior_and_cleanup_proof_and_redacts_private_errors(self):
        for mode in ('success', 'private', 'sigterm', 'cleanupFailed', *MODULE.CLEANUP, *BEHAVIOR,
                     'commitAcknowledgementsDropped', 'truthyAck', 'readyForQueryIdleDropped', 'targetClientClosed'):
            installed, prior = [], Mock()
            def scenario(args, report):
                if mode == 'private':
                    raise RuntimeError('private SQL and password')
                if mode == 'sigterm':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                self.successful_report(report)
                if mode == 'cleanupFailed':
                    report['cleanupFailed'] = True
                elif mode == 'truthyAck':
                    report['proxy']['commitAcknowledgementsDropped'] = True
                elif mode in report['checks']:
                    report['checks'][mode] = False
                elif mode in report['proxy']:
                    report['proxy'][mode] = False
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *values: installed.append(values)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'sigterm' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertNotIn('private SQL and password', output.getvalue() + str(report))
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()

    def test_main_never_overwrites_report_created_after_validation(self):
        def scenario(args, report):
            self.successful_report(report)
            args.report.write_text('retain concurrent evidence', encoding='utf-8')
        output = io.StringIO()
        with patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'run', side_effect=scenario), \
                patch.object(MODULE.signal, 'signal'), patch.object(MODULE.signal, 'getsignal'), \
                redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(MODULE.main([]), 1)
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'retain concurrent evidence')
        self.assertNotIn('PASS:', output.getvalue())

    def test_final_main_requires_terminal_specific_proof_one_attempt_and_bounded_observation(self):
        args = types.SimpleNamespace(**vars(self.args), phase='final-completion')
        cases = ('success', *FINAL_BEHAVIOR, *MODULE.CLEANUP, 'attempt-two', 'attempt-bool',
                 'short-observation', 'text-observation', 'bool-observation', 'truthy-ack', 'cleanup-failed',
                 'missing-idle-ack', 'client-open', 'private-error')
        for mode in cases:
            def scenario(_args, report):
                if mode == 'private-error':
                    raise RuntimeError('private synthetic SQL password must never print')
                report['cleanupFailed'] = False
                report['checks'].update(dict.fromkeys((*MODULE.CLEANUP, *FINAL_BEHAVIOR), True))
                report['attempts'] = 1
                report['terminalObservationSeconds'] = 12
                report['proxy'] = {'commitAcknowledgementsDropped': 1, 'readyForQueryIdleDropped': True, 'targetClientClosed': True}
                if mode in report['checks']:
                    report['checks'][mode] = False
                elif mode == 'attempt-two': report['attempts'] = 2
                elif mode == 'attempt-bool': report['attempts'] = True
                elif mode == 'short-observation': report['terminalObservationSeconds'] = 11.99
                elif mode == 'text-observation': report['terminalObservationSeconds'] = '12'
                elif mode == 'bool-observation': report['terminalObservationSeconds'] = True
                elif mode == 'truthy-ack': report['proxy']['commitAcknowledgementsDropped'] = True
                elif mode == 'cleanup-failed': report['cleanupFailed'] = True
                elif mode == 'missing-idle-ack': report['proxy']['readyForQueryIdleDropped'] = False
                elif mode == 'client-open': report['proxy']['targetClientClosed'] = False
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal'), \
                    patch.object(MODULE.signal, 'signal'), redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(args.report.read_text(encoding='utf-8'))
            self.assertEqual(0 if mode == 'success' else 1, code)
            self.assertEqual('PASS' if mode == 'success' else 'FAIL', report['result'])
            self.assertEqual('final-completion', report['phase'])
            self.assertNotIn('private synthetic SQL password must never print', str(report) + output.getvalue())
            if mode == 'success':
                self.assertNotIn('savedRowsReusedWithoutAi', report['checks'])
                self.assertNotIn('sameLiveWarRecovered', report['checks'])
            args.report.unlink()


if __name__ == '__main__':
    unittest.main()
