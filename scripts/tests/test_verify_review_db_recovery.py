import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_db_recovery', Path(__file__).absolute().parents[1] / 'verify-review-db-recovery.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32


class DbRecoveryDrillSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reviewer-db-recovery-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        self.cluster = self.workspace / '.local' / 'pg-validation'
        self.cluster.mkdir(parents=True)
        (self.cluster / 'reviewer-test-cluster').write_text(MODULE.MARKER + '\n', encoding='utf-8')
        (self.cluster / 'PG_VERSION').write_text('17\n', encoding='utf-8')
        self.write_pid()
        self.args = types.SimpleNamespace(cluster_path=self.cluster, expected_postmaster_pid=1234, expected_postmaster_started_at=1000,
            pg_ctl=self.workspace / 'pg_ctl', port=18092, war=self.workspace / 'fixture.war',
            java=self.workspace / 'java', psql=self.workspace / 'psql', timeout_seconds=240,
            startup_timeout_seconds=45, report=self.workspace / 'report.json', parent_run_token='b' * 32)
        self.database = Mock(url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration')
        self.database.sql.return_value = self.server()
        workspace_patch = patch.object(MODULE, 'WORKSPACE', self.workspace)
        workspace_patch.start()
        self.addCleanup(workspace_patch.stop)

    def write_pid(self, pid=1234, port=55439, started=1000, directory=None, listen='127.0.0.1'):
        (self.cluster / 'postmaster.pid').write_text(
            f'{pid}\n{directory or self.cluster}\n{started}\n{port}\n\n{listen}\nready\n', encoding='utf-8')

    def server(self, **overrides):
        data = dict(directory=str(self.cluster), port='55439', listen='127.0.0.1', pid='1234', started=1000)
        data.update(overrides)
        return json.dumps(data)

    def owned(self):
        return MODULE.ParentOwnedCluster(self.args, self.database, self.workspace)

    def test_parent_run_token_must_be_exact_lowercase_hex_before_any_socket(self):
        for name in ('war', 'java', 'psql', 'pg_ctl'):
            getattr(self.args, name).touch()
        base = ['verify-review-db-recovery.py', '--war', str(self.args.war), '--java', str(self.args.java),
                '--psql', str(self.args.psql), '--pg-ctl', str(self.args.pg_ctl), '--cluster-path', str(self.cluster),
                '--expected-postmaster-pid', '1234', '--expected-postmaster-started-at', '1000', '--port', '18092']
        for token in ('', 'a' * 31, 'A' * 32, 'a' * 32 + '/suffix'):
            with self.subTest(token=token), patch.object(MODULE.sys, 'argv', base + ['--parent-run-token', token]), \
                    patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
                MODULE.arguments()
            socket.assert_not_called()

    def test_only_exact_repository_test_cluster_is_accepted(self):
        outside = self.workspace / 'production'
        outside.mkdir()
        self.args.cluster_path = outside
        with patch.object(MODULE.subprocess, 'run') as command, self.assertRaises(MODULE.VerificationError):
            self.owned()
        command.assert_not_called()
        self.database.sql.assert_not_called()

    def test_marker_version_and_parent_pid_are_required_before_any_lifecycle_command(self):
        variants = [('reviewer-test-cluster', 'wrong marker'), ('PG_VERSION', '16'), ('postmaster.pid', '9876\n')]
        for filename, contents in variants:
            path = self.cluster / filename
            original = path.read_bytes()
            try:
                path.write_text(contents, encoding='utf-8')
                with self.subTest(filename=filename), patch.object(MODULE.subprocess, 'run') as command, \
                        self.assertRaises(MODULE.VerificationError):
                    self.owned()
                command.assert_not_called()
            finally:
                path.write_bytes(original)

    def test_pid_file_directory_port_listen_and_pid_must_match(self):
        for values in (dict(pid=9876), dict(port=5432), dict(directory=self.workspace), dict(listen='*')):
            self.write_pid(**values)
            with self.subTest(values=values), self.assertRaises(MODULE.VerificationError):
                self.owned()
        self.database.sql.assert_not_called()

    def test_connected_server_must_match_disk_identity(self):
        for values in (dict(directory=str(self.workspace)), dict(pid='9876'), dict(port='5432'),
                       dict(listen='*'), dict(started=1100)):
            self.database.sql.return_value = self.server(**values)
            with self.subTest(values=values), patch.object(MODULE.subprocess, 'run') as command, \
                    self.assertRaises(MODULE.VerificationError):
                self.owned()
            command.assert_not_called()

    def test_reused_parent_pid_with_new_disk_and_sql_start_time_is_rejected(self):
        self.write_pid(started=2000)
        self.database.sql.return_value = self.server(started=2000)
        with patch.object(MODULE.subprocess, 'run') as command, self.assertRaises(MODULE.VerificationError):
            self.owned()
        command.assert_not_called()

    def test_expected_parent_start_time_must_be_positive_int64(self):
        for value in (0, -1, 9223372036854775808, True, '1000'):
            self.args.expected_postmaster_started_at = value
            with self.subTest(value=value), patch.object(MODULE.subprocess, 'run') as command, \
                    self.assertRaises(MODULE.VerificationError):
                self.owned()
            command.assert_not_called()
        self.database.sql.assert_not_called()

    def test_expected_parent_pid_must_be_positive_int32(self):
        for value in (0, -1, 2147483648, True, '1234'):
            self.args.expected_postmaster_pid = value
            with self.subTest(value=value), patch.object(MODULE.subprocess, 'run') as command, \
                    self.assertRaises(MODULE.VerificationError):
                self.owned()
            command.assert_not_called()
        self.database.sql.assert_not_called()

    def test_changed_pid_or_replaced_marker_is_rechecked_before_stop(self):
        cluster = self.owned()
        self.write_pid(pid=9876)
        with patch.object(MODULE.subprocess, 'run') as command, self.assertRaises(MODULE.VerificationError):
            cluster.stop()
        command.assert_not_called()
        self.assertFalse(cluster.stop_attempted)
        self.write_pid()
        marker = self.cluster / 'reviewer-test-cluster'
        marker.write_text('untrusted marker', encoding='utf-8')
        with patch.object(MODULE.subprocess, 'run') as command, self.assertRaises(MODULE.VerificationError):
            cluster.stop()
        command.assert_not_called()

    def test_symlink_and_reparse_paths_are_rejected(self):
        link = self.workspace / 'linked-cluster'
        try:
            link.symlink_to(self.cluster, target_is_directory=True)
        except OSError:
            link = None
        if link is not None:
            with self.assertRaises(MODULE.VerificationError):
                MODULE.checked_path(link, directory=True)
        original = Path.lstat

        def reparse(path):
            info = original(path)
            return types.SimpleNamespace(st_mode=info.st_mode, st_file_attributes=0x400)

        with patch.object(Path, 'lstat', reparse), self.assertRaises(MODULE.VerificationError):
            MODULE.checked_path(self.cluster, directory=True)

    def test_control_has_no_inherited_pipes_or_sensitive_environment(self):
        with patch.dict(os.environ, {'PATH': 'synthetic-path', 'OPENAI_API_KEY': 'synthetic-secret',
                                    'PGPASSWORD': 'synthetic-db-secret', 'JAVA_TOOL_OPTIONS': 'synthetic-injection'}, clear=True):
            cluster = self.owned()
        with patch.object(MODULE.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as command:
            cluster.control('start')
        call = command.call_args
        self.assertEqual(call.kwargs['stdout'], subprocess.DEVNULL)
        self.assertEqual(call.kwargs['stderr'], subprocess.DEVNULL)
        self.assertEqual(call.kwargs['stdin'], subprocess.DEVNULL)
        self.assertEqual(call.kwargs['timeout'], 25)
        self.assertEqual(call.kwargs['env'], {'PATH': 'synthetic-path'})
        self.assertEqual(call.kwargs['creationflags'], MODULE.HELPERS.CREATE_FLAGS)
        self.assertIn('-p 55439 -h 127.0.0.1', call.args[0])
        self.assertIn(str(self.workspace / 'postgres-recovery.log'), call.args[0])

    def test_restore_never_starts_without_a_prior_owned_stop(self):
        cluster = self.owned()
        with patch.object(cluster, 'control') as control:
            cluster.restore()
        control.assert_not_called()

    def test_restore_rejects_unexpected_replacement_pid(self):
        cluster = self.owned()
        cluster.stop_attempted = True
        self.write_pid(pid=9876)
        with patch.object(cluster, 'control') as control, self.assertRaises(MODULE.VerificationError):
            cluster.restore()
        control.assert_not_called()

    def test_restored_server_new_pid_is_verified_against_sql(self):
        cluster = self.owned()
        cluster.stop_attempted = True
        (self.cluster / 'postmaster.pid').unlink()

        def start(operation):
            self.assertEqual(operation, 'start')
            self.write_pid(pid=5678, started=2000)
            self.database.sql.return_value = self.server(pid='5678', started=2000)

        with patch.object(cluster, 'control', side_effect=start) as control, patch.object(MODULE.socket, 'socket'):
            cluster.restore()
            cluster.restore()
        control.assert_called_once_with('start')
        self.assertEqual(cluster.pid, 5678)
        self.assertEqual(cluster.started_at, 2000)

    def test_failed_start_does_not_adopt_an_ambiguous_new_pid(self):
        cluster = self.owned()
        cluster.stop_attempted = True
        (self.cluster / 'postmaster.pid').unlink()

        def failed_start(operation):
            self.write_pid(pid=9876, started=2000)
            raise MODULE.VerificationError('fixed start failure')

        with patch.object(cluster, 'control', side_effect=failed_start), patch.object(MODULE.socket, 'socket'):
            with self.assertRaises(MODULE.VerificationError):
                cluster.restore()
            with self.assertRaises(MODULE.VerificationError):
                cluster.restore()
        self.assertEqual(cluster.pid, 1234)
        self.assertIsNone(MODULE.parent_handoff(cluster))

    def test_parent_handoff_requires_current_verified_pid_and_marker(self):
        cluster = self.owned()
        self.assertEqual(MODULE.parent_handoff(cluster), {'pid': 1234, 'startedAt': 1000})
        self.write_pid(pid=9876)
        self.assertIsNone(MODULE.parent_handoff(cluster))
        self.write_pid()
        (self.cluster / 'reviewer-test-cluster').write_text('replaced marker', encoding='utf-8')
        self.assertIsNone(MODULE.parent_handoff(cluster))
        self.assertIsNone(MODULE.parent_handoff(None))

    def test_failed_control_never_echoes_output_or_arbitrary_exception(self):
        cluster = self.owned()
        for failure in (OSError('synthetic-private'), subprocess.TimeoutExpired('synthetic-private', 25)):
            with patch.object(MODULE.subprocess, 'run', side_effect=failure), self.assertRaises(MODULE.VerificationError) as caught:
                cluster.control('stop')
            self.assertNotIn('synthetic-private', str(caught.exception))

    def test_cleanup_restores_database_even_if_war_stop_fails_and_retains_schema(self):
        war, fixture, cluster, database = Mock(), Mock(), Mock(stop_attempted=True), Mock()
        war.stop.side_effect = OSError('synthetic-private')
        war.process.poll.return_value = None
        pending, restored, failed = MODULE.cleanup_owned(war, fixture, cluster, database, SCHEMA, True)
        self.assertEqual((pending, restored, failed), (True, True, True))
        cluster.restore.assert_called_once()
        fixture.release_first_b.set.assert_called_once()
        fixture.release_second_b.set.assert_called_once()
        database.sql.assert_not_called()

    def test_cleanup_preserves_schema_when_db_restore_fails_and_rejects_foreign_schema(self):
        war, fixture, cluster, database = Mock(process=None), Mock(), Mock(stop_attempted=True), Mock()
        cluster.restore.side_effect = MODULE.VerificationError('fixed restoration failure')
        self.assertEqual(MODULE.cleanup_owned(war, fixture, cluster, database, SCHEMA, True), (True, False, True))
        database.sql.assert_not_called()
        cluster.restore.side_effect = None
        self.assertEqual(MODULE.cleanup_owned(war, fixture, cluster, database, 'public', True), (True, True, True))
        database.sql.assert_not_called()
        self.assertEqual(MODULE.cleanup_owned(war, fixture, cluster, database, SCHEMA, True), (False, True, False))
        database.sql.assert_called_once_with('DROP SCHEMA ' + SCHEMA + ' CASCADE;')

    def test_health_requires_exact_status_only_payload(self):
        for payload, code, acceptable in ((b'{"status":"DOWN"}', 503, True),
                (b'{"status":"DOWN","components":{"db":{"error":"synthetic-private"}}}', 503, False),
                (b'{"status":"UP"}', 200, False)):
            response = Mock(code=code)
            response.read.return_value = payload
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=None)
            with patch.object(MODULE.urllib.request, 'build_opener') as opener:
                opener.return_value.open.return_value = response
                if acceptable:
                    MODULE.probe_health('http://127.0.0.1:18092/' + SCHEMA, 'readiness', 503, 'DOWN')
                else:
                    with self.assertRaises(MODULE.VerificationError) as caught:
                        MODULE.probe_health('http://127.0.0.1:18092/' + SCHEMA, 'readiness', 503, 'DOWN')
                    self.assertNotIn('synthetic-private', str(caught.exception))

    def test_worker_failure_observation_is_specific_and_bounded(self):
        log = self.workspace / 'war.log'
        log.write_text('Review queue dispatch failed: ignored\nProject 2 review execution failed: ignored\n', encoding='utf-8')
        self.assertFalse(MODULE.worker_failed(log, 1))
        log.write_text('Project 1 review execution failed: CannotCreateTransactionException\n', encoding='utf-8')
        self.assertTrue(MODULE.worker_failed(log, 1))
        log.write_bytes(b'x' * 2_000_001)
        with self.assertRaises(MODULE.VerificationError):
            MODULE.worker_failed(log, 1)

    def test_main_failure_report_does_not_include_private_exception(self):
        with patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE.HELPERS, 'Database', return_value=self.database), \
                patch.object(MODULE, 'ParentOwnedCluster', side_effect=RuntimeError('synthetic-private-configuration')), \
                redirect_stdout(io.StringIO()) as output:
            self.assertEqual(MODULE.main(), 1)
        report = self.args.report.read_text(encoding='utf-8')
        self.assertNotIn('synthetic-private', report + output.getvalue())
        parsed = json.loads(report)
        self.assertEqual(parsed['failureType'], 'RuntimeError')
        self.assertEqual(parsed['parentRunToken'], 'b' * 32)
        self.assertNotIn('parentOwnedPostmaster', parsed)
        self.database.sql.assert_not_called()


if __name__ == '__main__':
    unittest.main()
