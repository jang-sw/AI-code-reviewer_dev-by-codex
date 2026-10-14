"""Safety, timing and evidence tests; no real processes, sockets or idle wait."""
import copy
from contextlib import ExitStack, contextmanager, redirect_stdout, redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location('verify_session_expiry',
    Path(__file__).absolute().parents[1] / 'verify-session-expiry.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
BASE = 'http://127.0.0.1:18104/' + SCHEMA


class SessionExpiryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='session-expiry-fixture-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port=18104,
            timeout_seconds=2100, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items()
                for part in ('--' + key.replace('_', '-'), str(value))]

    @staticmethod
    def good_report():
        return {'checks': dict.fromkeys(MODULE.CLEANUP + MODULE.BEHAVIOR, True), 'cleanupFailed': False,
                'sessionTimeoutSeconds': 1800, 'idleSecondsObserved': 1810.05, 'controlProbeAfterSeconds': 1740.01,
                'victimRequestsDuringIdle': 0, 'idleStateObservations': 31, 'warStarts': 1,
                'successfulStatusAudits': 1, 'assignedIssueCount': 2}

    def test_arguments_require_fresh_owned_report_and_real_wait_budget(self):
        with patch.object(MODULE.LINUX, 'unused_port'):
            actual = MODULE.arguments(self.argv())
        self.assertEqual(actual.timeout_seconds, 2100)
        self.assertEqual(actual.port, 18104)
        for change in ({'timeout_seconds': 1949}, {'timeout_seconds': 3601}, {'port': 1023},
                       {'startup_timeout_seconds': 121}, {'report': self.workspace / 'outside.json'}):
            with self.subTest(change=change), patch.object(MODULE.LINUX, 'unused_port'), \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**change))
        self.args.report.write_text('retain existing evidence', encoding='utf-8')
        with patch.object(MODULE.LINUX, 'unused_port'), self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        self.assertEqual(self.args.report.read_text(), 'retain existing evidence')

    def make_war(self, text, duplicate=False, location='WEB-INF/classes/application.properties'):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.args.war, 'w') as archive:
                archive.writestr(location, text)
                if duplicate:
                    archive.writestr(location, text)

    def test_packaged_timeout_requires_unique_bounded_actual_war_properties(self):
        self.make_war('name=fixture\nserver.servlet.session.timeout=30m\n')
        MODULE.verify_packaged_timeout(self.args.war)
        for text, duplicate, location in (
                ('server.servlet.session.timeout=1s', False, 'WEB-INF/classes/application.properties'),
                ('server.servlet.session.timeout=30m\nserver.servlet.session.timeout=1s', False, 'WEB-INF/classes/application.properties'),
                ('server.servlet.session.timeout=30m', True, 'WEB-INF/classes/application.properties'),
                ('server.servlet.session.timeout=30m', False, 'BOOT-INF/classes/application.properties'),
                ('server.servlet.session.timeout=30m\n' + '#' * 65536, False, 'WEB-INF/classes/application.properties')):
            with self.subTest(text=text[:45], duplicate=duplicate, location=location):
                self.make_war(text, duplicate, location)
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.verify_packaged_timeout(self.args.war)

    def test_owned_war_does_not_inherit_session_timeout_overrides(self):
        work, logs = self.workspace / 'work', self.workspace / 'logs'
        work.mkdir()
        logs.mkdir()
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration',
                                         user='reviewer_test', password='synthetic-only', sql=Mock())
        war = MODULE.HELPERS.OwnedWar(self.args, database, SCHEMA, 'http://127.0.0.1:12345', work, logs, 99999999999)
        with patch.dict(MODULE.os.environ, {'SERVER_SERVLET_SESSION_TIMEOUT': '1s',
                'SPRING_APPLICATION_JSON': '{"server":{"servlet":{"session":{"timeout":"1s"}}}}',
                'JAVA_TOOL_OPTIONS': '-Dserver.servlet.session.timeout=1s'}), \
                patch.object(MODULE.HELPERS.subprocess, 'Popen') as popen, patch.object(MODULE.HELPERS, 'bounded_wait'):
            war.start(True)
        command = popen.call_args.args[0]
        environment = popen.call_args.kwargs['env']
        self.assertEqual(command[1], '-Xmx384m')
        self.assertFalse(any('session.timeout' in str(value) for value in command))
        self.assertNotIn('SERVER_SERVLET_SESSION_TIMEOUT', environment)
        self.assertNotIn('SPRING_APPLICATION_JSON', environment)
        self.assertNotIn('JAVA_TOOL_OPTIONS', environment)
        self.assertIn('--spring.config.location=classpath:/application.properties', command)
        war.log_stream.close()

    def test_idle_guard_rejects_before_any_http_attempt(self):
        browser = MODULE.Browser(BASE, 99999999999)
        browser.opener = Mock()
        browser.idle = True
        with self.assertRaises(MODULE.VerificationError):
            browser.request('/issues/1')
        self.assertEqual(browser.requests, 0)
        self.assertEqual(browser.idle_attempts, 1)
        browser.opener.open.assert_not_called()

    def test_http_errors_preserve_status_headers_body_without_following_redirects(self):
        browser = MODULE.Browser(BASE, 99999999999)
        error = MODULE.urllib.error.HTTPError(BASE + '/issues/1/status', 403, 'fixed',
                                             {'Cache-Control': 'no-store'}, io.BytesIO(b'fixed body'))
        browser.opener = Mock()
        browser.opener.open.side_effect = error
        response = browser.request('/issues/1/status', {'_csrf': 'synthetic'})
        self.assertEqual((response.status, response.headers['Cache-Control'], response.body), (403, 'no-store', 'fixed body'))
        self.assertEqual(browser.requests, 1)
        self.assertIsNone(MODULE.NoRedirects().redirect_request(None, None, None, None, None, 'https://invalid.example'))

    def test_http_bounds_size_and_withholds_transport_details(self):
        browser = MODULE.Browser(BASE, 99999999999)
        browser.opener = Mock()
        browser.opener.open.side_effect = MODULE.urllib.error.URLError('password=private-placeholder')
        with self.assertRaises(MODULE.VerificationError) as raised:
            browser.request('/issues')
        self.assertNotIn('private-placeholder', str(raised.exception))
        for path in ('//remote', '/\\remote'):
            with self.assertRaises(MODULE.VerificationError):
                browser.request(path)
        response = Mock(status=200, headers={})
        response.read.return_value = b'x' * 2_000_001
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        browser.opener.open.side_effect = None
        browser.opener.open.return_value = response
        with self.assertRaises(MODULE.VerificationError):
            browser.request('/issues')
        response.read.assert_called_once_with(2_000_001)

    def test_redirect_rejects_remote_wrong_status_query_or_context(self):
        good = MODULE.Response(302, '/' + SCHEMA + '/login', {}, '')
        MODULE.redirect(good, BASE, '/login')
        for response in (good._replace(location='https://invalid.example/' + SCHEMA + '/login'),
                         good._replace(status=200), good._replace(location='/login'),
                         good._replace(location='/' + SCHEMA + '/login?return=private')):
            with self.subTest(response=response), self.assertRaises(MODULE.VerificationError):
                MODULE.redirect(response, BASE, '/login')

    @staticmethod
    def denial():
        return MODULE.Response(403, '', {'Cache-Control': 'no-store'},
            '양식을 다시 열어 주세요 제출 내용을 처리하지 않았습니다 '
            '<a href="/' + SCHEMA + '/issues">목록</a><a href="/' + SCHEMA + '/login">로그인</a>')

    def test_csrf_recovery_requires_safe_links_status_no_store_and_no_input_reflection(self):
        good = self.denial()
        MODULE.csrf_denial(good, BASE, ('private-input',))
        for response in (good._replace(status=302), good._replace(headers={}),
                         good._replace(body=good.body.replace('/issues', '/elsewhere')),
                         good._replace(body=good.body + ' private-input'), good._replace(body=good.body + ' SQLState')):
            with self.subTest(response=response), self.assertRaises(MODULE.VerificationError):
                MODULE.csrf_denial(response, BASE, ('private-input',))

    @contextmanager
    def idle_fixture(self, control_failure=False, mutate=False, guard_touch=False, process_dies=False):
        state = types.SimpleNamespace(now=0.0)
        victims = [MODULE.Browser(BASE, 3000), MODULE.Browser(BASE, 3000)]
        control = Mock()
        issue = {'id': 1, 'title': 'Synthetic finding A.java'}
        def get(_path):
            self.assertGreaterEqual(state.now, 1740)
            self.assertLess(state.now, 1800)
            if control_failure:
                raise MODULE.VerificationError('The control session was no longer authenticated')
            return issue['title'] + ' 처리 결과 기록'
        control.get.side_effect = get
        war = types.SimpleNamespace(base=BASE, process=Mock())
        war.process.poll.return_value = 1 if process_dies else None
        report = {'checks': {}}
        baseline = {'business': 'unchanged'}
        def sleep(seconds):
            state.now += seconds
            if guard_touch and state.now >= 100 and not victims[0].idle_attempts:
                with self.assertRaises(MODULE.VerificationError):
                    victims[0].request('/issues/1')
        with ExitStack() as stack:
            stack.enter_context(patch.object(MODULE.time, 'monotonic', side_effect=lambda: state.now))
            stack.enter_context(patch.object(MODULE.time, 'sleep', side_effect=sleep))
            stack.enter_context(patch.object(MODULE.NETWORK, 'probe'))
            stack.enter_context(patch.object(MODULE.NETWORK, 'server_identity', return_value='same'))
            stack.enter_context(patch.object(MODULE, 'business_fingerprints', return_value={} if mutate else baseline))
            stack.enter_context(redirect_stdout(io.StringIO()))
            yield types.SimpleNamespace(victims=victims, control=control, war=war, issue=issue, report=report,
                call=lambda: MODULE.observe_idle(victims, control, war, None, SCHEMA, baseline, 'same', issue, report, 2100))

    def test_idle_wait_observes_preexpiry_control_and_two_untouched_victims(self):
        with self.idle_fixture() as fixture:
            fixture.call()
        self.assertEqual(fixture.report['idleSecondsObserved'], 1810)
        self.assertEqual(fixture.report['controlProbeAfterSeconds'], 1740)
        self.assertEqual(fixture.report['victimRequestsDuringIdle'], 0)
        self.assertGreaterEqual(fixture.report['idleStateObservations'], 30)
        fixture.control.get.assert_called_once_with('/issues/1')
        self.assertTrue(all(not browser.idle for browser in fixture.victims))

    def test_idle_observation_fails_if_control_expires_data_changes_victim_touched_or_war_dies(self):
        for settings in ({'control_failure': True}, {'mutate': True}, {'guard_touch': True}, {'process_dies': True}):
            with self.subTest(settings=settings), self.idle_fixture(**settings) as fixture:
                with self.assertRaises(MODULE.VerificationError):
                    fixture.call()
                self.assertTrue(all(not browser.idle for browser in fixture.victims))

    def test_final_issue_rows_preserve_identity_owner_content_and_unrelated_issue(self):
        before = [{'id': 1, 'status': 'OPEN', 'updated_at': 'old', 'assignee_id': 4, 'description': 'same'},
                  {'id': 2, 'status': 'OPEN', 'updated_at': 'old', 'assignee_id': 4, 'description': 'same'}]
        after = copy.deepcopy(before)
        after[0].update(status='RESOLVED', updated_at='new')
        MODULE.assert_final_rows(before, after, 1)
        for row, key, value in ((0, 'id', 9), (0, 'assignee_id', 5), (0, 'description', 'changed'),
                                (0, 'status', 'OPEN'), (0, 'updated_at', 'old'), (1, 'status', 'RESOLVED')):
            changed = copy.deepcopy(after)
            changed[row][key] = value
            with self.subTest(row=row, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_final_rows(before, changed, 1)

    def test_evidence_gate_rejects_shortcuts_truthy_counters_and_incomplete_cleanup(self):
        good = self.good_report()
        MODULE.evidence_gate(good)
        for key, value in (('idleSecondsObserved', 1809.999), ('idleSecondsObserved', float('nan')),
                           ('idleSecondsObserved', float('inf')), ('controlProbeAfterSeconds', 1800),
                           ('controlProbeAfterSeconds', 1739), ('victimRequestsDuringIdle', True),
                           ('victimRequestsDuringIdle', 1), ('victimRequestsDuringIdle', [0, 0]),
                           ('sessionTimeoutSeconds', 1), ('idleStateObservations', 1), ('warStarts', True),
                           ('successfulStatusAudits', True), ('assignedIssueCount', 2.0)):
            bad = copy.deepcopy(good)
            bad[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(MODULE.VerificationError):
                MODULE.evidence_gate(bad)
        for key in MODULE.CLEANUP + MODULE.BEHAVIOR:
            bad = copy.deepcopy(good)
            bad['checks'][key] = False
            with self.subTest(check=key), self.assertRaises(MODULE.VerificationError):
                MODULE.evidence_gate(bad)

    @contextmanager
    def cleanup_fixture(self, failure=None, identity_changed=False, sessions_remain=False):
        database = Mock(user='reviewer_test', url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration')
        dropped = []
        def sql(text):
            if text == 'SELECT current_database();':
                return 'reviewer_integration'
            if text.startswith('DROP SCHEMA '):
                dropped.append(text)
            return ''
        database.sql.side_effect = sql
        fixture = Mock()
        fixture.thread = None
        fixture.start.return_value = 'http://127.0.0.1:12345'
        war = Mock(process=None, starts=1)
        report = {'checks': {}}
        def identity(_db, _schema):
            return None if dropped else {'oid': 42, 'owner': 'reviewer_test', 'marker': None}
        with ExitStack() as stack:
            stack.enter_context(patch.object(MODULE, 'verify_packaged_timeout'))
            stack.enter_context(patch.object(MODULE.HELPERS, 'Database', return_value=database))
            stack.enter_context(patch.object(MODULE.HELPERS, 'Fixture', return_value=fixture))
            stack.enter_context(patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war))
            stack.enter_context(patch.object(MODULE.RATE, 'public_fingerprints', return_value={'fixed': 'unchanged'}))
            stack.enter_context(patch.object(MODULE.NETWORK, 'server_identity', return_value='same'))
            stack.enter_context(patch.object(MODULE.NETWORK, 'owned_sessions', return_value=1 if sessions_remain else 0))
            stack.enter_context(patch.object(MODULE.HELPERS, 'bounded_wait',
                side_effect=lambda check, *_: MODULE.require(check(), 'Synthetic sessions remain')))
            stack.enter_context(patch.object(MODULE.SHARED, 'schema_identity', side_effect=identity))
            verify = stack.enter_context(patch.object(MODULE.SHARED, 'verify_schema'))
            if identity_changed:
                verify.side_effect = [None, MODULE.VerificationError('Synthetic schema identity changed')]
            stack.enter_context(patch.object(MODULE, 'exercise', side_effect=failure))
            yield types.SimpleNamespace(report=report, dropped=dropped, database=database, war=war, fixture=fixture,
                call=lambda: MODULE.run(self.args, report))

    def test_cleanup_stops_owned_resources_checks_sessions_then_removes_verified_schema(self):
        with self.cleanup_fixture() as fixture:
            fixture.call()
        self.assertEqual(len(fixture.dropped), 1)
        self.assertFalse(fixture.report['cleanupFailed'])
        for key in MODULE.CLEANUP:
            self.assertTrue(fixture.report['checks'][key])
        fixture.war.stop.assert_called_once_with(force=True)
        fixture.fixture.close.assert_called_once()

    def test_cleanup_withholds_schema_and_work_removal_if_identity_or_sessions_are_uncertain(self):
        for settings in ({'identity_changed': True}, {'sessions_remain': True}):
            with self.subTest(settings=settings), self.cleanup_fixture(**settings) as fixture:
                with self.assertRaises(MODULE.VerificationError):
                    fixture.call()
                self.assertFalse(fixture.dropped)
                self.assertTrue(fixture.report['cleanupFailed'])
                self.assertFalse(fixture.report['checks']['ownedSchemaRemoved'])
                self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])

    def test_cancellation_preserves_keyboardinterrupt_after_complete_cleanup(self):
        with self.cleanup_fixture(failure=KeyboardInterrupt()) as fixture:
            with self.assertRaises(KeyboardInterrupt):
                fixture.call()
        self.assertEqual(len(fixture.dropped), 1)
        self.assertFalse(fixture.report['cleanupFailed'])

    def test_main_requires_complete_evidence_restores_signal_and_hides_private_errors(self):
        prior, output = object(), io.StringIO()
        for mode in ('success', 'missing', 'sigterm', 'private'):
            installed = []
            def scenario(_args, report):
                report.update(self.good_report())
                if mode == 'missing':
                    report['checks']['freshFormSavedExactlyOnce'] = False
                if mode == 'sigterm':
                    installed[0][1](None, None)
                if mode == 'private':
                    raise RuntimeError('password=private-placeholder')
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *values: installed.append(values)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'sigterm' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.assertNotIn('private-placeholder', str(report) + output.getvalue())
            self.args.report.unlink()

    def test_main_never_overwrites_concurrently_created_report(self):
        def scenario(_args, report):
            report.update(self.good_report())
            self.args.report.write_text('retain concurrent evidence', encoding='utf-8')
        with patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'run', side_effect=scenario), \
                patch.object(MODULE.signal, 'getsignal'), patch.object(MODULE.signal, 'signal'), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(MODULE.main([]), 1)
        self.assertEqual(self.args.report.read_text(), 'retain concurrent evidence')


if __name__ == '__main__':
    unittest.main()
