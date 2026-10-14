"""Network-drill evidence and cleanup gates; no real WAR, database, sockets or waits."""
import copy
from contextlib import ExitStack, contextmanager, redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_network_recovery',
    Path(__file__).resolve().parents[1] / 'verify-review-network-recovery.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
ORIGIN = 'http://127.0.0.1:18103'
CLEANUP = ('ownedWarsStopped', 'ownedProxyStopped', 'ownedDatabaseSessionsGone',
           'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')


class NetworkRecoveryDrillTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='network-recovery-fixture-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port=18102,
            blackhole_seconds=65, timeout_seconds=300, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items()
                for part in ('--' + key.replace('_', '-'), str(value))]

    @staticmethod
    def running():
        return {'request': {'requestId': 'original-request', 'actor': 7, 'source': 'MANUAL',
                            'state': 'RUNNING', 'attempts': 1},
                'ownership': {'requestedAt': '2026-10-15T01:02:03Z', 'claim': 'original-claim', 'run': 11},
                'commits': [MODULE.HELPERS.SHA_A], 'issues': 1, 'assignees': [7], 'cursor': None,
                'runs': [{'state': 'RUNNING', 'commits': 1}],
                'progress': {'runId': 11, 'stage': 'REVIEWING', 'saved': 1,
                             'recordedAt': '2026-10-15T01:02:05Z', 'lastSavedAt': '2026-10-15T01:02:04Z'}}

    def test_preservation_rejects_changed_request_claim_run_actor_time_or_saved_work(self):
        original = self.running()
        MODULE.assert_preserved(copy.deepcopy(original), original)
        mutations = [('request', 'requestId', 'replacement'), ('request', 'actor', 8),
            ('request', 'source', 'SCHEDULED'), ('request', 'state', 'SUCCEEDED'), ('request', 'attempts', 2),
            ('ownership', 'requestedAt', 'later'), ('ownership', 'claim', 'replacement'), ('ownership', 'run', 12),
            ('root', 'commits', []), ('root', 'commits', [MODULE.HELPERS.SHA_B]),
            ('root', 'commits', [MODULE.HELPERS.SHA_A, MODULE.HELPERS.SHA_B]),
            ('root', 'issues', 0), ('root', 'issues', 2), ('root', 'cursor', MODULE.HELPERS.SHA_A),
            ('root', 'runs', [{'state': 'FAILED', 'commits': 1}]),
            ('root', 'runs', [{'state': 'RUNNING', 'commits': 0}]),
            ('root', 'runs', [{'state': 'RUNNING', 'commits': 1}, {'state': 'RUNNING', 'commits': 0}]),
            ('root', 'progress', None), ('progress', 'runId', 12), ('progress', 'saved', 0),
            ('progress', 'stage', 'FINALIZING'), ('progress', 'recordedAt', 'later'),
            ('progress', 'lastSavedAt', None)]
        for location, key, value in mutations:
            changed = copy.deepcopy(original)
            (changed if location == 'root' else changed[location])[key] = value
            with self.subTest(location=location, key=key, value=value), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_preserved(changed, original)
        self.assertEqual(original, self.running())

    def test_cli_rejects_unbounded_arguments_and_external_or_existing_reports_before_ports(self):
        variants = ({'port': 1023}, {'port': 65536}, {'blackhole_seconds': 44}, {'blackhole_seconds': 181},
            {'blackhole_seconds': 180, 'timeout_seconds': 269}, {'timeout_seconds': 601},
            {'startup_timeout_seconds': 14}, {'startup_timeout_seconds': 121},
            {'war': self.args.java}, {'report': self.workspace / 'outside.json'},
            {'report': self.workspace / '.local/../outside.json'})
        for changed in variants:
            with self.subTest(changed=changed), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**changed))
            port.assert_not_called()
        self.args.report.write_text('retained prior evidence', encoding='utf-8')
        with patch.object(MODULE.LINUX, 'unused_port') as port, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        port.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'retained prior evidence')

    def test_cli_accepts_exact_bounds_and_checks_only_its_explicit_war_port(self):
        for values in ({'port': 1024, 'blackhole_seconds': 45, 'timeout_seconds': 135, 'startup_timeout_seconds': 15},
                       {'port': 65535, 'blackhole_seconds': 180, 'timeout_seconds': 600, 'startup_timeout_seconds': 120}):
            with self.subTest(values=values), patch.object(MODULE.LINUX, 'unused_port') as port:
                args = MODULE.arguments(self.argv(**values))
            port.assert_called_once_with(values['port'])
            self.assertEqual(args.java, self.args.java)
            self.assertEqual(args.psql, self.args.psql)
            self.assertEqual(args.report, self.args.report)
        with patch.object(MODULE.LINUX, 'unused_port', side_effect=MODULE.LINUX.SafetyError('Port occupied')), \
                self.assertRaises(MODULE.LINUX.SafetyError):
            MODULE.arguments(self.argv())

    def test_cli_missing_paths_and_nonfiles_are_rejected_without_opening_ports(self):
        for name, value in (('war', self.workspace / 'missing.war'), ('java', self.workspace),
                            ('psql', self.workspace / 'missing-psql')):
            with self.subTest(name=name), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**{name: value}))
            port.assert_not_called()

    def test_observer_database_rejects_remote_wrong_database_and_url_options_before_any_process(self):
        bad_urls = ('jdbc:postgresql://remote.example:55439/reviewer_integration',
                    'jdbc:postgresql://127.0.0.1:55439/production',
                    'jdbc:postgresql://127.0.0.1:55439/reviewer_integration?currentSchema=public',
                    'jdbc:postgresql://127.0.0.1:0/reviewer_integration',
                    'jdbc:postgresql://127.0.0.1:65536/reviewer_integration')
        with patch.object(MODULE.HELPERS.subprocess, 'run') as process:
            for value in bad_urls:
                with self.subTest(url=value), self.assertRaises(MODULE.VerificationError):
                    MODULE.HELPERS.Database(self.args.psql, value, self.workspace)
        process.assert_not_called()

    @contextmanager
    def run_fixture(self):
        database = Mock(user='reviewer_test', password='synthetic-private-password',
                        url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration')
        dropped = []
        def sql(text):
            if text.startswith('DROP SCHEMA '):
                dropped.append(text)
            return 'reviewer_integration' if text == 'SELECT current_database();' else ''
        database.sql.side_effect = sql
        fixture = Mock(start=Mock(return_value=ORIGIN))
        war = Mock(starts=1, process=None)
        proxy = Mock(port=55440)
        proxy.start.return_value = proxy
        proxy.metrics.return_value = {'mode': 'CLOSED', 'active': 0, 'failed': False}
        report = {'checks': {}}
        original = {'app_user': 'private-fingerprint-fixture', 'review_issue': 'retained'}
        def wait(check, *_args):
            MODULE.require(check(), 'Synthetic owned sessions did not close')
        with ExitStack() as stack:
            stack.enter_context(patch.object(MODULE.HELPERS, 'Database', return_value=database))
            stack.enter_context(patch.object(MODULE.HELPERS, 'Fixture', return_value=fixture))
            war_factory = stack.enter_context(patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war))
            proxy_factory = stack.enter_context(patch.object(MODULE.PROXY, 'OwnedTcpProxy', return_value=proxy))
            fingerprints = stack.enter_context(patch.object(MODULE.RATE, 'public_fingerprints', return_value=original))
            stack.enter_context(patch.object(MODULE, 'server_identity', return_value='private-server-identity'))
            stack.enter_context(patch.object(MODULE.SHARED, 'schema_identity',
                side_effect=lambda *_: None if dropped else {'oid': 123, 'owner': 'reviewer_test'}))
            verify = stack.enter_context(patch.object(MODULE.SHARED, 'verify_schema'))
            sessions = stack.enter_context(patch.object(MODULE, 'owned_sessions', return_value=0))
            stack.enter_context(patch.object(MODULE.HELPERS, 'bounded_wait', side_effect=wait))
            exercise = stack.enter_context(patch.object(MODULE, 'exercise'))
            yield types.SimpleNamespace(database=database, fixture=fixture, war=war, proxy=proxy, report=report,
                verify=verify, fingerprints=fingerprints, sessions=sessions, dropped=dropped,
                exercise=exercise, war_factory=war_factory, proxy_factory=proxy_factory)

    def test_normal_cleanup_removes_only_verified_schema_and_owned_work_with_original_tables_preserved(self):
        with self.run_fixture() as fixture:
            MODULE.run(self.args, fixture.report)
            fixture.war.stop.assert_called_once_with(force=True)
            fixture.proxy.close.assert_called_once_with()
            fixture.fixture.close.assert_called_once_with()
            fixture.fixture.release_first_b.set.assert_called_once_with()
            fixture.fixture.release_second_b.set.assert_called_once_with()
            self.assertEqual(fixture.verify.call_count, 2)
            self.assertEqual(len(fixture.dropped), 1)
            schema = fixture.war_factory.call_args.args[2]
            self.assertRegex(schema, r'^restart_test_[0-9a-f]{32}$')
            self.assertEqual(fixture.dropped, ['DROP SCHEMA ' + schema + ' CASCADE;'])
            self.assertEqual(fixture.proxy_factory.call_args.args, (55439,))
            routed = fixture.war_factory.call_args.args[1]
            self.assertEqual(routed.url, 'jdbc:postgresql://127.0.0.1:55440/reviewer_integration')
            self.assertEqual(routed.sql, fixture.database.sql)
            self.assertFalse(any(call.args[0].startswith(('UPDATE ', 'DELETE ', 'ALTER '))
                                 for call in fixture.database.sql.call_args_list))
            self.assertFalse(fixture.report['cleanupFailed'])
            self.assertTrue(all(fixture.report['checks'][key] is True for key in CLEANUP))
            self.assertNotIn('private-fingerprint', json.dumps(fixture.report))
            self.assertNotIn('private-server-identity', json.dumps(fixture.report))
            self.assertNotIn('synthetic-private-password', json.dumps(fixture.report))

    def test_war_database_port_collision_is_rejected_before_proxy_or_war_start(self):
        with self.run_fixture() as fixture:
            fixture.database.url = 'jdbc:postgresql://127.0.0.1:18102/reviewer_integration'
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            fixture.proxy_factory.assert_not_called()
            fixture.war_factory.assert_not_called()
            fixture.exercise.assert_not_called()
            self.assertTrue(fixture.report['checks']['ownedSchemaRemoved'])

    def test_live_war_or_unclean_proxy_or_remaining_sessions_prevents_schema_and_work_removal(self):
        for mode in ('war', 'proxy-exception', 'proxy-active', 'sessions'):
            with self.subTest(mode=mode), self.run_fixture() as fixture:
                if mode == 'war':
                    fixture.war.process = Mock()
                    fixture.war.process.poll.return_value = None
                    fixture.war.stop.side_effect = RuntimeError('private stop failure')
                elif mode == 'proxy-exception':
                    fixture.proxy.close.side_effect = RuntimeError('private proxy failure')
                elif mode == 'proxy-active':
                    fixture.proxy.metrics.return_value = {'mode': 'CLOSED', 'active': 1, 'failed': False}
                else:
                    fixture.sessions.return_value = 1
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.run(self.args, fixture.report)
                self.assertTrue(fixture.report['cleanupFailed'])
                self.assertFalse(fixture.report['checks']['ownedSchemaRemoved'])
                self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])
                self.assertEqual(fixture.dropped, [])
                fixture.fixture.release_first_b.set.assert_called_once_with()
                fixture.fixture.release_second_b.set.assert_called_once_with()

    def test_changed_schema_identity_never_drops_replacement_schema(self):
        with self.run_fixture() as fixture:
            fixture.verify.side_effect = [None, MODULE.VerificationError('Schema identity changed')]
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            self.assertEqual(fixture.dropped, [])
            self.assertTrue(fixture.report['cleanupFailed'])
            self.assertFalse(fixture.report['checks']['ownedSchemaRemoved'])
            self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])

    def test_changed_original_table_fingerprint_cannot_be_reported_as_preserved(self):
        with self.run_fixture() as fixture:
            fixture.fingerprints.side_effect = [{'app_user': 'before'}, {'app_user': 'after'}]
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            self.assertTrue(fixture.report['checks']['ownedSchemaRemoved'])
            self.assertTrue(fixture.report['cleanupFailed'])
            self.assertIsNot(fixture.report['checks'].get('originalTestTablesPreserved'), True)
            self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])

    def test_interruption_still_releases_fixture_stops_owned_resources_and_preserves_exception(self):
        with self.run_fixture() as fixture:
            interruption = KeyboardInterrupt()
            fixture.exercise.side_effect = interruption
            with self.assertRaises(KeyboardInterrupt) as caught:
                MODULE.run(self.args, fixture.report)
            self.assertIs(caught.exception, interruption)
            fixture.war.stop.assert_called_once_with(force=True)
            fixture.proxy.close.assert_called_once_with()
            fixture.fixture.release_first_b.set.assert_called_once_with()
            fixture.fixture.release_second_b.set.assert_called_once_with()
            self.assertTrue(all(fixture.report['checks'][key] is True for key in CLEANUP))

    def test_owned_war_uses_proxy_only_for_its_schema_and_discards_inherited_secrets(self):
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55440/reviewer_integration',
            user='reviewer_test', password='synthetic-private-password', sql=Mock(return_value='1'))
        war = MODULE.HELPERS.OwnedWar(self.args, database, SCHEMA, ORIGIN, self.workspace, self.workspace, 100)
        with patch.dict(MODULE.os.environ, {'AI_API_KEY': 'inherited-private-key', 'JAVA_TOOL_OPTIONS': 'private-agent',
                                         'SPRING_CONFIG_LOCATION': 'private-path', 'DB_URL': 'remote-private-url'}), \
                patch.object(MODULE.HELPERS.time, 'monotonic', return_value=0), \
                patch.object(MODULE.HELPERS.subprocess, 'Popen') as process, \
                patch.object(MODULE.HELPERS, 'bounded_wait'), \
                patch.object(MODULE.HELPERS.urllib.request, 'build_opener'):
            war.start(True)
        self.addCleanup(war.log_stream.close)
        environment = process.call_args.kwargs['env']
        self.assertEqual(environment['DB_URL'], database.url + '?currentSchema=' + SCHEMA + '&ApplicationName=' + SCHEMA)
        self.assertEqual(environment['REVIEW_ENABLED'], 'false')
        self.assertEqual(environment['REVIEW_WORKER_ENABLED'], 'true')
        self.assertEqual(environment['AI_API_KEY'], '')
        self.assertEqual(environment['AI_BASE_URL'], ORIGIN)
        self.assertEqual(environment['JDBC_SOCKET_TIMEOUT_SECONDS'], '10')
        self.assertEqual(environment['SERVER_ADDRESS'], '127.0.0.1')
        self.assertNotIn('JAVA_TOOL_OPTIONS', environment)
        self.assertNotIn('SPRING_CONFIG_LOCATION', environment)
        self.assertIn('--spring.flyway.default-schema=' + SCHEMA, process.call_args.args[0])

    def test_main_requires_every_cleanup_flag_is_true_and_redacts_failures(self):
        for mode in ('private', 'sigterm', 'cleanupFailed', *CLEANUP, 'success'):
            installed, prior = [], Mock()
            def scenario(args, report):
                if mode == 'private':
                    raise RuntimeError('private SQL credential response fixture')
                if mode == 'sigterm':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                report['cleanupFailed'] = mode == 'cleanupFailed'
                report['checks'].update({key: mode != key for key in CLEANUP})
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), \
                    patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'sigterm' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.assertNotIn('private SQL credential response fixture', str(report) + output.getvalue())
            if mode in ('private', 'sigterm'):
                self.assertEqual(report['failure'], 'Private error details withheld')
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()

    def test_main_preserves_an_evidence_file_created_after_argument_validation(self):
        def scenario(args, report):
            report['cleanupFailed'] = False
            report['checks'].update({key: True for key in CLEANUP})
            args.report.write_text('retained concurrent evidence', encoding='utf-8')
        output = io.StringIO()
        with patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'run', side_effect=scenario), \
                patch.object(MODULE.signal, 'signal'), patch.object(MODULE.signal, 'getsignal'), \
                redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(MODULE.main([]), 1)
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'retained concurrent evidence')
        self.assertIn('FAIL: fresh network recovery report could not be written', output.getvalue())
        self.assertNotIn('PASS:', output.getvalue())

    def test_main_does_not_treat_missing_or_truthy_cleanup_values_as_proof(self):
        for value in (None, 1, 'true'):
            def scenario(args, report):
                report['cleanupFailed'] = False
                report['checks'].update({key: True for key in CLEANUP})
                if value is None:
                    del report['checks']['ownedSchemaRemoved']
                else:
                    report['checks']['ownedSchemaRemoved'] = value
            output = io.StringIO()
            with self.subTest(value=value), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'signal'), \
                    patch.object(MODULE.signal, 'getsignal'), redirect_stdout(output), redirect_stderr(output):
                self.assertEqual(MODULE.main([]), 1)
            self.assertEqual(json.loads(self.args.report.read_text(encoding='utf-8'))['result'], 'FAIL')
            self.assertNotIn('PASS:', output.getvalue())
            self.args.report.unlink()


if __name__ == '__main__':
    unittest.main()
