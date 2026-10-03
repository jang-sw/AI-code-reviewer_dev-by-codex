"""Shared-auth drill safety boundaries; no PostgreSQL, WAR, socket or child starts."""
from contextlib import ExitStack, redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_shared_auth',
    Path(__file__).absolute().parents[1] / 'verify-shared-auth.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
IDENTITY = {'oid': 42, 'owner': 'reviewer_test', 'marker': 'shared-auth-' + 'b' * 32}
ENV = {'TEST_DATABASE_URL': 'jdbc:postgresql://127.0.0.1:55439/reviewer_integration',
       'TEST_DATABASE_USERNAME': 'reviewer_test', 'TEST_DATABASE_PASSWORD': ''}


class OwnedDatabaseFixture:
    """Only lifecycle SQL is supported; scenario SQL is deliberately not imitated."""
    user = 'reviewer_test'

    def __init__(self, events):
        self.events = events
        self.identity = None
        self.queries = []

    def sql(self, query):
        self.queries.append(query)
        if query == 'SELECT current_database();':
            return 'reviewer_integration'
        if query.startswith('CREATE SCHEMA '):
            self.schema = query.removeprefix('CREATE SCHEMA ').removesuffix(';')
            if not re.fullmatch(r'restart_test_[a-f0-9]{32}', self.schema):
                raise AssertionError('Lifecycle fixture was given an unowned schema')
            self.identity = dict(IDENTITY, marker=None)
        elif query.startswith('COMMENT ON SCHEMA ' + self.schema + ' IS '):
            self.identity['marker'] = query.split("'")[1]
        elif query.startswith("SELECT json_build_object('oid',n.oid::bigint,"):
            return json.dumps(self.identity) if self.identity is not None else ''
        elif query == 'DROP SCHEMA ' + self.schema + ' CASCADE;':
            self.events.append('drop')
            self.identity = None
        else:
            raise AssertionError('Unexpected lifecycle SQL')
        return ''


class OwnedWarFixture:
    def __init__(self, args, database, schema, fixture_url, work, logs, deadline, *, events, index):
        self.args, self.database, self.schema = args, database, schema
        self.fixture_url, self.work, self.logs, self.deadline = fixture_url, work, logs, deadline
        self.events, self.index = events, index
        self.password = 'synthetic-private-password-' + str(index)
        self.username = 'synthetic-account'
        self.process = None
        self.starts = 0
        self.workers = []
        self.stop_failure = None

    def start(self, worker):
        self.starts += 1
        self.workers.append(worker)
        self.process = Mock()
        self.process.poll.return_value = None

    def stop(self, force=False):
        if force is not True:
            raise AssertionError('Fixture cleanup should stop its owned WAR')
        self.events.append('stop-' + str(self.index))
        if self.stop_failure is not None:
            raise self.stop_failure
        self.process = None


class SharedAuthDrillSafetyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='shared-auth-safety-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for filename in ('application.war', 'java', 'psql'):
            (self.workspace / filename).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local' / 'report.json', port_a=18094,
            port_b=18095, timeout_seconds=300, startup_timeout_seconds=45)
        for context in (patch.object(MODULE, 'WORKSPACE', self.workspace), patch.dict(os.environ, ENV, clear=True),
                        patch.object(MODULE.HELPERS.subprocess, 'run', side_effect=AssertionError('No child allowed')),
                        patch.object(MODULE.HELPERS.subprocess, 'Popen', side_effect=AssertionError('No WAR allowed'))):
            context.start()
            self.addCleanup(context.stop)
        socket_patch = patch.object(MODULE.socket, 'socket')
        self.socket = socket_patch.start()
        self.addCleanup(socket_patch.stop)

    def argv(self, **changes):
        return [value for key, item in (vars(self.args) | changes).items()
                for value in ('--' + key.replace('_', '-'), str(item))]

    def lifecycle(self, exercise):
        events, wars = [], []
        database = OwnedDatabaseFixture(events)

        def war(*args):
            result = OwnedWarFixture(*args, events=events, index=len(wars))
            wars.append(result)
            return result

        stack = ExitStack()
        stack.enter_context(patch.object(MODULE.HELPERS, 'Database', return_value=database))
        stack.enter_context(patch.object(MODULE.HELPERS, 'OwnedWar', side_effect=war))
        stack.enter_context(patch.object(MODULE, 'exercise', side_effect=exercise))
        self.addCleanup(stack.close)
        return database, wars, events

    @staticmethod
    def report():
        return {'result': 'FAIL', 'checks': {}}

    def test_existing_and_outside_reports_are_rejected_before_port_probes(self):
        self.args.report.write_text('preserve original evidence', encoding='utf-8')
        for path in (self.args.report, self.workspace / 'outside.json', self.workspace / '.local-other' / 'report.json'):
            with self.subTest(path=path), self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(report=path))
        self.socket.assert_not_called()
        self.assertEqual('preserve original evidence', self.args.report.read_text(encoding='utf-8'))

    def test_report_traversal_and_parent_reparse_are_rejected_before_port_probes(self):
        for path in (Path('relative.json'), self.workspace / '.local' / '..' / 'outside.json'):
            with self.subTest(path=path), self.assertRaises(MODULE.LINUX.SafetyError):
                MODULE.arguments(self.argv(report=path))
        original = Path.lstat
        def replaced(path):
            if path == self.workspace / '.local':
                return types.SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_file_attributes=0x400)
            return original(path)
        with patch.object(Path, 'lstat', replaced), self.assertRaises(MODULE.LINUX.SafetyError):
            MODULE.arguments(self.argv())
        self.socket.assert_not_called()

    def test_invalid_ports_and_unbounded_timeouts_are_rejected_before_port_probes(self):
        for changes in ({'port_b': self.args.port_a}, {'port_a': 1023}, {'port_b': 65536},
                        {'timeout_seconds': 119}, {'timeout_seconds': 601},
                        {'startup_timeout_seconds': 14}, {'startup_timeout_seconds': 121}):
            with self.subTest(changes=changes), self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(**changes))
        self.socket.assert_not_called()

    def test_port_preflight_binds_only_loopback_and_refuses_occupied_port(self):
        MODULE.arguments(self.argv())
        binds = self.socket.return_value.__enter__.return_value.bind
        self.assertEqual([call.args[0] for call in binds.call_args_list],
                         [('127.0.0.1', 18094), ('127.0.0.1', 18095)])
        binds.side_effect = OSError('synthetic private socket detail')
        with self.assertRaises(MODULE.VerificationError) as failure:
            MODULE.arguments(self.argv())
        self.assertNotIn('private socket', str(failure.exception))

    def test_database_rejects_remote_production_and_parameterized_urls_before_any_command(self):
        for url in ('jdbc:postgresql://example.invalid:55439/reviewer_integration',
                    'jdbc:postgresql://127.0.0.1:55439/production',
                    ENV['TEST_DATABASE_URL'] + '?currentSchema=public'):
            with self.subTest(url=url), self.assertRaises(MODULE.VerificationError):
                MODULE.HELPERS.Database(self.args.psql, url, self.workspace)
        MODULE.HELPERS.subprocess.run.assert_not_called()

    def test_probe_identifiers_are_validated_without_issuing_sql(self):
        database = Mock()
        for action in (lambda: MODULE.schema_identity(database, 'public'),
                       lambda: MODULE.bucket_count(database, SCHEMA, 'LOGIN', 'raw-private-username'),
                       lambda: MODULE.bucket_count(database, SCHEMA, 'OTHER', MODULE.ADDRESS_KEY),
                       lambda: MODULE.quota_fingerprint(database, SCHEMA, 'OTHER'),
                       lambda: MODULE.table_oid(database, SCHEMA, 'app_user')):
            with self.assertRaises(MODULE.VerificationError):
                action()
        database.sql.assert_not_called()

    def test_changed_schema_identity_prevents_expiration_and_fault_restoration_mutations(self):
        for current in (None, dict(IDENTITY, oid=43), dict(IDENTITY, owner='foreign_owner'),
                        dict(IDENTITY, marker='foreign-marker')):
            for action in (lambda db: MODULE.expire(db, SCHEMA, IDENTITY, 'LOGIN'),
                           lambda db: MODULE.restore_bucket(db, SCHEMA, IDENTITY, '71')):
                database = Mock()
                database.sql.return_value = json.dumps(current) if current is not None else ''
                with self.subTest(current=current), self.assertRaises(MODULE.VerificationError):
                    action(database)
                self.assertTrue(all(call.args[0].startswith('SELECT ') for call in database.sql.call_args_list))

    def test_fault_restore_requires_original_table_oid_and_never_renames_a_replacement(self):
        for normal, hidden in (('72', ''), ('', '72'), ('71', '72'), ('', '')):
            database = Mock()
            with self.subTest(normal=normal, hidden=hidden), \
                    patch.object(MODULE, 'schema_identity', return_value=IDENTITY), \
                    patch.object(MODULE, 'table_oid', side_effect=[normal, hidden]), \
                    self.assertRaises(MODULE.VerificationError):
                MODULE.restore_bucket(database, SCHEMA, IDENTITY, '71')
            database.sql.assert_not_called()
        database = Mock()
        with patch.object(MODULE, 'schema_identity', return_value=IDENTITY), \
                patch.object(MODULE, 'table_oid', side_effect=['', '71', '71', '']):
            MODULE.restore_bucket(database, SCHEMA, IDENTITY, '71')
        database.sql.assert_called_once_with(f'ALTER TABLE {SCHEMA}.{MODULE.FAULT_BUCKET} RENAME TO {MODULE.BUCKET};')

    def test_503_requires_fixed_body_retry_and_no_store_and_rejects_private_disclosure(self):
        valid_headers = {'Retry-After': '30', 'Cache-Control': 'no-store'}
        for path in ('/login', '/signup'):
            MODULE.denial(path, valid_headers, MODULE.UNAVAILABLE[path], 503, 'private-user', 'private-password')
            for headers, body in (({'Retry-After': '30'}, MODULE.UNAVAILABLE[path]),
                                  ({'Retry-After': '0', 'Cache-Control': 'no-store'}, MODULE.UNAVAILABLE[path]),
                                  ({'Retry-After': '31', 'Cache-Control': 'no-store'}, MODULE.UNAVAILABLE[path]),
                                  (valid_headers, 'private-user'), (valid_headers, 'private-password'),
                                  (valid_headers, 'SELECT * FROM auth_attempt_bucket'),
                                  (valid_headers, 'SQLState 42P01 jdbc:postgresql://private'),
                                  (valid_headers, 'unexpected error body')):
                with self.subTest(path=path, headers=headers, body=body), self.assertRaises(MODULE.VerificationError):
                    MODULE.denial(path, headers, body, 503, 'private-user', 'private-password')

    def test_form_requires_cookie_before_posting_and_preserves_csrf_token(self):
        page = '<input type="hidden" name="_csrf" value="synthetic-token">'
        browser = MODULE.ProbeBrowser('http://127.0.0.1:18094/' + SCHEMA)
        war = types.SimpleNamespace(base=browser.base)
        request = Mock(return_value=(200, browser.base + '/login', {}, page))
        with patch.object(MODULE, 'ProbeBrowser', return_value=browser), \
                patch.object(browser, 'request_checked', request), self.assertRaises(MODULE.VerificationError):
            MODULE.post_form(war, '/login', {'username': 'synthetic-user'}, 100)
        request.assert_called_once_with('/login', 100)
        handler = next(handler for handler in browser.opener.handlers
                       if isinstance(handler, MODULE.urllib.request.HTTPCookieProcessor))
        handler.cookiejar = [types.SimpleNamespace(name='JSESSIONID')]
        request.reset_mock()
        with patch.object(MODULE, 'ProbeBrowser', return_value=browser), patch.object(browser, 'request_checked', request):
            MODULE.post_form(war, '/login', {'username': 'synthetic-user'}, 100)
        self.assertEqual(request.call_args.args,
                         ('/login', 100, {'_csrf': 'synthetic-token', 'username': 'synthetic-user'}))

    def test_expired_http_deadline_prevents_any_request(self):
        browser = MODULE.ProbeBrowser('http://127.0.0.1:18094/' + SCHEMA)
        with patch.object(MODULE.time, 'monotonic', return_value=100), patch.object(browser.opener, 'open') as request, \
                self.assertRaises(MODULE.VerificationError):
            browser.request_checked('/login', 100, {'password': 'synthetic-private-password'})
        request.assert_not_called()

    def test_cleanup_stops_both_wars_before_drop_and_uses_one_schema_separate_work_directories(self):
        database, wars, events = self.lifecycle(lambda *unused: None)
        report = self.report()
        MODULE.run(self.args, report)
        self.assertEqual(events, ['stop-0', 'stop-1', 'drop'])
        self.assertEqual(len(wars), 2)
        self.assertEqual(wars[0].schema, wars[1].schema)
        self.assertEqual(wars[0].password, wars[1].password)
        self.assertEqual([war.workers for war in wars], [[False], [False]])
        self.assertEqual([war.fixture_url for war in wars], ['http://127.0.0.1:9'] * 2)
        self.assertNotEqual(wars[0].logs, wars[1].logs)
        self.assertTrue(all(war.logs.is_dir() and not war.work.exists() for war in wars))
        self.assertIsNone(database.identity)
        self.assertFalse(report['cleanupFailed'])
        self.assertTrue(report['checks']['ownedWarsStopped'])
        self.assertTrue(report['checks']['ownedSchemaRemoved'])

    def test_unstoppable_war_preserves_schema_work_and_original_failure_but_still_stops_other(self):
        for failed_index in (0, 1):
            original = MODULE.VerificationError('Synthetic scenario failed')
            def exercise(wars, *_unused):
                wars[failed_index].stop_failure = RuntimeError('synthetic private stop error')
                raise original
            database, wars, events = self.lifecycle(exercise)
            report = self.report()
            with self.subTest(failed_index=failed_index), self.assertRaises(MODULE.VerificationError) as failure:
                MODULE.run(self.args, report)
            self.assertIs(failure.exception, original)
            self.assertEqual(events, ['stop-0', 'stop-1'])
            self.assertIsNotNone(database.identity)
            self.assertTrue(all(war.work.is_dir() for war in wars))
            self.assertTrue(report['cleanupFailed'])
            self.assertFalse(report['checks']['ownedWarsStopped'])
            self.assertFalse(report['checks']['ownedSchemaRemoved'])

    def test_identity_change_after_scenario_stops_wars_but_preserves_schema_and_work(self):
        def exercise(_wars, database, *_unused):
            database.identity['oid'] += 1
        database, wars, events = self.lifecycle(exercise)
        report = self.report()
        with self.assertRaises(MODULE.VerificationError):
            MODULE.run(self.args, report)
        self.assertEqual(events, ['stop-0', 'stop-1'])
        self.assertFalse(any(query.startswith('DROP ') for query in database.queries))
        self.assertTrue(all(war.work.is_dir() for war in wars))
        self.assertTrue(report['checks']['ownedWarsStopped'])
        self.assertFalse(report['checks']['ownedSchemaRemoved'])

    def test_failed_fault_restoration_withholds_drop_without_replacing_first_error(self):
        original = MODULE.VerificationError('Synthetic scenario failed')
        def exercise(_wars, _database, _schema, _identity, report, _deadline):
            report['faultTableOid'] = 71
            raise original
        database, wars, events = self.lifecycle(exercise)
        report = self.report()
        with patch.object(MODULE, 'restore_bucket', side_effect=MODULE.VerificationError('Synthetic identity changed')), \
                self.assertRaises(MODULE.VerificationError) as failure:
            MODULE.run(self.args, report)
        self.assertIs(failure.exception, original)
        self.assertEqual(events, ['stop-0', 'stop-1'])
        self.assertFalse(any(query.startswith('DROP ') for query in database.queries))
        self.assertTrue(all(war.work.is_dir() for war in wars))
        self.assertTrue(report['cleanupFailed'])
        self.assertNotIn('faultTableOid', report)

    def test_sigterm_records_failure_cleans_owned_resources_and_restores_previous_handler(self):
        installed, prior = [], Mock()
        def exercise(*unused):
            installed[0][1](MODULE.signal.SIGTERM, None)
        _database, _wars, events = self.lifecycle(exercise)
        with patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                patch.object(MODULE.signal, 'signal', side_effect=lambda *item: installed.append(item)), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            code = MODULE.main([])
        report = json.loads(self.args.report.read_text(encoding='utf-8'))
        self.assertEqual(code, 130)
        self.assertEqual(report['result'], 'FAIL')
        self.assertEqual(report['failureType'], 'KeyboardInterrupt')
        self.assertEqual(events, ['stop-0', 'stop-1', 'drop'])
        self.assertTrue(report['checks']['ownedWarsStopped'])
        self.assertTrue(report['checks']['ownedSchemaRemoved'])
        self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))

    def test_report_is_private_exclusive_and_unknown_errors_do_not_disclose_inputs(self):
        private = 'synthetic-private-password-and-database-detail'
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE, 'run', side_effect=RuntimeError(private)), \
                patch.object(MODULE.signal, 'getsignal'), patch.object(MODULE.signal, 'signal'), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(MODULE.main([]), 1)
        evidence = self.args.report.read_text(encoding='utf-8')
        self.assertNotIn(private, evidence + stdout.getvalue() + stderr.getvalue())
        self.assertEqual(json.loads(evidence)['result'], 'FAIL')
        if os.name != 'nt':
            self.assertEqual(stat.S_IMODE(self.args.report.stat().st_mode), 0o600)
        with patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'run'), \
                patch.object(MODULE.signal, 'getsignal'), patch.object(MODULE.signal, 'signal'), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(MODULE.main([]), 1)
        self.assertEqual(evidence, self.args.report.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
