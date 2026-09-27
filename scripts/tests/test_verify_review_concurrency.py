import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request


SPEC = importlib.util.spec_from_file_location('verify_review_concurrency', Path(__file__).resolve().parents[1] / 'verify-review-concurrency.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32


class ConcurrencyDrillSafetyTest(unittest.TestCase):
    def test_duplicate_or_privileged_ports_are_rejected_before_opening_sockets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('fixture.war', 'java', 'psql'):
                (root / name).touch()
            base = ['verify-review-concurrency.py', '--war', str(root / 'fixture.war'),
                    '--java', str(root / 'java'), '--psql', str(root / 'psql')]
            for ports in (['--port-a', '18090', '--port-b', '18090'], ['--port-a', '80', '--port-b', '18091']):
                with self.subTest(ports=ports), patch.object(MODULE.sys, 'argv', base + ports), patch.object(MODULE.socket, 'socket') as socket:
                    with self.assertRaises(MODULE.VerificationError):
                        MODULE.arguments()
                    socket.assert_not_called()

    def test_children_keep_fixed_worker_concurrency_and_drop_external_configuration(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:5432/reviewer_integration', user='fixture', password='synthetic-db')
            args = types.SimpleNamespace(port=18090, war=root / 'fixture.war', java=root / 'java', startup_timeout_seconds=45)
            war = MODULE.HELPERS.OwnedWar(args, database, SCHEMA, 'http://127.0.0.1:18092', root, root, float('inf'))
            with patch.dict(os.environ, {'OPENAI_API_KEY': 'synthetic-external-key', 'GIT_CREDENTIALS_0_TOKEN': 'synthetic-token',
                    'SPRING_APPLICATION_JSON': 'synthetic-override', 'JAVA_TOOL_OPTIONS': 'synthetic-override', 'HTTPS_PROXY': 'synthetic-proxy'}, clear=True), \
                    patch.object(MODULE.HELPERS.subprocess, 'Popen') as launch, patch.object(MODULE.HELPERS, 'bounded_wait'):
                launch.return_value.poll.return_value = 0
                try:
                    war.start(True)
                    environment = launch.call_args.kwargs['env']
                    self.assertEqual(environment['REVIEW_CONCURRENCY'], '1')
                    self.assertEqual(environment['REVIEW_WORKER_ENABLED'], 'true')
                    self.assertEqual(environment['REVIEW_ENABLED'], 'false')
                    self.assertEqual(environment['AI_PROVIDER'], 'ollama')
                    self.assertEqual(environment['AI_BASE_URL'], 'http://127.0.0.1:18092')
                    self.assertEqual(environment['GIT_ALLOWED_HOSTS'], '127.0.0.1')
                    self.assertEqual(environment['AI_API_KEY'], '')
                    self.assertEqual(environment['GIT_TOKEN'], '')
                    for key in ('OPENAI_API_KEY', 'GIT_CREDENTIALS_0_TOKEN', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'HTTPS_PROXY'):
                        self.assertNotIn(key, environment)
                    self.assertEqual(launch.call_args.kwargs['creationflags'], MODULE.HELPERS.CREATE_FLAGS)
                finally:
                    war.stop(force=True)

    def test_fixture_mutations_reject_nonowned_identifiers_before_sql(self):
        database = Mock()
        for schema, project in (('public', 1), ('restart_test_bad', 1), (SCHEMA, 0), (SCHEMA, True), (SCHEMA, '1; DROP SCHEMA public')):
            for operation in (MODULE.force_fixture_due, MODULE.ownership):
                with self.subTest(schema=schema, operation=operation.__name__), self.assertRaises(MODULE.VerificationError):
                    operation(database, schema, project)
        database.sql.assert_not_called()

    def test_busy_deferral_must_not_change_claim_token_attempt_run_or_request(self):
        original = {'requestId': 'one', 'token': 'owned', 'runId': 10, 'attempts': 1, 'state': 'RUNNING', 'due': True}
        MODULE.assert_owner_unchanged(original, dict(original, due=False))
        for key, value in (('requestId', 'two'), ('token', 'stolen'), ('runId', 11), ('attempts', 2), ('state', 'SUCCEEDED')):
            with self.subTest(key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_owner_unchanged(original, dict(original, **{key: value}))

    def test_completion_rejects_duplicate_work_and_incorrect_checkpoint(self):
        request = {'requestId': 'one', 'actor': 3, 'source': 'MANUAL', 'state': 'SUCCEEDED', 'attempts': 1}
        complete = {'request': request, 'commits': [MODULE.PROJECTS['slow']['sha']], 'cursor': MODULE.PROJECTS['slow']['sha'],
                    'issues': 1, 'assignees': [3], 'runs': [{'state': 'SUCCEEDED', 'commits': 1}]}
        MODULE.assert_complete(complete, complete, 'slow')
        for patch_value in ({'issues': 2}, {'cursor': None}, {'commits': []}, {'runs': complete['runs'] * 2},
                            {'request': dict(request, attempts=2)}, {'assignees': [4]}):
            with self.subTest(patch=patch_value), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_complete(dict(complete, **patch_value), complete, 'slow')

    def test_cleanup_tries_both_owned_processes_and_preserves_schema_if_one_is_alive(self):
        first, second, fixture, database = Mock(), Mock(), Mock(), Mock()
        first.stop.side_effect = OSError('synthetic-private-failure')
        first.process.poll.return_value = None
        second.process = None
        pending, failed = MODULE.cleanup_owned([first, second], fixture, database, SCHEMA, True)
        self.assertTrue(pending)
        self.assertTrue(failed)
        first.stop.assert_called_once_with(force=True)
        second.stop.assert_called_once_with(force=True)
        fixture.close.assert_called_once()
        database.sql.assert_not_called()

    def test_cleanup_drops_only_the_valid_generated_schema_after_all_owned_processes_stop(self):
        war, fixture, database = Mock(), Mock(), Mock()
        war.process = None
        self.assertEqual(MODULE.cleanup_owned([war], fixture, database, SCHEMA, True), (False, False))
        database.sql.assert_called_once_with('DROP SCHEMA ' + SCHEMA + ' CASCADE;')
        database.reset_mock()
        self.assertEqual(MODULE.cleanup_owned([war], fixture, database, 'public', True), (True, True))
        database.sql.assert_not_called()

    def test_failed_schema_cleanup_remains_a_reportable_failure(self):
        fixture, database = Mock(), Mock()
        database.sql.side_effect = MODULE.VerificationError('isolated cleanup failed')
        self.assertEqual(MODULE.cleanup_owned([], fixture, database, SCHEMA, True), (True, True))

    def test_work_cleanup_rejects_live_process_or_target_outside_its_own_node(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / SCHEMA
            node = root / 'node-a'
            node.mkdir(parents=True)
            work = node / 'owned-work-fixture'
            work.mkdir()
            marker = work / 'marker.txt'
            marker.write_text('owned fixture')
            war = Mock()
            war.process.poll.return_value = None
            with self.assertRaises(MODULE.VerificationError):
                MODULE.remove_owned_work(work, node, [war])
            self.assertTrue(marker.exists())
            with self.assertRaises(MODULE.VerificationError):
                MODULE.remove_owned_work(root, node, [])
            self.assertTrue(marker.exists())
            MODULE.remove_owned_work(work, node, [])
            self.assertFalse(work.exists())
            self.assertTrue(node.exists())

    def test_synthetic_http_rejects_credentials_without_echoing_them(self):
        fixture = MODULE.ConcurrencyFixture()
        base = fixture.start()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            request = urllib.request.Request(base + '/api/v4/projects/fixture%2Fconcurrency-fast1/repository/commits?per_page=1',
                                             headers={'Authorization': 'Bearer synthetic-private-credential'})
            with self.assertRaises(urllib.error.HTTPError) as failure:
                opener.open(request, timeout=3)
            with failure.exception as response:
                self.assertEqual(response.code, 400)
                self.assertNotIn('synthetic-private-credential', response.read().decode('utf-8'))
            self.assertEqual(fixture.observed(), {})
            self.assertEqual(fixture.errors, ['VerificationError'])
        finally:
            fixture.close()

    def test_failure_report_omits_untrusted_exception_and_shares_bootstrap_password(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = types.SimpleNamespace(war=root / 'fixture.war', java=root / 'java', psql=root / 'psql',
                port_a=18090, port_b=18091, timeout_seconds=240, startup_timeout_seconds=45, report=root / 'report.json')
            database = Mock()
            database.sql.side_effect = lambda query: 'reviewer_integration' if query == 'SELECT current_database();' else ''
            created = []

            def new_war(*arguments):
                war = Mock()
                war.password = 'synthetic-node-password-' + str(len(created))
                war.process = None
                war.starts = 0
                war.start.side_effect = RuntimeError('synthetic-private-response-and-credential')
                created.append(war)
                return war

            with patch.object(MODULE, 'arguments', return_value=args), patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                    patch.object(MODULE.HELPERS, 'OwnedWar', side_effect=new_war), \
                    patch.object(MODULE.ConcurrencyFixture, 'start', return_value='http://127.0.0.1:18092'), patch('builtins.print'):
                self.assertEqual(MODULE.main(), 1)
            self.assertEqual(len(created), 2)
            self.assertEqual(created[0].password, created[1].password)
            self.assertNotEqual(created[0].password, 'synthetic-node-password-1')
            for war in created:
                war.stop.assert_called_once_with(force=True)
            text = args.report.read_text(encoding='utf-8')
            self.assertNotIn('synthetic-private', text)
            self.assertNotIn('synthetic-node-password', text)
            report = json.loads(text)
            self.assertEqual(report['result'], 'FAIL')
            self.assertEqual(report['failureType'], 'RuntimeError')
            self.assertFalse(report['ownedSchemaCleanupPending'])
            self.assertTrue(report['checks']['ownedWorkingDirectoriesRemoved'])


if __name__ == '__main__':
    unittest.main()
