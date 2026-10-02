"""Portable safety fixtures: no PostgreSQL, Maven, Java or real child is executed."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('test_postgres_linux', Path(__file__).resolve().parents[1] / 'test-postgres-linux.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LinuxPostgresSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='linux-pg-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.pg_bin = self.workspace / 'tools' / 'pg17' / 'bin'
        self.pg_bin.mkdir(parents=True)
        for name in ('postgres', 'pg_ctl', 'initdb', 'psql', 'createdb'):
            (self.pg_bin / name).write_text('synthetic executable', encoding='utf-8')
        java = self.workspace / 'tools' / 'java25' / 'bin' / 'java'
        java.parent.mkdir(parents=True)
        java.write_text('synthetic executable', encoding='utf-8')
        self.args = argparse.Namespace(pg_bin=self.pg_bin, java=java, port=55439, review_restart=False,
            review_concurrency=False, review_database_recovery=False, review_restart_port=18089,
            review_concurrency_port_a=18090, review_concurrency_port_b=18091, review_database_recovery_port=18092)
        (self.workspace / 'source' / 'target').mkdir(parents=True)
        (self.workspace / 'source' / 'mvnw').write_text('synthetic wrapper', encoding='utf-8')
        (self.workspace / 'source' / 'target' / 'ai-code-reviewer.war').write_bytes(b'synthetic')
        (self.workspace / 'scripts').mkdir()
        for name in ('verify-test-reports.py', 'verify-review-restart.py', 'verify-review-concurrency.py', 'verify-review-db-recovery.py', 'verify-postgres-backup.py'):
            (self.workspace / 'scripts' / name).write_text('# synthetic fixture', encoding='utf-8')
        self.runner = MODULE.TestRun(self.args, self.workspace)
        self.runner.cluster.mkdir(parents=True)
        for name, value in (('reviewer-test-cluster', MODULE.MARKER), ('reviewer-test-platform', MODULE.PLATFORM_MARKER),
                            ('PG_VERSION', '17'), ('postgresql.conf', "unix_socket_directories = ''\n")):
            (self.runner.cluster / name).write_text(value + '\n', encoding='utf-8')
        self.pid_file = self.runner.cluster / 'postmaster.pid'

    def write_pid(self, pid=123, started=1800000000, port=55439, directory=None, listen='127.0.0.1'):
        self.pid_file.write_text(f'{pid}\n{directory or self.runner.cluster}\n{started}\n{port}\n\n{listen}\n0 0\nready\n', encoding='utf-8')

    def enable_upgrade(self):
        self.args.review_upgrade = True
        self.args.review_upgrade_port = 18093
        self.args.previous_war = self.workspace / 'previous.war'
        self.args.previous_war.write_bytes(b'previous synthetic WAR')
        self.args.expected_previous_war_sha256 = hashlib.sha256(b'previous synthetic WAR').hexdigest()
        (self.workspace / 'scripts' / 'verify-review-upgrade.py').write_text('# synthetic fixture', encoding='utf-8')

    def test_upgrade_requires_explicit_previous_war_and_recorded_hash_together(self):
        base = ['--pg-bin', str(self.pg_bin), '--java', str(self.args.java)]
        self.enable_upgrade()
        previous = ['--previous-war', str(self.args.previous_war)]
        checksum = ['--expected-previous-war-sha256', self.args.expected_previous_war_sha256]
        for extra in (['--review-upgrade'], previous, checksum, previous + checksum,
                      ['--review-upgrade'] + previous, ['--review-upgrade'] + checksum):
            with self.subTest(extra=extra), self.assertRaises(MODULE.SafetyError):
                MODULE.arguments(base + extra)
        args = MODULE.arguments(base + ['--review-upgrade'] + previous + checksum)
        self.assertTrue(args.review_upgrade)
        self.assertEqual(args.review_upgrade_port, 18093)
        for value in ('x' * 64, 'a' * 63, 'a' * 65):
            with self.subTest(value=value), self.assertRaises(MODULE.SafetyError):
                MODULE.arguments(base + ['--review-upgrade'] + previous + ['--expected-previous-war-sha256', value])

    def test_upgrade_checksum_mismatch_stops_before_cluster_or_external_commands(self):
        self.enable_upgrade()
        self.args.expected_previous_war_sha256 = '0' * 64
        with patch.object(MODULE, 'require_linux_user'), patch.object(self.runner, 'command') as command, \
                self.assertRaises(MODULE.SafetyError):
            self.runner.prepare()
        command.assert_not_called()
        self.assertFalse(self.runner.lock.exists())

    def test_previous_war_in_cleaned_build_directory_is_rejected(self):
        self.enable_upgrade()
        self.args.previous_war = self.workspace / 'source' / 'target' / 'ai-code-reviewer.war'
        self.args.expected_previous_war_sha256 = MODULE.war_digest(self.args.previous_war)
        with patch.object(MODULE, 'require_linux_user'), patch.object(self.runner, 'command') as command, \
                self.assertRaises(MODULE.SafetyError):
            self.runner.prepare()
        command.assert_not_called()

    def test_war_digest_rejects_empty_or_non_war_input(self):
        self.enable_upgrade()
        self.assertEqual(MODULE.war_digest(self.args.previous_war), self.args.expected_previous_war_sha256)
        self.args.previous_war.write_bytes(b'')
        with self.assertRaises(MODULE.SafetyError):
            MODULE.war_digest(self.args.previous_war)
        other = self.workspace / 'previous.txt'
        other.write_text('synthetic', encoding='utf-8')
        with self.assertRaises(MODULE.SafetyError):
            MODULE.war_digest(other)

    def test_upgrade_port_participates_in_collision_check(self):
        self.enable_upgrade()
        self.assertEqual(self.runner.selected_ports(), [55439, 18093])
        self.args.review_restart = True
        self.args.review_upgrade_port = self.args.review_restart_port
        with self.assertRaises(MODULE.SafetyError):
            self.runner.selected_ports()

    def test_upgrade_passes_owned_identity_and_accepts_only_matching_complete_report(self):
        self.enable_upgrade()
        self.runner.logs.mkdir()
        self.runner.owned = (123, 1800000000)
        observed = []
        def child(command, **kwargs):
            self.assertTrue(kwargs['cooperative_cancel'])
            self.assertEqual(kwargs['timeout'], 1000)
            self.assertEqual(command[command.index('--expected-postmaster-pid') + 1], '123')
            self.assertEqual(command[command.index('--previous-war') + 1], self.args.previous_war)
            data = {'result': 'PASS', 'parentRunToken': self.runner.token, 'ownedDatabasesRemoved': True,
                    'externalServicesUsed': False, 'paidAiUsed': False,
                    'warSha256': command[command.index('--expected-war-sha256') + 1],
                    'previousWarSha256': self.args.expected_previous_war_sha256}
            data.update(overrides)
            path = Path(command[command.index('--report') + 1])
            path.write_text(json.dumps(data), encoding='utf-8')
            observed.append(path)
        for overrides in ({'parentRunToken': '0' * 32}, {'ownedDatabasesRemoved': False},
                          {'externalServicesUsed': True}, {'paidAiUsed': True}, {'result': 'FAIL'},
                          {'warSha256': '0' * 64}, {'previousWarSha256': '0' * 64}, {}):
            with self.subTest(overrides=overrides), patch.object(self.runner, 'verify_owned') as verify, \
                    patch.object(self.runner, 'command', side_effect=child):
                if overrides:
                    with self.assertRaises(MODULE.SafetyError):
                        self.runner.upgrade()
                else:
                    self.runner.upgrade()
                self.assertEqual(verify.call_count, 2)
            observed.pop().unlink()

    def test_upgrade_existing_report_is_preserved_without_child_call(self):
        self.enable_upgrade()
        self.runner.logs.mkdir()
        report = self.runner.logs / ('review-upgrade-' + self.runner.token + '.json')
        report.write_text('previous synthetic evidence', encoding='utf-8')
        with patch.object(self.runner, 'verify_owned'), patch.object(self.runner, 'command') as command, \
                self.assertRaises(MODULE.SafetyError):
            self.runner.upgrade()
        command.assert_not_called()
        self.assertEqual(report.read_text(encoding='utf-8'), 'previous synthetic evidence')

    def test_non_linux_and_root_refused(self):
        with patch.object(MODULE.sys, 'platform', 'win32'), self.assertRaises(MODULE.SafetyError):
            MODULE.require_linux_user()
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.os, 'geteuid', return_value=0, create=True), self.assertRaises(MODULE.SafetyError):
            MODULE.require_linux_user()
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.os, 'geteuid', return_value=1000, create=True):
            MODULE.require_linux_user()

    def test_postgres17_version_accepts_distribution_suffix_but_not_other_major_or_preview(self):
        for value in ('pg_ctl (PostgreSQL) 17.6\n', 'postgres (PostgreSQL) 17\n',
                      'pg_ctl (PostgreSQL) 17.9 (Ubuntu 17.9-1.pgdg24.04+1)\n',
                      'psql (PostgreSQL) 17.6 (Debian 17.6-1.pgdg12+1)\n'):
            with self.subTest(value=value):
                self.assertTrue(MODULE.postgres17_version(value))
        for value in ('pg_ctl (PostgreSQL) 170.1\n', 'postgres (PostgreSQL) 16.9\n',
                      'psql (PostgreSQL) 17beta1\n', 'pg_ctl (PostgreSQL) 17.9\nextra'):
            with self.subTest(value=value):
                self.assertFalse(MODULE.postgres17_version(value))

    def test_native_mount_uses_most_specific_mount_and_rejects_windows_network_or_unknown(self):
        mount = self.workspace.as_posix().replace(' ', r'\040')
        MODULE.native_filesystem(self.workspace, f'1 0 0:1 / {mount} rw - ext4 /dev/test rw')
        for kind in ('9p', 'drvfs', 'cifs', 'ntfs3', 'nfs', 'unknown'):
            with self.subTest(kind=kind), self.assertRaises(MODULE.SafetyError):
                MODULE.native_filesystem(self.workspace, f'1 0 0:1 / {mount} rw - {kind} test rw')
        with self.assertRaises(MODULE.SafetyError):
            MODULE.native_filesystem(self.workspace, '')

    def test_nested_windows_mount_is_rejected_even_when_checkout_is_native(self):
        native = self.workspace.as_posix().replace(' ', r'\040')
        for nested in (self.workspace / '.local', self.runner.cluster):
            point = nested.as_posix().replace(' ', r'\040')
            mountinfo = (f'1 0 0:1 / {native} rw - ext4 /dev/test rw\n'
                         f'2 1 0:2 / {point} rw - 9p drvfs rw\n')
            MODULE.native_filesystem(self.workspace, mountinfo)
            with self.subTest(nested=nested), self.assertRaises(MODULE.SafetyError):
                MODULE.native_filesystem(self.runner.cluster, mountinfo)

    def test_prepare_validates_cluster_and_local_mounts_before_any_tool_call(self):
        checked = []
        def native_only(path, mountinfo):
            checked.append(path)
            if path == self.runner.cluster:
                raise MODULE.SafetyError('synthetic Windows submount')
        with patch.object(MODULE, 'require_linux_user'), patch.object(MODULE, 'native_filesystem', side_effect=native_only), \
                patch.object(Path, 'read_text', return_value='synthetic mount info'), \
                patch.object(self.runner, 'command') as command, self.assertRaises(MODULE.SafetyError):
            self.runner.prepare()
        self.assertEqual(checked, [self.workspace, self.workspace / '.local', self.runner.cluster])
        command.assert_not_called()

    def test_path_rejects_parent_traversal_relative_and_reparse_leaf(self):
        for path in ('relative', str(self.workspace / 'other') + '/../target', '//server/share'):
            with self.subTest(path=path), self.assertRaises(MODULE.SafetyError):
                MODULE.absolute_path(path)
        fake = Mock(st_mode=0o100600, st_file_attributes=0x400)
        with patch.object(Path, 'lstat', return_value=fake), self.assertRaises(MODULE.SafetyError):
            MODULE.checked_path(self.runner.cluster)

    def test_links_on_existing_ancestor_and_leaf_are_rejected(self):
        link = self.workspace / 'linked'
        try:
            link.symlink_to(self.runner.cluster, target_is_directory=True)
        except OSError:
            self.skipTest('Temporary symlink creation unavailable on this platform')
        for path in (link, link / 'PG_VERSION'):
            with self.subTest(path=path), self.assertRaises(MODULE.SafetyError):
                MODULE.checked_path(path)

    def test_marker_platform_version_and_bounded_evidence_are_required(self):
        self.runner.validate_cluster()
        for name in ('reviewer-test-cluster', 'reviewer-test-platform', 'PG_VERSION'):
            file = self.runner.cluster / name
            content = file.read_bytes()
            for invalid in (b'fixture-private-value', b'x' * 16385, b'\xff'):
                file.write_bytes(invalid)
                with self.subTest(name=name), self.assertRaises(MODULE.SafetyError):
                    self.runner.validate_cluster()
            file.unlink()
            with self.assertRaises(MODULE.SafetyError):
                self.runner.validate_cluster()
            file.write_bytes(content)

    def test_pid_identity_checks_all_fields_and_numeric_bounds(self):
        self.write_pid()
        self.assertEqual(self.runner.pid_identity(), (123, 1800000000))
        for changes in ({'pid': 0}, {'pid': 2147483648}, {'started': 0}, {'started': 9223372036854775808},
                        {'port': 55440}, {'directory': str(self.workspace / 'foreign')}, {'listen': '0.0.0.0'}):
            self.write_pid(**changes)
            with self.subTest(changes=changes), self.assertRaises(MODULE.SafetyError):
                self.runner.pid_identity()

    def test_environment_excludes_secrets_overrides_and_enables_no_external_or_load_tests(self):
        inherited = {'HOME': '/synthetic/home', 'PATH': '/untrusted', 'OPENAI_API_KEY': 'fixture-private-value',
                     'RUN_AI_EVALUATION': 'true', 'JAVA_TOOL_OPTIONS': 'fixture-private-value',
                     'PGPASSWORD': 'fixture-private-value', 'MAVEN_OPTS': 'fixture-private-value',
                     'SPRING_APPLICATION_JSON': 'fixture-private-value'}
        with patch.dict(MODULE.os.environ, inherited, clear=True):
            result = MODULE.safe_environment(self.args.java, self.pg_bin, 55439)
            self.assertEqual(dict(MODULE.os.environ), inherited)
        self.assertNotIn('fixture-private-value', str(result))
        for key in MODULE.OPT_INS:
            self.assertEqual(result[key], 'false')
        self.assertEqual(result['TEST_DATABASE_PASSWORD'], '')
        self.assertEqual(result['TEST_DATABASE_URL'], 'jdbc:postgresql://127.0.0.1:55439/reviewer_integration')

    def test_occupied_or_duplicate_ports_are_refused(self):
        with patch.object(MODULE.socket, 'socket') as socket:
            socket.return_value.__enter__.return_value.bind.side_effect = OSError('fixture-private-value')
            with self.assertRaises(MODULE.SafetyError) as failure:
                MODULE.unused_port(55439)
            self.assertNotIn('fixture-private-value', str(failure.exception))
        self.args.review_restart = True
        self.args.review_restart_port = self.args.port
        with self.assertRaises(MODULE.SafetyError):
            self.runner.selected_ports()

    def prepare_with_commands(self, command):
        with patch.object(MODULE, 'require_linux_user'), patch.object(MODULE, 'native_filesystem'), \
                patch.object(Path, 'read_text', return_value='synthetic mount info'), \
                patch.object(MODULE.os, 'access', return_value=True), patch.object(MODULE, 'unused_port'), \
                patch.object(self.runner, 'command', side_effect=command):
            self.runner.prepare()

    def prepare_command(self, command, **kwargs):
        executable = Path(command[0]).name
        if '--version' in command:
            return 0, executable + ' (PostgreSQL) 17.6\n'
        if '-version' in command:
            return 0, 'openjdk version "25.0.1"\n'
        if command[-1] == 'status':
            return 3, ''
        if '-C' in command:
            return 0, '\n'
        raise AssertionError('Unexpected synthetic command')

    def test_prepare_refuses_existing_pid_or_already_running_cluster_without_starting_it(self):
        self.write_pid()
        with self.assertRaises(MODULE.SafetyError):
            self.prepare_with_commands(self.prepare_command)
        self.assertFalse(self.runner.start_attempted)
        self.runner.cleanup()
        self.pid_file.unlink()
        # A second test object needs a fresh per-invocation log directory.
        self.runner = MODULE.TestRun(self.args, self.workspace)
        def running(command, **kwargs):
            return (0, '') if command[-1] == 'status' else self.prepare_command(command, **kwargs)
        with self.assertRaises(MODULE.SafetyError):
            self.prepare_with_commands(running)
        self.assertFalse(self.runner.start_attempted)
        self.runner.cleanup()

    def test_prepare_refuses_wrong_tool_versions_or_existing_unmarked_cluster(self):
        def old_tool(command, **kwargs):
            return 0, 'postgres (PostgreSQL) 16.1\n'
        with self.assertRaises(MODULE.SafetyError):
            self.prepare_with_commands(old_tool)
        (self.runner.cluster / 'reviewer-test-platform').unlink()
        with self.assertRaises(MODULE.SafetyError):
            self.prepare_with_commands(self.prepare_command)
        self.assertFalse(self.runner.start_attempted)
        self.runner.cleanup()

    def test_new_cluster_records_linux_marker_and_persistent_tcp_only_configuration(self):
        for file in self.runner.cluster.iterdir():
            file.unlink()
        self.runner.cluster.rmdir()
        observed = []
        def initialize(command, **kwargs):
            observed.append(command)
            if Path(command[0]).name == 'initdb' and '--version' not in command:
                self.runner.cluster.mkdir()
                (self.runner.cluster / 'PG_VERSION').write_text('17\n', encoding='utf-8')
                (self.runner.cluster / 'postgresql.conf').write_text('# initial fixture\n', encoding='utf-8')
                return 0, ''
            return self.prepare_command(command, **kwargs)
        self.prepare_with_commands(initialize)
        self.assertEqual((self.runner.cluster / 'reviewer-test-platform').read_text(encoding='utf-8').strip(), MODULE.PLATFORM_MARKER)
        config = (self.runner.cluster / 'postgresql.conf').read_text(encoding='utf-8')
        self.assertIn("unix_socket_directories = ''", config)
        self.assertIn("listen_addresses = '127.0.0.1'", config)
        init = next(command for command in observed if '-A' in command)
        self.assertEqual(init[init.index('-A') + 1], 'trust')
        self.assertFalse(self.runner.start_attempted)
        self.runner.cleanup()

    def test_changed_lock_is_preserved_and_never_removed(self):
        self.runner.lock.mkdir()
        self.runner.lock_identity = (-1, -1)
        with self.assertRaises(MODULE.SafetyError):
            self.runner.cleanup()
        self.assertTrue(self.runner.lock.exists())

    def test_verify_runs_clean_build_then_required_pg_gate_with_distinct_logs(self):
        with patch.object(self.runner, 'command', return_value=(0, '')) as command:
            self.runner.verify()
        commands = command.call_args_list
        self.assertEqual([str(part) for part in commands[0].args[0]][-4:], ['-B', '-ntp', 'clean', 'verify'])
        self.assertEqual(commands[0].kwargs['cwd'], self.workspace / 'source')
        self.assertEqual(Path(commands[1].args[0][1]).name, 'verify-test-reports.py')
        self.assertNotEqual(commands[0].kwargs['log'], commands[1].kwargs['log'])

    def test_command_uses_source_cwd_without_duplicate_keyword(self):
        with patch.object(MODULE, 'run_command', return_value=(0, '')) as run_command:
            self.runner.command(['synthetic'], cwd=self.workspace / 'source')
        self.assertEqual(run_command.call_args.kwargs['cwd'], self.workspace / 'source')

    def test_backup_receives_owned_identity_and_requires_matching_cleaned_report(self):
        self.runner.logs.mkdir()
        self.runner.owned = (123, 1800000000)
        observed = []
        def child(command, **kwargs):
            observed.append(command)
            self.assertTrue(kwargs['cooperative_cancel'])
            self.assertEqual(command[command.index('--expected-postmaster-pid') + 1], '123')
            self.assertEqual(command[command.index('--expected-postmaster-started-at') + 1], '1800000000')
            self.assertEqual(command[command.index('--parent-run-token') + 1], self.runner.token)
            report = Path(command[command.index('--report') + 1])
            report.write_text(json.dumps({'result': 'PASS', 'parentRunToken': self.runner.token,
                'restoreDatabaseRemoved': True, 'sourceReadOnly': True, 'externalServicesUsed': False,
                'paidAiUsed': False}), encoding='utf-8')
        with patch.object(self.runner, 'command', side_effect=child), patch.object(self.runner, 'verify_owned') as verify:
            self.runner.backup_restore()
        self.assertEqual(len(observed), 1)
        self.assertEqual(verify.call_count, 2)
        with patch.object(self.runner, 'command') as command, patch.object(self.runner, 'verify_owned'), self.assertRaises(MODULE.SafetyError):
            self.runner.backup_restore()
        command.assert_not_called()

    def test_backup_rejects_foreign_or_incomplete_success_report(self):
        self.runner.logs.mkdir()
        self.runner.owned = (123, 1800000000)
        for changed in ({'parentRunToken': 'foreign'}, {'restoreDatabaseRemoved': False}, {'sourceReadOnly': False}, {'result': 'FAIL'}):
            report = self.runner.logs / ('postgres-backup-' + self.runner.token + '.json')
            def child(command, **kwargs):
                outcome = {'result': 'PASS', 'parentRunToken': self.runner.token, 'restoreDatabaseRemoved': True,
                           'sourceReadOnly': True, 'externalServicesUsed': False, 'paidAiUsed': False}
                outcome.update(changed)
                report.write_text(json.dumps(outcome), encoding='utf-8')
            with patch.object(self.runner, 'command', side_effect=child), patch.object(self.runner, 'verify_owned'), self.assertRaises(MODULE.SafetyError):
                self.runner.backup_restore()
            report.unlink()

    def test_backup_flag_runs_after_required_pg_gate_and_before_war_drills(self):
        self.args.backup_restore = True
        self.args.review_restart = True
        events = []
        with patch.object(self.runner, 'command', side_effect=lambda *a, **k: events.append('build/gate')), \
                patch.object(self.runner, 'backup_restore', side_effect=lambda: events.append('backup')), \
                patch.object(self.runner, 'drill', side_effect=lambda *a: events.append('war')):
            self.runner.verify()
        self.assertEqual(events, ['build/gate', 'build/gate', 'backup', 'war'])

    def test_cleanup_stops_only_matching_pid_and_start_then_removes_own_lock(self):
        self.write_pid()
        self.runner.logs.mkdir()
        self.runner.lock.mkdir()
        self.runner.lock_identity = self.runner.identity(self.runner.lock)
        self.runner.start_attempted, self.runner.owned = True, (123, 1800000000)
        server = {'directory': str(self.runner.cluster), 'port': 55439, 'listen': '127.0.0.1', 'pid': 123, 'started': 1800000000}
        with patch.object(self.runner, 'sql', return_value=json.dumps(server)), patch.object(self.runner, 'command') as command:
            command.side_effect = lambda *args, **kwargs: self.pid_file.unlink()
            self.runner.cleanup()
        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[0][-1], 'stop')
        self.assertFalse(self.runner.lock.exists())

    def test_cleanup_refuses_missing_ownership_changed_pid_start_or_replaced_marker(self):
        for identity in (None, (124, 1800000000), (123, 1800000001)):
            self.write_pid()
            self.runner.start_attempted, self.runner.owned = True, identity
            with patch.object(self.runner, 'command') as command, self.subTest(identity=identity), self.assertRaises(MODULE.SafetyError):
                self.runner.cleanup()
            command.assert_not_called()
        self.runner.cluster_identity = self.runner.identity(self.runner.cluster)
        self.runner.marker_identity = (-1, -1)
        with self.assertRaises(MODULE.SafetyError):
            self.runner.validate_cluster()

    def test_errors_and_keyboard_interrupt_still_enter_ownership_checked_cleanup(self):
        for error in (MODULE.SafetyError('synthetic'), KeyboardInterrupt()):
            with patch.object(self.runner, 'prepare'), patch.object(self.runner, 'start'), patch.object(self.runner, 'databases'), \
                    patch.object(self.runner, 'verify', side_effect=error), patch.object(self.runner, 'cleanup') as cleanup:
                with self.assertRaises(type(error)):
                    self.runner.execute()
                cleanup.assert_called_once()

    def test_handoff_requires_invocation_token_bounded_integer_identity_and_server_match(self):
        report = self.workspace / 'report.json'
        cases = ({'parentRunToken': 'other', 'parentOwnedPostmaster': {'pid': 200, 'startedAt': 1800000001}},
                 {'parentRunToken': self.runner.token, 'parentOwnedPostmaster': {'pid': True, 'startedAt': 1800000001}},
                 {'parentRunToken': self.runner.token, 'parentOwnedPostmaster': {'pid': 200, 'startedAt': 0}})
        for data in cases:
            report.write_text(json.dumps(data), encoding='utf-8')
            with patch.object(self.runner, 'verify_owned') as verify, self.assertRaises(MODULE.SafetyError):
                self.runner.adopt_handoff(report, self.runner.token)
            verify.assert_not_called()
        data = {'parentRunToken': self.runner.token, 'parentOwnedPostmaster': {'pid': 200, 'startedAt': 1800000001}}
        report.write_text(json.dumps(data), encoding='utf-8')
        with patch.object(self.runner, 'verify_owned', side_effect=MODULE.SafetyError('mismatch')), self.assertRaises(MODULE.SafetyError):
            self.runner.adopt_handoff(report, self.runner.token)
        self.assertIsNone(self.runner.owned)
        with patch.object(self.runner, 'verify_owned') as verify:
            self.runner.adopt_handoff(report, self.runner.token)
        verify.assert_called_once_with((200, 1800000001))
        self.assertEqual(self.runner.owned, (200, 1800000001))

    def test_failed_child_can_handoff_verified_restored_identity_before_parent_cleanup(self):
        self.runner.logs.mkdir()
        self.runner.owned = (123, 1800000000)
        def failed_child(command, **kwargs):
            self.assertTrue(kwargs['cooperative_cancel'])
            report = Path(command[command.index('--report') + 1])
            report.write_text(json.dumps({'parentRunToken': self.runner.token,
                'parentOwnedPostmaster': {'pid': 200, 'startedAt': 1800000001}}), encoding='utf-8')
            self.assertIn('--expected-postmaster-started-at', command)
            raise MODULE.SafetyError('failed synthetic scenario')
        with patch.object(self.runner, 'verify_owned'), patch.object(self.runner, 'command', side_effect=failed_child), self.assertRaises(MODULE.SafetyError):
            self.runner.drill('review-db-recovery', ['--port', '18092'])
        self.assertEqual(self.runner.owned, (200, 1800000001))

    def test_mismatched_connected_server_prevents_cluster_stop(self):
        self.write_pid()
        self.runner.start_attempted, self.runner.owned = True, (123, 1800000000)
        foreign = {'directory': str(self.workspace / 'foreign'), 'port': 55439,
                   'listen': '127.0.0.1', 'pid': 123, 'started': 1800000000}
        with patch.object(self.runner, 'sql', return_value=json.dumps(foreign)), \
                patch.object(self.runner, 'command') as command, self.assertRaises(MODULE.SafetyError):
            self.runner.cleanup()
        command.assert_not_called()

    def test_child_report_is_unique_and_preexisting_report_is_never_overwritten(self):
        self.runner.logs.mkdir()
        report = self.runner.logs / ('review-restart-' + self.runner.token + '.json')
        report.write_text('preserve fixture', encoding='utf-8')
        with patch.object(self.runner, 'command') as command, self.assertRaises(MODULE.SafetyError):
            self.runner.drill('review-restart', ['--port', '18089'])
        command.assert_not_called()
        self.assertEqual(report.read_text(encoding='utf-8'), 'preserve fixture')

    def test_child_timeout_terminates_only_owned_process_group_and_hides_output(self):
        process = Mock(pid=12345)
        process.communicate.side_effect = [subprocess.TimeoutExpired(['synthetic'], 1), ('', None)]
        process.poll.return_value = None
        with patch.object(MODULE.subprocess, 'Popen', return_value=process) as popen, \
                patch.object(MODULE.os, 'killpg', create=True) as killpg, self.assertRaises(MODULE.SafetyError):
            MODULE.run_command(['synthetic'], environment={}, cwd=self.workspace, capture=True, timeout=1)
        self.assertTrue(popen.call_args.kwargs['start_new_session'])
        killpg.assert_called_once_with(12345, MODULE.signal.SIGTERM)

    def test_python_drill_interrupt_allows_finally_cleanup_then_still_raises_interrupt(self):
        process = Mock(pid=12345)
        process.communicate.side_effect = [KeyboardInterrupt(), ('', None)]
        process.poll.return_value = None
        with patch.object(MODULE.subprocess, 'Popen', return_value=process), \
                patch.object(MODULE.os, 'kill') as kill, patch.object(MODULE.os, 'killpg', create=True) as killpg, \
                self.assertRaises(KeyboardInterrupt):
            MODULE.run_command(['synthetic-python-drill'], environment={}, cwd=self.workspace,
                               capture=True, cooperative_cancel=True)
        kill.assert_called_once_with(12345, MODULE.signal.SIGINT)
        killpg.assert_not_called()
        self.assertEqual(process.communicate.call_args_list[-1].kwargs['timeout'], 60)

    def test_python_drill_timeout_allows_cleanup_without_becoming_success(self):
        process = Mock(pid=12345)
        process.communicate.side_effect = [subprocess.TimeoutExpired(['synthetic'], 1), ('', None)]
        process.poll.return_value = None
        with patch.object(MODULE.subprocess, 'Popen', return_value=process), \
                patch.object(MODULE.os, 'kill') as kill, patch.object(MODULE.os, 'killpg', create=True) as killpg, \
                self.assertRaises(MODULE.SafetyError):
            MODULE.run_command(['synthetic-python-drill'], environment={}, cwd=self.workspace,
                               capture=True, cooperative_cancel=True, timeout=1)
        kill.assert_called_once_with(12345, MODULE.signal.SIGINT)
        killpg.assert_not_called()

    def test_unresponsive_drill_escalates_only_after_bounded_cooperative_cleanup(self):
        process = Mock(pid=12345)
        process.communicate.side_effect = [KeyboardInterrupt(), subprocess.TimeoutExpired(['synthetic'], 60), ('', None)]
        process.poll.return_value = None
        events = []
        with patch.object(MODULE.subprocess, 'Popen', return_value=process), \
                patch.object(MODULE.os, 'kill', side_effect=lambda *_: events.append('interrupt-child')), \
                patch.object(MODULE.os, 'killpg', side_effect=lambda *_: events.append('terminate-owned-group'), create=True), \
                self.assertRaises(KeyboardInterrupt):
            MODULE.run_command(['synthetic-python-drill'], environment={}, cwd=self.workspace,
                               capture=True, cooperative_cancel=True)
        self.assertEqual(events, ['interrupt-child', 'terminate-owned-group'])
        self.assertEqual([call.kwargs['timeout'] for call in process.communicate.call_args_list], [60, 60, 5])

    def test_main_sigterm_enters_finally_cleanup_and_restores_prior_signal_handler(self):
        installed = []
        prior = Mock()
        def interrupt_during_verify():
            installed[0][1](MODULE.signal.SIGTERM, None)
        with patch.object(MODULE, 'require_linux_user'), patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE, 'TestRun', return_value=self.runner), \
                patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                patch.object(self.runner, 'prepare'), patch.object(self.runner, 'start'), patch.object(self.runner, 'databases'), \
                patch.object(self.runner, 'verify', side_effect=interrupt_during_verify), \
                patch.object(self.runner, 'cleanup') as cleanup, patch('builtins.print'):
            self.assertEqual(MODULE.main([]), 130)
        cleanup.assert_called_once()
        self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))

    def test_main_restores_prior_signal_handler_after_success(self):
        prior = Mock()
        with patch.object(MODULE, 'require_linux_user'), patch.object(MODULE, 'arguments', return_value=self.args), \
                patch.object(MODULE, 'TestRun') as runner, patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                patch.object(MODULE.signal, 'signal') as install, patch('builtins.print'):
            self.assertEqual(MODULE.main([]), 0)
        runner.return_value.execute.assert_called_once()
        self.assertEqual(install.call_args.args, (MODULE.signal.SIGTERM, prior))


if __name__ == '__main__':
    unittest.main()
