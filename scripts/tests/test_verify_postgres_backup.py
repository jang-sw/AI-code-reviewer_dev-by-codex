import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_postgres_backup', Path(__file__).absolute().parents[1] / 'verify-postgres-backup.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ENV = {'TEST_DATABASE_URL': 'jdbc:postgresql://127.0.0.1:55439/reviewer_integration',
       'TEST_DATABASE_USERNAME': 'reviewer_test', 'TEST_DATABASE_PASSWORD': ''}


class BackupDrillSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reviewer-backup-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        self.cluster = self.workspace / '.local' / 'pg-validation'
        self.cluster.mkdir(parents=True)
        for name, value in (('reviewer-test-cluster', MODULE.LINUX.MARKER),
                            ('reviewer-test-platform', MODULE.LINUX.PLATFORM_MARKER), ('PG_VERSION', '17')):
            (self.cluster / name).write_text(value, encoding='utf-8')
        self.write_pid()
        self.pg_bin = self.workspace / 'pgbin'
        self.pg_bin.mkdir()
        for name in ('psql', 'pg_dump', 'pg_restore', 'createdb', 'dropdb'):
            (self.pg_bin / name).touch()
        self.args = types.SimpleNamespace(pg_bin=self.pg_bin, expected_postmaster_pid=1234,
            expected_postmaster_started_at=1000, parent_run_token='b' * 32,
            report=self.workspace / '.local' / 'report.json')
        self.environment_patch = patch.dict(os.environ, ENV, clear=True)
        self.environment_patch.start()
        self.addCleanup(self.environment_patch.stop)

    def write_pid(self, pid=1234, started=1000, port=55439, directory=None, listen='127.0.0.1'):
        (self.cluster / 'postmaster.pid').write_text(
            f'{pid}\n{directory or self.cluster}\n{started}\n{port}\n\n{listen}\nready\n', encoding='utf-8')

    def drill(self):
        return MODULE.BackupDrill(self.args, self.workspace)

    def server(self, **overrides):
        data = dict(directory=str(self.cluster), port='55439', listen='127.0.0.1',
                    pid='1234', started=1000, user='reviewer_test', version='170010')
        data.update(overrides)
        return json.dumps(data)

    def test_only_exact_test_source_and_user_are_accepted(self):
        invalid = ('jdbc:postgresql://localhost:55439/reviewer_integration',
                   'jdbc:postgresql://127.0.0.1:55439/identity_security',
                   'jdbc:postgresql://127.0.0.1:55439/reviewer_integration?options=x',
                   'jdbc:postgresql://127.0.0.1:65536/reviewer_integration',
                   'jdbc:postgresql://127.0.0.1:543/reviewer_integration',
                   'jdbc:postgresql://example.com:55439/reviewer_integration')
        for value in invalid:
            with self.subTest(value=value), patch.dict(os.environ, {'TEST_DATABASE_URL': value}), \
                    self.assertRaises(MODULE.SafetyError):
                self.drill()
        for name, value in (('TEST_DATABASE_USERNAME', 'postgres'), ('TEST_DATABASE_PASSWORD', 'unexpected-test-value')):
            with self.subTest(name=name), patch.dict(os.environ, {name: value}), self.assertRaises(MODULE.SafetyError):
                self.drill()

    def test_positive_parent_identity_and_exact_token_required(self):
        for name, values in (('expected_postmaster_pid', [0, -1, True, 2147483648]),
                             ('expected_postmaster_started_at', [0, False, -1]),
                             ('parent_run_token', ['a' * 31, 'A' * 32, 'a' * 32 + '/suffix'])):
            original = getattr(self.args, name)
            for value in values:
                with self.subTest(name=name, value=value), self.assertRaises(MODULE.SafetyError):
                    setattr(self.args, name, value)
                    self.drill()
            setattr(self.args, name, original)

    def test_report_must_remain_inside_local_workspace(self):
        self.args.report = self.workspace / 'report.json'
        with self.assertRaises(MODULE.SafetyError):
            self.drill()

    def test_child_environment_does_not_inherit_pg_credentials_services_or_ai(self):
        with patch.dict(os.environ, {'PGPASSWORD': 'private-value', 'PGSERVICE': 'production',
                                   'OPENAI_API_KEY': 'private-ai-value', 'HTTP_PROXY': 'http://example.invalid',
                                   'PGOPTIONS': 'unsafe-options', 'JAVA_TOOL_OPTIONS': 'unsafe-java'}):
            drill = self.drill()
        for name in ('PGPASSWORD', 'PGSERVICE', 'OPENAI_API_KEY', 'HTTP_PROXY', 'JAVA_TOOL_OPTIONS'):
            self.assertNotIn(name, drill.environment)
        self.assertEqual(str(drill.logs / 'unused-pgpass'), drill.environment['PGPASSFILE'])
        self.assertNotIn('unsafe-options', drill.environment['PGOPTIONS'])

    def test_source_and_other_database_writes_are_refused_without_command(self):
        drill = self.drill()
        for database, query in ((MODULE.SOURCE, 'DELETE FROM app_user;'), ('postgres', 'DROP DATABASE reviewer_integration;'),
                                (MODULE.SOURCE, 'COMMENT ON DATABASE ' + drill.target + " IS '" + drill.comment + "';")):
            with self.subTest(database=database), patch.object(drill, 'command') as command, self.assertRaises(MODULE.SafetyError):
                drill.sql(database, query, readonly=False)
            command.assert_not_called()
        with patch.object(drill, 'command') as command, self.assertRaises(MODULE.SafetyError):
            drill.sql('identity_security', 'SELECT 1;')
        command.assert_not_called()

    def test_readonly_sql_and_dump_enforce_session_readonly_without_mutating_base_environment(self):
        drill = self.drill()
        with patch.object(MODULE.LINUX, 'run_command', return_value=(0, '1')) as command:
            self.assertEqual('1', drill.sql(MODULE.SOURCE, 'SELECT 1;'))
        arguments, options = command.call_args
        self.assertIn('-X', arguments[0])
        self.assertIn('default_transaction_read_only=on', options['environment']['PGOPTIONS'])
        self.assertNotIn('default_transaction_read_only', drill.environment['PGOPTIONS'])
        self.assertTrue(options['capture'])

    def test_marker_platform_and_version_rejected_before_sql(self):
        for name in ('reviewer-test-cluster', 'reviewer-test-platform', 'PG_VERSION'):
            path = self.cluster / name
            original = path.read_bytes()
            try:
                path.write_text('wrong', encoding='utf-8')
                drill = self.drill()
                with self.subTest(name=name), patch.object(drill, 'sql') as sql, self.assertRaises(MODULE.SafetyError):
                    drill.verify_owned()
                sql.assert_not_called()
            finally:
                path.write_bytes(original)

    def test_replaced_cluster_directory_or_marker_is_refused(self):
        drill = self.drill()
        drill.path_identity = drill.identity(self.cluster)
        drill.marker_identity = (-1, -1)
        with patch.object(drill, 'sql') as sql, self.assertRaises(MODULE.SafetyError):
            drill.verify_owned()
        sql.assert_not_called()

    def test_disk_pid_start_port_directory_listen_mismatch_refused_before_sql(self):
        for values in (dict(pid=4567), dict(started=1002), dict(port=5432),
                       dict(directory=self.workspace), dict(listen='*')):
            self.write_pid(**values)
            drill = self.drill()
            with self.subTest(values=values), patch.object(drill, 'sql') as sql, self.assertRaises(MODULE.SafetyError):
                drill.verify_owned()
            sql.assert_not_called()
        self.write_pid()

    def test_sql_server_identity_version_account_must_match(self):
        for values in (dict(pid='1235'), dict(started=1003), dict(port='5432'),
                       dict(directory=str(self.workspace)), dict(listen='*'), dict(user='postgres'), dict(version='180000')):
            drill = self.drill()
            with self.subTest(values=values), patch.object(drill, 'sql', return_value=self.server(**values)), \
                    self.assertRaises(MODULE.SafetyError):
                drill.verify_owned()
        with patch.object(drill, 'sql', return_value=self.server()):
            drill.verify_owned()

    def test_preflight_existing_report_is_preserved_without_database_connection(self):
        drill = self.drill()
        drill.report.write_text('existing evidence', encoding='utf-8')
        with patch.object(MODULE.LINUX, 'require_linux_user'), patch.object(MODULE.LINUX, 'native_filesystem'), \
                patch.object(MODULE.Path, 'read_text', return_value='fixture mount inventory'), \
                patch.object(MODULE.os, 'access', return_value=True), \
                patch.object(drill, 'command', return_value='psql (PostgreSQL) 17.9\n'), \
                patch.object(drill, 'verify_owned') as verify, self.assertRaises(MODULE.SafetyError):
            drill.prepare()
        verify.assert_not_called()
        self.assertEqual('existing evidence', drill.report.read_text(encoding='utf-8'))
        self.assertFalse(drill.logs.exists())

    def test_preflight_mixed_postgres_version_is_refused_without_database_connection(self):
        drill = self.drill()
        with patch.object(MODULE.LINUX, 'require_linux_user'), patch.object(MODULE.LINUX, 'native_filesystem'), \
                patch.object(MODULE.Path, 'read_text', return_value='fixture mount inventory'), \
                patch.object(MODULE.os, 'access', return_value=True), \
                patch.object(drill, 'command', side_effect=['psql (PostgreSQL) 17.9\n', 'pg_dump (PostgreSQL) 18.1\n']), \
                patch.object(drill, 'verify_owned') as verify, self.assertRaises(MODULE.SafetyError):
            drill.prepare()
        verify.assert_not_called()
        self.assertFalse(drill.logs.exists())

    def test_preflight_rejects_windows_nested_mount_before_commands(self):
        drill = self.drill()
        with patch.object(MODULE.LINUX, 'require_linux_user'), \
                patch.object(MODULE.Path, 'read_text', return_value='fixture mount inventory'), \
                patch.object(MODULE.LINUX, 'native_filesystem', side_effect=[None, MODULE.SafetyError('non-native mount')]), \
                patch.object(drill, 'command') as command, self.assertRaises(MODULE.SafetyError):
            drill.prepare()
        command.assert_not_called()
        self.assertFalse(drill.logs.exists())

    def test_existing_target_is_never_reused_or_deleted(self):
        drill = self.drill()
        with patch.object(drill, 'verify_owned'), patch.object(drill, 'target_identity', return_value={'oid': 17}), \
                patch.object(drill, 'command') as command, self.assertRaises(MODULE.SafetyError):
            drill.create_target()
        self.assertFalse(drill.created_here)
        command.assert_not_called()
        with patch.object(drill, 'command') as command:
            drill.cleanup()
        command.assert_not_called()

    def test_failed_create_is_not_adopted_for_cleanup(self):
        drill = self.drill()
        with patch.object(drill, 'verify_owned'), patch.object(drill, 'target_identity', return_value=None), \
                patch.object(drill, 'command', side_effect=MODULE.SafetyError('fixed failure')), \
                self.assertRaises(MODULE.SafetyError):
            drill.create_target()
        self.assertFalse(drill.created_here)

    def test_successful_create_captures_oid_marks_database_and_verifies_owner(self):
        drill = self.drill()
        current = {'oid': 42, 'owner': 'reviewer_test', 'comment': None}
        with patch.object(drill, 'verify_owned'), patch.object(drill, 'target_identity', side_effect=[None, current]), \
                patch.object(drill, 'command') as command, patch.object(drill, 'sql') as sql, \
                patch.object(drill, 'verify_target') as verify:
            drill.create_target()
        self.assertEqual(42, drill.database_oid)
        self.assertTrue(drill.created_here)
        self.assertEqual('createdb', command.call_args.args[0])
        self.assertIn('--template=template0', command.call_args.args[1])
        self.assertEqual(False, sql.call_args.kwargs['readonly'])
        self.assertIn(drill.comment, sql.call_args.args[1])
        verify.assert_called_once()

    def test_cleanup_requires_same_cluster_database_oid_owner_and_comment(self):
        for current in (None, {'oid': 43, 'comment': 'unused'}, {'oid': 42, 'comment': None}):
            drill = self.drill()
            drill.created_here, drill.database_oid = True, 42
            with self.subTest(current=current), patch.object(drill, 'verify_owned'), \
                    patch.object(drill, 'target_identity', return_value=current), patch.object(drill, 'command') as command, \
                    self.assertRaises(MODULE.SafetyError):
                drill.cleanup()
            command.assert_not_called()
            self.assertTrue(drill.created_here)
        drill = self.drill()
        drill.created_here, drill.database_oid = True, 42
        with patch.object(drill, 'verify_owned', side_effect=MODULE.SafetyError('changed server')), \
                patch.object(drill, 'command') as command, self.assertRaises(MODULE.SafetyError):
            drill.cleanup()
        command.assert_not_called()

    def test_foreign_database_owner_or_invalid_identity_is_refused(self):
        drill = self.drill()
        for value in ({'oid': 42, 'owner': 'postgres', 'comment': drill.comment},
                      {'oid': True, 'owner': 'reviewer_test', 'comment': drill.comment},
                      {'oid': '42', 'owner': 'reviewer_test', 'comment': drill.comment},
                      {'oid': 42, 'owner': 'reviewer_test'}, []):
            with self.subTest(value=value), patch.object(drill, 'sql', return_value=json.dumps(value)), \
                    self.assertRaises(MODULE.SafetyError):
                drill.target_identity()

    def test_database_oid_is_cast_to_numeric_json_before_strict_validation(self):
        drill = self.drill()
        current = {'oid': 16384, 'owner': 'reviewer_test', 'comment': None}
        with patch.object(drill, 'sql', return_value=json.dumps(current)) as sql:
            self.assertEqual(current, drill.target_identity())
        self.assertIn("'oid',d.oid::bigint,", sql.call_args.args[1])

    def test_successful_cleanup_drops_only_generated_target_and_checks_absence(self):
        drill = self.drill()
        drill.created_here, drill.database_oid = True, 42
        with patch.object(drill, 'verify_target') as verify, patch.object(drill, 'command') as command, \
                patch.object(drill, 'target_identity', return_value=None):
            drill.cleanup()
        verify.assert_called_once()
        self.assertEqual(('dropdb', [*drill.connection, drill.target]), command.call_args.args)
        self.assertNotIn('--force', command.call_args.args[1])
        self.assertFalse(drill.created_here)

    def test_failed_drop_preserves_ownership_and_no_success_report(self):
        drill = self.drill()
        drill.created_here, drill.database_oid = True, 42
        with patch.object(drill, 'verify_target'), \
                patch.object(drill, 'command', side_effect=MODULE.SafetyError('fixed failure')), \
                self.assertRaises(MODULE.SafetyError):
            drill.cleanup()
        self.assertTrue(drill.created_here)
        self.assertFalse(drill.report.exists())

    def test_fingerprint_reads_exact_twelve_public_tables_and_refuses_unbounded_output(self):
        drill = self.drill()
        with patch.object(drill, 'sql', return_value='3:' + 'a' * 32) as sql:
            result = drill.fingerprints(MODULE.SOURCE)
        self.assertEqual(set(MODULE.TABLES), set(result))
        self.assertEqual(12, sql.call_count)
        self.assertIn('auth_attempt_policy', result)
        self.assertIn('auth_attempt_bucket', result)
        for table, call in zip(MODULE.TABLES, sql.call_args_list):
            self.assertEqual(MODULE.SOURCE, call.args[0])
            self.assertIn('FROM public.' + table + ' t;', call.args[1])
        with patch.object(drill, 'sql', return_value='unexpected row contents'), self.assertRaises(MODULE.SafetyError):
            drill.fingerprints(MODULE.SOURCE)

    def test_identity_and_queue_inserts_use_only_restore_database_and_rollback(self):
        drill = self.drill()
        with patch.object(drill, 'verify_target') as verify, patch.object(drill, 'sql') as sql:
            drill.verify_inserts()
        self.assertEqual(2, verify.call_count)
        self.assertEqual(2, sql.call_count)
        for call in sql.call_args_list:
            self.assertEqual(drill.target, call.args[0])
            self.assertTrue(call.args[1].startswith('BEGIN;'))
            self.assertTrue(call.args[1].endswith('ROLLBACK;'))
            self.assertFalse(call.kwargs['readonly'])
        self.assertIn('public.review_request', sql.call_args_list[1].args[1])

    def prepare_mocked_run(self, drill):
        drill.logs.mkdir()
        drill.dump.write_bytes(b'synthetic fixture dump')

    def test_dump_restore_source_and_cleanup_success_are_required_before_pass_report(self):
        drill = self.drill()
        self.prepare_mocked_run(drill)
        expected = {table: '1:' + 'b' * 32 for table in MODULE.TABLES}
        with patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), patch.object(drill, 'verify_target'), \
                patch.object(drill, 'create_target'), patch.object(drill, 'command') as command, \
                patch.object(drill, 'fingerprints', return_value=expected), patch.object(drill, 'verify_inserts') as inserts, \
                patch.object(drill, 'cleanup') as cleanup, redirect_stdout(io.StringIO()):
            drill.execute()
        report = json.loads(drill.report.read_text(encoding='utf-8'))
        self.assertEqual('PASS', report['result'])
        self.assertEqual(self.args.parent_run_token, report['parentRunToken'])
        self.assertTrue(report['restoreDatabaseRemoved'])
        self.assertTrue(report['sourceReadOnly'])
        self.assertFalse(report['externalServicesUsed'])
        self.assertEqual(12, len(report['verifiedTables']))
        self.assertIn('auth_attempt_policy', report['verifiedTables'])
        self.assertIn('auth_attempt_bucket', report['verifiedTables'])
        self.assertNotIn('fingerprints', report)
        self.assertEqual('pg_dump', command.call_args_list[0].args[0])
        self.assertTrue(command.call_args_list[0].kwargs['readonly'])
        restore = command.call_args_list[1].args
        self.assertEqual('pg_restore', restore[0])
        self.assertIn('--single-transaction', restore[1])
        self.assertNotIn('--clean', restore[1])
        self.assertNotIn('--create', restore[1])
        inserts.assert_called_once()
        cleanup.assert_called_once()

    def test_changed_source_or_restore_mismatch_cleans_up_without_success_report(self):
        for fingerprints in ([{'t': 'original'}, {'t': 'changed'}],
                              [{'t': 'original'}, {'t': 'original'}, {'t': 'changed'}]):
            drill = self.drill()
            self.prepare_mocked_run(drill)
            with self.subTest(fingerprints=fingerprints), patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), \
                    patch.object(drill, 'verify_target'), patch.object(drill, 'create_target'), patch.object(drill, 'command'), \
                    patch.object(drill, 'fingerprints', side_effect=fingerprints), patch.object(drill, 'cleanup') as cleanup, \
                    patch.object(drill, 'verify_inserts') as inserts, self.assertRaises(MODULE.SafetyError):
                drill.execute()
            cleanup.assert_called_once()
            inserts.assert_not_called()
            self.assertFalse(drill.report.exists())

    def test_restore_failure_or_interrupt_still_attempts_owned_cleanup(self):
        for failure in (MODULE.SafetyError('fixed failure'), KeyboardInterrupt()):
            drill = self.drill()
            self.prepare_mocked_run(drill)
            with self.subTest(failure=type(failure).__name__), patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), \
                    patch.object(drill, 'verify_target'), patch.object(drill, 'create_target'), \
                    patch.object(drill, 'command', side_effect=['', failure]), patch.object(drill, 'fingerprints', return_value={}), \
                    patch.object(drill, 'cleanup') as cleanup, self.assertRaises(type(failure)):
                drill.execute()
            cleanup.assert_called_once()
            self.assertFalse(drill.report.exists())

    def test_cleanup_failure_blocks_success_report_after_other_checks_pass(self):
        drill = self.drill()
        self.prepare_mocked_run(drill)
        with patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), patch.object(drill, 'verify_target'), \
                patch.object(drill, 'create_target'), patch.object(drill, 'command'), patch.object(drill, 'fingerprints', return_value={}), \
                patch.object(drill, 'verify_inserts'), patch.object(drill, 'cleanup', side_effect=MODULE.SafetyError('changed owner')), \
                self.assertRaises(MODULE.SafetyError):
            drill.execute()
        self.assertFalse(drill.report.exists())

    def test_created_target_identity_failure_survives_unproven_ownership_cleanup_failure(self):
        drill = self.drill()
        self.prepare_mocked_run(drill)
        original = MODULE.SafetyError('primary identity failure must not be echoed')
        output = io.StringIO()
        with patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), \
                patch.object(drill, 'fingerprints', return_value={}), patch.object(drill, 'command') as command, \
                patch.object(drill, 'target_identity', side_effect=[None, original]), redirect_stderr(output), \
                self.assertRaises(MODULE.SafetyError) as caught:
            drill.execute()
        self.assertIs(original, caught.exception)
        self.assertTrue(drill.created_here)
        self.assertIsNone(drill.database_oid)
        self.assertEqual(['pg_dump', 'createdb'], [call.args[0] for call in command.call_args_list])
        self.assertFalse(drill.report.exists())
        self.assertIn('CLEANUP INCOMPLETE:', output.getvalue())
        self.assertNotIn(str(original), output.getvalue())

    def test_interruption_survives_cleanup_error_and_keeps_exit_130(self):
        drill = self.drill()
        self.prepare_mocked_run(drill)
        interrupted = KeyboardInterrupt()
        output = io.StringIO()
        with patch.object(MODULE.LINUX, 'require_linux_user'), patch.object(MODULE.signal, 'signal'), \
                patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'BackupDrill', return_value=drill), \
                patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), patch.object(drill, 'verify_target'), \
                patch.object(drill, 'create_target'), patch.object(drill, 'fingerprints', return_value={}), \
                patch.object(drill, 'command', side_effect=['', interrupted]), \
                patch.object(drill, 'cleanup', side_effect=OSError('private cleanup details')), redirect_stderr(output):
            self.assertEqual(130, MODULE.main([]))
        self.assertFalse(drill.report.exists())
        self.assertIn('CLEANUP INCOMPLETE:', output.getvalue())
        self.assertIn('INTERRUPTED:', output.getvalue())
        self.assertNotIn('private cleanup details', output.getvalue())

    def test_cleanup_only_failure_is_preserved_without_secondary_failure_diagnostic(self):
        drill = self.drill()
        self.prepare_mocked_run(drill)
        original = MODULE.SafetyError('cleanup ownership failure')
        output = io.StringIO()
        with patch.object(drill, 'prepare'), patch.object(drill, 'verify_owned'), patch.object(drill, 'verify_target'), \
                patch.object(drill, 'create_target'), patch.object(drill, 'command'), patch.object(drill, 'fingerprints', return_value={}), \
                patch.object(drill, 'verify_inserts'), patch.object(drill, 'cleanup', side_effect=original), \
                redirect_stderr(output), self.assertRaises(MODULE.SafetyError) as caught:
            drill.execute()
        self.assertIs(original, caught.exception)
        self.assertEqual('', output.getvalue())
        self.assertFalse(drill.report.exists())

    def test_fixed_failure_output_does_not_echo_exception_or_environment(self):
        output = io.StringIO()
        with patch.object(MODULE.LINUX, 'require_linux_user'), patch.object(MODULE.signal, 'signal'), \
                patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE.BackupDrill, 'execute', side_effect=MODULE.SafetyError('private diagnostic value')), \
                patch.object(MODULE, 'WORKSPACE', self.workspace), redirect_stderr(output):
            self.assertEqual(1, MODULE.main([]))
        self.assertNotIn('private diagnostic value', output.getvalue())
        self.assertIn('FAIL:', output.getvalue())


if __name__ == '__main__':
    unittest.main()
