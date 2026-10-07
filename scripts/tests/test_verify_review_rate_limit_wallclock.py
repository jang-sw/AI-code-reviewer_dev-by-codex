"""Wall-clock drill boundaries; no real process, HTTP server, database or sleep."""
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


SPEC = importlib.util.spec_from_file_location('verify_review_rate_limit_wallclock',
    Path(__file__).resolve().parents[1] / 'verify-review-rate-limit-wallclock.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
ORIGIN = 'http://127.0.0.1:18100'


class WallClockDrillTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='wallclock-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port_a=18097, port_b=18098,
            timeout_seconds=240, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items()
                for part in ('--' + key.replace('_', '-'), str(value))]

    def pending(self, count=1):
        return {'request': {'requestId': 'fixture-request', 'actor': 5, 'source': 'MANUAL', 'state': 'QUEUED', 'attempts': 1},
                'wait': {'requestedAt': 'fixture-original-time', 'code': 'GIT_RATE_LIMITED', 'count': count, 'first': 'fixture-first-time'},
                'commits': [], 'issues': 0, 'assignees': [], 'cursor': None, 'progress': None}

    def completed(self, attempts=2, count=1):
        value = self.pending(count)
        value['request'].update(state='SUCCEEDED', attempts=attempts)
        value.update(commits=[MODULE.HELPERS.SHA_A, MODULE.HELPERS.SHA_B], issues=2, assignees=[5, 5], cursor=MODULE.HELPERS.SHA_B,
            runs=([{'state': 'FAILED', 'commits': 0}] if attempts == 2 else []) + [{'state': 'SUCCEEDED', 'commits': 2}])
        return value

    def test_cli_requires_distinct_ports_bounded_time_and_new_local_report(self):
        for changes in ({'port_a': 0}, {'port_b': 65536}, {'port_b': 18097}, {'timeout_seconds': 119},
                        {'timeout_seconds': 601}, {'startup_timeout_seconds': 121}, {'report': self.workspace / 'outside.json'}):
            with self.subTest(changes=changes), patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(**changes))
            socket.assert_not_called()
        self.args.report.write_text('preserved', encoding='utf-8')
        with patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        socket.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'preserved')

    def test_cli_probes_both_loopback_ports_and_preserves_executable_paths(self):
        with patch.object(MODULE.socket, 'socket') as socket:
            args = MODULE.arguments(self.argv())
        self.assertEqual(args.java, self.args.java)
        self.assertEqual(args.psql, self.args.psql)
        self.assertEqual([call.args for call in socket.return_value.__enter__.return_value.bind.call_args_list],
                         [(('127.0.0.1', 18097),), (('127.0.0.1', 18098),)])
        with patch.object(MODULE.socket, 'socket') as socket:
            socket.return_value.__enter__.return_value.bind.side_effect = OSError('private-os-detail')
            with self.assertRaises(MODULE.VerificationError) as error:
                MODULE.arguments(self.argv())
        self.assertNotIn('private-os-detail', str(error.exception))

    def test_clock_records_first_response_once_and_rejects_peer_as_first_request(self):
        fixture = MODULE.WallClockFixture(True)
        with patch.object(MODULE.time, 'monotonic', side_effect=[10.0, 75.0]), patch.object(MODULE.time, 'time', return_value=1000.0):
            self.assertTrue(fixture.observe_git('restart'))
            self.assertFalse(fixture.observe_git('peer'))
        self.assertEqual(fixture.timing(), ((10.0, 1000.0), ((10.0, 'restart'), (75.0, 'peer'))))
        self.assertEqual(MODULE.no_early_calls(fixture, require_retry=True), [65.0])
        other = MODULE.WallClockFixture(True)
        with self.assertRaises(MODULE.VerificationError):
            other.observe_git('peer')
        self.assertEqual(other.timing(), (None, ()))

    def test_no_early_gate_rejects_single_microsecond_early_call_and_missing_retry(self):
        fixture = MODULE.WallClockFixture(True)
        fixture.limited_event = (100.0, 1000.0)
        fixture.git_events = [(100.0, 'restart')]
        self.assertEqual(MODULE.no_early_calls(fixture), [])
        with self.assertRaises(MODULE.VerificationError):
            MODULE.no_early_calls(fixture, require_retry=True)
        fixture.git_events.append((164.999999, 'peer'))
        with self.assertRaises(MODULE.VerificationError):
            MODULE.no_early_calls(fixture)
        fixture.git_events[-1] = (165.0, 'peer')
        self.assertEqual(MODULE.no_early_calls(fixture, require_retry=True), [65.0])
        fixture.git_events.append((170.0, 'restart'))
        self.assertEqual(MODULE.no_early_calls(fixture, require_retry=True), [65.0, 70.0])

    def test_origin_y_is_unlimited_and_event_recording_is_bounded(self):
        fixture = MODULE.WallClockFixture(False)
        with patch.object(MODULE.time, 'monotonic', return_value=1):
            for _ in range(MODULE.MAX_EVENTS):
                self.assertFalse(fixture.observe_git('restart'))
            with self.assertRaises(MODULE.VerificationError):
                fixture.observe_git('restart')
        self.assertIsNone(fixture.timing()[0])
        self.assertEqual(len(fixture.timing()[1]), MODULE.MAX_EVENTS)
        self.assertTrue(fixture.release_first_b.is_set())
        self.assertTrue(fixture.release_second_b.is_set())

    def fixture_handler(self, limited):
        class Parent:
            def check_headers(self):
                pass
            def do_GET(self):
                self.forwarded = self.path
            def do_POST(self):
                self.ai_forwarded = True
        fixture = MODULE.WallClockFixture(limited)
        def fake_start(value):
            value.server = types.SimpleNamespace(RequestHandlerClass=Parent)
            return ORIGIN
        with patch.object(MODULE.HELPERS.Fixture, 'start', fake_start):
            fixture.start()
        handler = fixture.server.RequestHandlerClass.__new__(fixture.server.RequestHandlerClass)
        handler.reply, handler.send_response, handler.send_header, handler.end_headers = Mock(), Mock(), Mock(), Mock()
        return fixture, handler

    def test_http_fixture_returns_real_65_header_and_preserves_peer_routing(self):
        fixture, handler = self.fixture_handler(True)
        handler.path = '/api/v4/projects/fixture%2Frestart/repository/commits?per_page=1'
        original = handler.path
        with patch.object(MODULE.time, 'monotonic', side_effect=[10.0, 75.0]), patch.object(MODULE.time, 'time', return_value=1000.0):
            handler.do_GET()
            self.assertEqual(handler.path, original)
            handler.send_response.assert_called_once_with(429)
            handler.send_header.assert_any_call('Retry-After', '65')
            handler.send_header.assert_any_call('Content-Length', '0')
            handler.path = '/api/v4/projects/fixture%2Fpeer/repository/commits?per_page=1'
            handler.do_GET()
        self.assertEqual(handler.forwarded, '/api/v4/projects/fixture/restart/repository/commits?per_page=1')
        self.assertEqual(fixture.observed()['git_429'], 1)
        self.assertEqual(MODULE.no_early_calls(fixture, True), [65.0])
        self.assertFalse(fixture.errors)

    def test_fixture_rejects_ai_on_git_only_origin_but_forwards_y(self):
        x, handler_x = self.fixture_handler(True)
        handler_x.do_POST()
        self.assertEqual(x.errors, ['UnexpectedAiAtGitOnlyOrigin'])
        handler_x.reply.assert_called_once()
        y, handler_y = self.fixture_handler(False)
        handler_y.do_POST()
        self.assertTrue(handler_y.ai_forwarded)
        self.assertFalse(y.errors)

    def test_pending_and_completion_require_original_identity_and_exact_durable_work(self):
        original = self.pending()
        MODULE.assert_pending(original, original, 1)
        MODULE.assert_pending(self.pending(0), original, 0)
        final = self.completed()
        MODULE.assert_complete(final, original, 2, 1)
        for location, key, value in (('request', 'requestId', 'other'), ('request', 'actor', 8),
                ('request', 'source', 'SCHEDULED'), ('wait', 'requestedAt', 'other'), ('request', 'attempts', 3),
                ('wait', 'count', 2), ('root', 'issues', 3), ('root', 'commits', [MODULE.HELPERS.SHA_B]),
                ('root', 'cursor', None), ('root', 'assignees', [8, 8]), ('root', 'runs', [])):
            altered = copy.deepcopy(final)
            (altered if location == 'root' else altered[location])[key] = value
            with self.subTest(location=location, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_complete(altered, original, 2, 1)
        for key, value in (('commits', [MODULE.HELPERS.SHA_A]), ('issues', 1), ('cursor', MODULE.HELPERS.SHA_A), ('progress', {})):
            altered = copy.deepcopy(original)
            altered[key] = value
            with self.subTest(key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_pending(altered, original, 1)

    def test_clock_comparison_is_read_only_and_checks_persisted_origin_deadline(self):
        fixture = Mock()
        fixture.timing.return_value = ((10.0, 1000.0), ())
        database = Mock()
        valid = {'now': 1010.1, 'available': 1065.05, 'first': 1000.1, 'shared': 1065.05}
        database.sql.return_value = json.dumps(valid)
        with patch.object(MODULE.time, 'time', side_effect=[1010.0, 1010.2]):
            result = MODULE.compare_clock(database, SCHEMA, 7, fixture, ORIGIN)
        self.assertEqual(result['sharedRetryAfterResponseSeconds'], 65.05)
        query = database.sql.call_args.args[0]
        self.assertTrue(query.startswith('SELECT '))
        self.assertIn("c.service='GIT'", query)
        self.assertIn('q.project_id=7', query)
        self.assertIn(MODULE.hashlib.sha256(ORIGIN.encode('ascii')).hexdigest(), query)
        for key, value in (('now', 999.0), ('shared', 1064.0), ('available', 1064.0), ('first', 900.0), ('now', True)):
            database.sql.return_value = json.dumps(valid | {key: value})
            with self.subTest(key=key), patch.object(MODULE.time, 'time', side_effect=[1010.0, 1010.2]), self.assertRaises(MODULE.VerificationError):
                MODULE.compare_clock(database, SCHEMA, 7, fixture, ORIGIN)
        database.reset_mock()
        with self.assertRaises(MODULE.VerificationError):
            MODULE.compare_clock(database, 'public', 7, fixture, ORIGIN)
        database.sql.assert_not_called()

    def test_waiter_fails_early_call_or_dead_process_before_sleep_and_success_checks_twice(self):
        fixture = Mock()
        fixture.timing.return_value = ((10.0, 1000.0), ((10.0, 'restart'), (70.0, 'peer')))
        war = Mock()
        war.process.poll.return_value = None
        with patch.object(MODULE.time, 'monotonic', return_value=20), patch.object(MODULE.time, 'sleep') as sleep, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.wait_until(lambda: True, 100, [war], fixture, 'fixture deadline')
        sleep.assert_not_called()
        fixture.timing.return_value = ((10.0, 1000.0), ((10.0, 'restart'),))
        with patch.object(MODULE.time, 'monotonic', return_value=20), patch.object(MODULE.time, 'sleep') as sleep:
            MODULE.wait_until(lambda: True, 100, [war], fixture, 'fixture deadline')
        sleep.assert_not_called()
        war.process.poll.return_value = 1
        with patch.object(MODULE.time, 'monotonic', return_value=20), self.assertRaises(MODULE.VerificationError):
            MODULE.wait_until(lambda: True, 100, [war], fixture, 'fixture deadline')

    def test_run_cleans_both_owned_wars_and_checks_original_tables(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        wars = [Mock(starts=3, process=None), Mock(starts=2, process=None)]
        before = {'app_user': '0:' + 'a' * 32}
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE.RATE, 'public_fingerprints', side_effect=[before, before]), \
                patch.object(MODULE.SHARED, 'schema_identity', side_effect=[{'oid': 1, 'owner': 'reviewer_test'}, None]), \
                patch.object(MODULE.SHARED, 'verify_schema') as verify, \
                patch.object(MODULE, 'WallClockFixture') as fixture, \
                patch.object(MODULE.HELPERS, 'OwnedWar', side_effect=wars), patch.object(MODULE, 'exercise'):
            fixture.side_effect = [Mock(start=Mock(return_value=ORIGIN)), Mock(start=Mock(return_value='http://127.0.0.1:18101'))]
            MODULE.run(self.args, report)
        for war in wars:
            war.stop.assert_called_once_with(force=True)
        self.assertEqual(verify.call_count, 2)
        self.assertFalse(report['cleanupFailed'])
        self.assertTrue(all(report['checks'][name] for name in MODULE.CLEANUP_CHECKS))
        self.assertEqual(wars[1].password, wars[0].password)
        self.assertEqual(sum('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list), 1)
        self.assertFalse(any('UPDATE ' in call.args[0] or 'DELETE ' in call.args[0] for call in database.sql.call_args_list))

    def test_live_war_prevents_schema_and_work_deletion_but_other_war_is_stopped(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        alive, stopped = Mock(starts=1), Mock(starts=1, process=None)
        alive.process.poll.return_value = None
        alive.stop.side_effect = OSError('fixture-private-error')
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE.RATE, 'public_fingerprints', return_value={}), \
                patch.object(MODULE.SHARED, 'schema_identity', return_value={'oid': 1, 'owner': 'reviewer_test'}), \
                patch.object(MODULE.SHARED, 'verify_schema'), patch.object(MODULE, 'WallClockFixture') as fixture, \
                patch.object(MODULE.HELPERS, 'OwnedWar', side_effect=[alive, stopped]), patch.object(MODULE, 'exercise'), \
                self.assertRaises(MODULE.VerificationError):
            fixture.side_effect = [Mock(start=Mock(return_value=ORIGIN)), Mock(start=Mock(return_value='http://127.0.0.1:18101'))]
            MODULE.run(self.args, report)
        stopped.stop.assert_called_once_with(force=True)
        self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
        self.assertFalse(report['checks']['ownedWarsStopped'])
        self.assertFalse(report['checks']['ownedSchemaRemoved'])
        self.assertFalse(report['checks']['ownedWorkRemoved'])

    def test_unknown_schema_identity_is_never_guessed_for_drop(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE.RATE, 'public_fingerprints', return_value={}), \
                patch.object(MODULE.SHARED, 'schema_identity', return_value=None), \
                patch.object(MODULE.SHARED, 'verify_schema', side_effect=MODULE.VerificationError('not owned')), \
                patch.object(MODULE, 'WallClockFixture') as fixture, self.assertRaises(MODULE.VerificationError):
            MODULE.run(self.args, report)
        fixture.assert_not_called()
        self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
        self.assertTrue(report['cleanupFailed'])

    def test_changed_schema_identity_blocks_drop_after_both_wars_stop(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        wars = [Mock(starts=1, process=None), Mock(starts=1, process=None)]
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE.RATE, 'public_fingerprints', return_value={}), \
                patch.object(MODULE.SHARED, 'schema_identity', return_value={'oid': 1, 'owner': 'reviewer_test'}), \
                patch.object(MODULE.SHARED, 'verify_schema', side_effect=[None, MODULE.VerificationError('changed')]), \
                patch.object(MODULE, 'WallClockFixture') as fixture, \
                patch.object(MODULE.HELPERS, 'OwnedWar', side_effect=wars), patch.object(MODULE, 'exercise'), \
                self.assertRaises(MODULE.VerificationError):
            fixture.side_effect = [Mock(start=Mock(return_value=ORIGIN)), Mock(start=Mock(return_value='http://127.0.0.1:18101'))]
            MODULE.run(self.args, report)
        self.assertTrue(report['checks']['ownedWarsStopped'])
        self.assertFalse(report['checks']['ownedSchemaRemoved'])
        self.assertFalse(report['checks']['ownedWorkRemoved'])
        self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
        for war in wars:
            war.stop.assert_called_once_with(force=True)

    def test_main_redacts_errors_restores_sigterm_and_requires_every_cleanup_check(self):
        for mode in ('private', 'interrupt', *MODULE.CLEANUP_CHECKS, 'success'):
            installed = []
            prior = Mock()
            def scenario(args, report):
                if mode == 'private':
                    raise RuntimeError('fixture-private-token-and-response')
                if mode == 'interrupt':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                report['cleanupFailed'] = False
                report['checks'].update({key: mode != key for key in MODULE.CLEANUP_CHECKS})
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'interrupt' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertFalse(report['sqlTimeAcceleration'])
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.assertEqual(report['retryAfterSeconds'], 65)
            self.assertNotIn('fixture-private-token-and-response', str(report) + output.getvalue())
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()


if __name__ == '__main__':
    unittest.main()
