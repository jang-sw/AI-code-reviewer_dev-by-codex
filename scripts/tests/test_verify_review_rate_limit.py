"""Rate-limit drill boundaries; no WAR, database, HTTP server or process is started."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_rate_limit',
    Path(__file__).resolve().parents[1] / 'verify-review-rate-limit.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
ORIGIN = 'http://127.0.0.1:18096'


class RateLimitDrillBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='rate-limit-drill-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local' / 'result.json', port=18096,
            timeout_seconds=180, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        values = vars(self.args) | changes
        return [part for key, value in values.items() for part in ('--' + key.replace('_', '-'), str(value))]

    def waiting(self):
        return {'request': {'requestId': 'private-request', 'actor': 7, 'source': 'MANUAL', 'state': 'QUEUED', 'attempts': 1},
                'wait': {'requestedAt': '2026-10-07T00:00:00Z', 'availableAt': '2026-10-07T00:01:00Z',
                         'availableLabel': '2026-10-07 00:01:00', 'future': True, 'code': 'AI_RATE_LIMITED',
                         'count': 1, 'first': '2026-10-07T00:00:01Z'},
                'commits': [MODULE.HELPERS.SHA_A], 'issues': 1, 'assignees': [7], 'cursor': None, 'progress': None}

    def test_cli_requires_local_owned_fresh_report_and_bounded_arguments_before_socket(self):
        for changes in ({'report': self.workspace / 'outside.json'}, {'port': 0}, {'port': 65536},
                        {'timeout_seconds': 89}, {'startup_timeout_seconds': 121}):
            with patch.object(MODULE.socket, 'socket') as socket, self.subTest(changes=changes), self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(**changes))
            socket.assert_not_called()
        self.args.report.write_text('preserved evidence', encoding='utf-8')
        with patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        socket.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'preserved evidence')

    def test_cli_preserves_explicit_executable_names_and_rejects_occupied_port(self):
        with patch.object(MODULE.socket, 'socket'):
            args = MODULE.arguments(self.argv())
        self.assertEqual(args.java, self.args.java)
        self.assertEqual(args.psql, self.args.psql)
        with patch.object(MODULE.socket, 'socket') as socket:
            socket.return_value.__enter__.return_value.bind.side_effect = OSError('private fixture')
            with self.assertRaises(MODULE.VerificationError) as error:
                MODULE.arguments(self.argv())
        self.assertNotIn('private fixture', str(error.exception))

    def test_each_actual_rate_limit_occurs_only_once_and_git_requires_explicit_arming(self):
        fixture = MODULE.RateLimitFixture()
        self.assertFalse(fixture.limit_ai(MODULE.HELPERS.SHA_A))
        self.assertTrue(fixture.limit_ai(MODULE.HELPERS.SHA_B))
        self.assertFalse(fixture.limit_ai(MODULE.HELPERS.SHA_B))
        path = '/commits/' + MODULE.HELPERS.SHA_B + '/diff'
        self.assertFalse(fixture.limit_git('restart', path))
        fixture.git_armed = True
        self.assertFalse(fixture.limit_git('peer', path))
        self.assertTrue(fixture.limit_git('restart', path))
        self.assertFalse(fixture.limit_git('restart', path))
        self.assertTrue(fixture.release_first_b.is_set())
        self.assertTrue(fixture.release_second_b.is_set())

    def fixture_handler(self):
        class Parent:
            def check_headers(self):
                pass
            def do_GET(self):
                self.forwarded = self.path
            def do_POST(self):
                self.forwarded = self.rfile.read()
        fixture = MODULE.RateLimitFixture()
        def fake_start(_self):
            _self.server = types.SimpleNamespace(RequestHandlerClass=Parent)
            return ORIGIN
        with patch.object(MODULE.HELPERS.Fixture, 'start', fake_start):
            self.assertEqual(fixture.start(), ORIGIN)
        handler = fixture.server.RequestHandlerClass.__new__(fixture.server.RequestHandlerClass)
        handler.reply = Mock()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()
        handler.connection = Mock()
        return fixture, handler

    def test_http_fixture_returns_real_retry_after_headers_and_preserves_peer_route(self):
        fixture, handler = self.fixture_handler()
        fixture.git_armed = True
        suffix = '/repository/commits/' + MODULE.HELPERS.SHA_B + '/diff?unidiff=true'
        handler.path = '/api/v4/projects/fixture%2Fpeer' + suffix
        original = handler.path
        handler.do_GET()
        self.assertEqual(handler.path, original)
        self.assertIn('/fixture/restart/', handler.forwarded)
        handler.send_response.assert_not_called()
        handler.path = '/api/v4/projects/fixture%2Frestart' + suffix
        handler.do_GET()
        handler.send_response.assert_called_once_with(429)
        handler.send_header.assert_any_call('Retry-After', '30')
        handler.send_header.assert_any_call('Content-Length', '0')
        self.assertEqual(fixture.observed()['git_429'], 1)
        self.assertFalse(fixture.errors)

    def test_ai_fixture_reads_bounded_payload_once_and_replays_it_to_existing_validation(self):
        fixture, handler = self.fixture_handler()
        handler.path = '/api/chat'
        payload = json.dumps({'messages': [{}, {'content': json.dumps({'commitSha': MODULE.HELPERS.SHA_B})}]}).encode()
        handler.headers = {'Content-Length': str(len(payload))}
        original = io.BytesIO(payload)
        handler.rfile = original
        handler.do_POST()
        handler.send_response.assert_called_once_with(429)
        handler.send_header.assert_any_call('Retry-After', '60')
        self.assertIs(handler.rfile, original)
        self.assertEqual(fixture.observed()['ai_B.java'], 1)
        handler.rfile = io.BytesIO(payload)
        handler.do_POST()
        self.assertEqual(handler.forwarded, payload)
        self.assertEqual(fixture.observed()['ai_429'], 1)
        self.assertFalse(fixture.errors)

    def test_waiting_assertion_detects_identity_loss_changed_actor_time_or_saved_work(self):
        original = self.waiting()
        MODULE.assert_waiting(original, original, 'AI', 1)
        changes = [('request', 'requestId', 'foreign'), ('request', 'actor', 8), ('request', 'source', 'SCHEDULED'),
                   ('request', 'state', 'RUNNING'), ('wait', 'requestedAt', 'other'), ('wait', 'code', 'GIT_RATE_LIMITED'),
                   ('wait', 'count', 0), ('root', 'commits', []), ('root', 'issues', 2), ('root', 'cursor', MODULE.HELPERS.SHA_A),
                   ('root', 'assignees', [8]), ('root', 'progress', {'saved': 1})]
        for location, key, value in changes:
            altered = copy.deepcopy(original)
            (altered if location == 'root' else altered[location])[key] = value
            with self.subTest(location=location, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_waiting(altered, original, 'AI', 1)

    def test_sql_expiry_requires_owned_schema_and_scopes_exact_project_service_and_origin(self):
        database = Mock()
        with patch.object(MODULE.SHARED, 'verify_schema') as verify:
            MODULE.expire_owned(database, SCHEMA, {'oid': 10}, 5, 'AI', ORIGIN)
        verify.assert_called_once_with(database, SCHEMA, {'oid': 10})
        query = database.sql.call_args.args[0]
        self.assertTrue(query.startswith('BEGIN;'))
        self.assertIn("WHERE project_id=5 AND state='QUEUED' AND result_code='AI_RATE_LIMITED'", query)
        self.assertIn("WHERE service='AI' AND origin_hash='" + MODULE.hashlib.sha256(ORIGIN.encode()).hexdigest() + "'", query)
        self.assertNotIn('DELETE', query)
        for schema, project, service, origin in (('public', 5, 'AI', ORIGIN), (SCHEMA, 0, 'AI', ORIGIN),
                (SCHEMA, 5, 'OTHER', ORIGIN), (SCHEMA, 5, 'AI', 'https://external.example')):
            database.reset_mock()
            with self.subTest(schema=schema, project=project, service=service), self.assertRaises(MODULE.VerificationError):
                MODULE.expire_owned(database, schema, {}, project, service, origin)
            database.sql.assert_not_called()
        with patch.object(MODULE.SHARED, 'verify_schema', side_effect=MODULE.VerificationError('changed')), self.assertRaises(MODULE.VerificationError):
            MODULE.expire_owned(database, SCHEMA, {}, 5, 'AI', ORIGIN)
        database.sql.assert_not_called()

    def test_packaged_page_probe_rejects_wrong_state_missing_utc_or_borrowed_progress(self):
        page = ('data-request-state="QUEUED" AI 서비스 호출 제한 2026-10-07 00:01:00 '
                '재시도 가능 시각 (UTC) 실행이나 완료를 보장하지 않습니다')
        browser = Mock()
        browser.request.return_value = ('unused', page)
        MODULE.check_waiting_pages(browser, 5, self.waiting(), 'AI')
        self.assertEqual(browser.request.call_count, 2)
        for altered in (page.replace('QUEUED', 'RUNNING'), page.replace('(UTC)', ''), page + ' id="review-progress"'):
            browser.request.return_value = ('unused', altered)
            with self.assertRaises(MODULE.VerificationError):
                MODULE.check_waiting_pages(browser, 5, self.waiting(), 'AI')

    def test_exhausted_page_probe_rejects_automatic_retry_promises(self):
        page = 'data-request-state="FAILED" 자동 재시도를 중단했습니다. 직접 리뷰를 요청해 주세요.'
        browser = Mock()
        browser.request.return_value = ('unused', page)
        MODULE.check_exhausted_pages(browser, 5)
        self.assertEqual(browser.request.call_count, 2)
        for altered in (page.replace('FAILED', 'QUEUED'), page.replace('자동 재시도를 중단', ''),
                        page + ' 남은 이력은 다음 예약 요청', page + ' 자동 리뷰가 켜져 있어요'):
            browser.request.return_value = ('unused', altered)
            with self.assertRaises(MODULE.VerificationError):
                MODULE.check_exhausted_pages(browser, 5)

    def test_baseline_fingerprints_use_all_fourteen_public_tables_and_reject_bad_results(self):
        database = Mock()
        database.sql.return_value = '3:' + 'a' * 32
        result = MODULE.public_fingerprints(database)
        self.assertEqual(len(result), 14)
        self.assertEqual(tuple(result), MODULE.BACKUP.TABLES)
        for table, call in zip(MODULE.BACKUP.TABLES, database.sql.call_args_list):
            self.assertIn('FROM public.' + table + ' t;', call.args[0])
            self.assertTrue(call.args[0].startswith('SELECT '))
        database.sql.return_value = 'synthetic-private-row'
        with self.assertRaises(MODULE.VerificationError) as error:
            MODULE.public_fingerprints(database)
        self.assertNotIn('synthetic-private-row', str(error.exception))

    def test_quiet_observation_fails_on_new_http_or_dead_war_without_sleeping(self):
        for dead in (False, True):
            fixture, war = Mock(), Mock()
            fixture.observed.side_effect = [{'calls': 1}, {'calls': 2}]
            war.process.poll.return_value = 1 if dead else None
            with patch.object(MODULE.time, 'monotonic', return_value=0), patch.object(MODULE.time, 'sleep') as sleep, self.assertRaises(MODULE.VerificationError):
                MODULE.quiet_wait(fixture, war, 100)
            sleep.assert_not_called()

    def test_main_hides_arbitrary_errors_preserves_interrupt_and_requires_cleanup_for_pass(self):
        for mode in ('private-error', 'interrupted', 'incomplete', 'success'):
            installed = []
            prior = Mock()
            def scenario(args, report):
                if mode == 'private-error':
                    raise RuntimeError('synthetic-private-response-and-password')
                if mode == 'interrupted':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                report['cleanupFailed'] = mode != 'success'
                report['checks'].update({key: mode == 'success' for key in
                    ('ownedWarsStopped', 'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')})
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'interrupted' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.assertIn('synthetic', report['limitations'][1])
            self.assertNotIn('synthetic-private-response-and-password', str(report) + output.getvalue())
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()

    def test_run_never_drops_schema_if_identity_was_not_established(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE, 'public_fingerprints', return_value={'app_user': '0:' + 'a' * 32}), \
                patch.object(MODULE.SHARED, 'schema_identity', return_value=None), \
                patch.object(MODULE.SHARED, 'verify_schema', side_effect=MODULE.VerificationError('not owned')), \
                patch.object(MODULE, 'RateLimitFixture') as fixture, self.assertRaises(MODULE.VerificationError):
            MODULE.run(self.args, report)
        self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
        fixture.return_value.start.assert_not_called()
        fixture.return_value.close.assert_called_once()
        self.assertTrue(report['cleanupFailed'])
        self.assertFalse(report['checks']['ownedSchemaRemoved'])

    def test_run_does_not_remove_schema_or_work_if_its_owned_war_remains_alive(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        war = Mock(starts=1)
        war.process.poll.return_value = None
        war.stop.side_effect = OSError('synthetic private failure')
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE, 'public_fingerprints', return_value={'app_user': '0:' + 'a' * 32}), \
                patch.object(MODULE.SHARED, 'schema_identity', return_value={'oid': 1, 'owner': 'reviewer_test'}), \
                patch.object(MODULE.SHARED, 'verify_schema'), patch.object(MODULE, 'RateLimitFixture'), \
                patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war), patch.object(MODULE, 'exercise'), \
                self.assertRaises(MODULE.VerificationError):
            MODULE.run(self.args, report)
        self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
        self.assertFalse(report['checks']['ownedWarsStopped'])
        self.assertFalse(report['checks']['ownedWorkRemoved'])

    def test_run_confirms_owned_cleanup_and_unchanged_original_tables_before_success(self):
        for changed in (False, True):
            database = Mock(user='reviewer_test')
            database.sql.return_value = 'reviewer_integration'
            war = Mock(starts=3, process=None)
            before = {'app_user': '0:' + 'a' * 32}
            after = {'app_user': '1:' + 'b' * 32} if changed else before
            report = {'checks': {}}
            with self.subTest(changed=changed), patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                    patch.object(MODULE, 'public_fingerprints', side_effect=[before, after]), \
                    patch.object(MODULE.SHARED, 'schema_identity', side_effect=[{'oid': 1, 'owner': 'reviewer_test'}, None]), \
                    patch.object(MODULE.SHARED, 'verify_schema') as verify, patch.object(MODULE, 'RateLimitFixture'), \
                    patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war), patch.object(MODULE, 'exercise'):
                if changed:
                    with self.assertRaises(MODULE.VerificationError):
                        MODULE.run(self.args, report)
                else:
                    MODULE.run(self.args, report)
            self.assertEqual(report['cleanupFailed'], changed)
            self.assertEqual(report['checks'].get('originalTestTablesPreserved', False), not changed)
            self.assertTrue(report['checks']['ownedWarsStopped'])
            self.assertTrue(report['checks']['ownedSchemaRemoved'])
            self.assertTrue(report['checks']['ownedWorkRemoved'])
            self.assertEqual(verify.call_count, 2)
            war.stop.assert_called_once_with(force=True)
            self.assertEqual(sum('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list), 1)


if __name__ == '__main__':
    unittest.main()
