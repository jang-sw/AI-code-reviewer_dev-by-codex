"""Upgrade drill boundary fixtures; no WAR, PostgreSQL, socket or child is started."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import types
import unittest
import warnings
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_upgrade',
    Path(__file__).resolve().parents[1] / 'verify-review-upgrade.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
SOURCE_URL = 'jdbc:postgresql://127.0.0.1:55439/reviewer_integration'


class UpgradeDrillBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reviewer-upgrade-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        self.pg_bin = self.workspace / 'pg-bin'
        self.pg_bin.mkdir()
        (self.pg_bin / 'psql').touch()
        self.java = self.workspace / 'java'
        self.java.touch()
        self.old_war, old_hash = self.war('old.war', 12)
        self.new_war, new_hash = self.war('new.war', 13)
        self.args = types.SimpleNamespace(war=self.new_war, previous_war=self.old_war,
            java=self.java, pg_bin=self.pg_bin, psql=self.pg_bin / 'psql',
            expected_war_sha256=new_hash, expected_previous_war_sha256=old_hash,
            expected_postmaster_pid=1234, expected_postmaster_started_at=1800000000,
            parent_run_token='b' * 32, report=self.workspace / '.local' / 'result.json',
            port=18093, timeout_seconds=300, startup_timeout_seconds=45)
        for context in (patch.object(MODULE.BACKUP, 'WORKSPACE', self.workspace),
                        patch.dict(os.environ, {'TEST_DATABASE_URL': SOURCE_URL,
                            'TEST_DATABASE_USERNAME': 'reviewer_test', 'TEST_DATABASE_PASSWORD': ''}, clear=True)):
            context.start()
            self.addCleanup(context.stop)

    def war(self, name, maximum, *, extra=(), omit=()):
        path = self.workspace / name
        names = ['org/springframework/boot/loader/launch/WarLauncher.class', 'WEB-INF/jsp/login.jsp']
        names += [f'WEB-INF/classes/db/migration/V{version}__fixture.sql' for version in range(1, maximum + 1)]
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'w') as archive:
                for entry in [name for name in names if name not in omit] + list(extra):
                    archive.writestr(entry, b'synthetic fixture only')
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def argv(self, **changes):
        values = vars(self.args) | changes
        return [part for key, value in values.items() for part in ('--' + key.replace('_', '-'), str(value))]

    def owner(self, letter='c'):
        owner = Mock(target='reviewer_restore_' + letter * 32, port=55439, created_here=True)
        owner.sql.return_value = '0'
        return owner

    @staticmethod
    def report():
        return {'result': 'FAIL', 'checks': {}}

    def test_war_hash_and_exact_migration_boundary_are_both_required(self):
        self.assertEqual(self.args.expected_previous_war_sha256,
                         MODULE.verify_war(self.old_war, self.args.expected_previous_war_sha256, 12))
        self.assertEqual(self.args.expected_war_sha256,
                         MODULE.verify_war(self.new_war, self.args.expected_war_sha256, 13))
        for path, digest, maximum in ((self.old_war, self.args.expected_previous_war_sha256, 13),
                                      (self.new_war, self.args.expected_war_sha256, 12),
                                      (self.new_war, '0' * 64, 13)):
            with self.subTest(path=path.name, maximum=maximum), self.assertRaises(MODULE.VerificationError):
                MODULE.verify_war(path, digest, maximum)

    def test_war_duplicate_missing_and_unexpected_resources_are_rejected(self):
        prefix = 'WEB-INF/classes/db/migration/'
        variants = [dict(extra=(prefix + 'V12__fixture.sql',)),
                    dict(extra=(prefix + 'V12__another.sql',)),
                    dict(extra=(prefix + 'nested/V1__unexpected.sql',)),
                    dict(omit=(prefix + 'V4__fixture.sql',)),
                    dict(omit=('org/springframework/boot/loader/launch/WarLauncher.class',)),
                    dict(omit=('WEB-INF/jsp/login.jsp',))]
        for index, options in enumerate(variants):
            path, digest = self.war(f'invalid-{index}.war', 12, **options)
            with self.subTest(options=options), self.assertRaises(MODULE.VerificationError):
                MODULE.verify_war(path, digest, 12)

    def test_cli_accepts_uppercase_hashes_and_rejects_invalid_hash_before_socket(self):
        with patch.object(MODULE.os, 'access', return_value=True), patch.object(MODULE.socket, 'socket'):
            args = MODULE.arguments(self.argv(expected_war_sha256=self.args.expected_war_sha256.upper(),
                expected_previous_war_sha256=self.args.expected_previous_war_sha256.upper()))
        self.assertEqual(args.expected_war_sha256, self.args.expected_war_sha256)
        self.assertEqual(args.expected_previous_war_sha256, self.args.expected_previous_war_sha256)
        for field in ('expected_war_sha256', 'expected_previous_war_sha256'):
            for value in ('a' * 63, 'g' * 64, 'a' * 64 + '\n'):
                with self.subTest(field=field, value=value), patch.object(MODULE.socket, 'socket') as socket, \
                        self.assertRaises(MODULE.VerificationError):
                    MODULE.arguments(self.argv(**{field: value}))
                socket.assert_not_called()

    def test_existing_or_outside_report_is_rejected_before_socket(self):
        self.args.report.write_text('preserve existing evidence', encoding='utf-8')
        for path in (self.args.report, self.workspace / 'outside.json'):
            with self.subTest(path=path.name), patch.object(MODULE.os, 'access', return_value=True), \
                    patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(report=path))
            socket.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'preserve existing evidence')

    def test_database_targets_owned_db_and_rechecks_ownership_before_each_sql(self):
        owner = self.owner()
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'synthetic-private-value',
                                   'JAVA_TOOL_OPTIONS': 'synthetic-private-value'}):
            database = MODULE.UpgradeDatabase(self.args.psql, SOURCE_URL, self.workspace, owner)
        self.assertEqual(database.url, SOURCE_URL.rsplit('/', 1)[0] + '/' + owner.target)
        self.assertEqual(database.command[database.command.index('-d') + 1], owner.target)
        self.assertNotIn('synthetic-private-value', str(database.environment))
        events = []
        owner.verify_target.side_effect = lambda: events.append('verified')
        with patch.object(MODULE.HELPERS.Database, 'sql', side_effect=lambda _: events.append('sql') or 'ok'):
            self.assertEqual(database.sql('SELECT 1;'), 'ok')
        self.assertEqual(events, ['verified', 'sql'])

    def test_database_rejects_foreign_target_changed_owner_and_expired_deadline(self):
        for changed in ({'target': 'reviewer_integration'}, {'port': 55440}):
            owner = self.owner()
            for name, value in changed.items():
                setattr(owner, name, value)
            with self.subTest(changed=changed), self.assertRaises(MODULE.VerificationError):
                MODULE.UpgradeDatabase(self.args.psql, SOURCE_URL, self.workspace, owner)
        owner = self.owner()
        database = MODULE.UpgradeDatabase(self.args.psql, SOURCE_URL, self.workspace, owner)
        owner.verify_target.side_effect = MODULE.BACKUP.SafetyError('ownership changed')
        with patch.object(MODULE.HELPERS.Database, 'sql') as sql, self.assertRaises(MODULE.BACKUP.SafetyError):
            database.sql('DELETE FROM synthetic;')
        sql.assert_not_called()
        owner.verify_target.reset_mock(side_effect=True)
        database.deadline = 10
        with patch.object(MODULE.time, 'monotonic', return_value=10), \
                patch.object(MODULE.HELPERS.Database, 'sql') as sql, self.assertRaises(MODULE.VerificationError):
            database.sql('SELECT 1;')
        sql.assert_not_called()
        owner.verify_target.assert_not_called()

    def test_migration_and_fingerprint_probes_retain_legacy_checksums_and_columns(self):
        database = Mock()
        rows = [{'version': str(value), 'checksum': value * 11, 'success': True} for value in range(1, 13)]
        database.sql.return_value = json.dumps(rows)
        self.assertEqual(MODULE.migration_snapshot(database, SCHEMA, 12), rows)
        for bad in (rows[:-1], list(reversed(rows)), rows[:-1] + [rows[-1] | {'success': False}]):
            database.sql.return_value = json.dumps(bad)
            with self.subTest(rows=bad), self.assertRaises(MODULE.VerificationError):
                MODULE.migration_snapshot(database, SCHEMA, 12)
        database.sql.reset_mock()
        database.sql.return_value = '2:' + 'a' * 32
        values = MODULE.legacy_fingerprints(database, SCHEMA)
        self.assertEqual(set(values), set(MODULE.TABLES))
        queries = [call.args[0] for call in database.sql.call_args_list]
        run_query = next(query for query in queries if '.review_run)' in query)
        self.assertIn('SELECT ' + MODULE.OLD_RUN_COLUMNS + ' FROM', run_query)
        self.assertNotIn('progress_stage', run_query)
        self.assertEqual(set(MODULE.application_fingerprints(values)), set(MODULE.TABLES) - {'flyway_schema_history'})
        database.sql.reset_mock()
        with self.assertRaises(MODULE.VerificationError):
            MODULE.legacy_fingerprints(database, 'public')
        database.sql.assert_not_called()

    def test_recovery_rejects_changed_request_claim_duplicates_and_borrowed_progress(self):
        frozen = {'request': {'requestId': 'same-request', 'actor': 7, 'source': 'MANUAL',
                             'attempts': 1, 'runId': 20, 'token': 'old'}}
        current = {'request': frozen['request'] | {'state': 'SUCCEEDED', 'attempts': 2, 'runId': 21, 'token': 'new'},
                   'commits': [MODULE.HELPERS.SHA_A, MODULE.HELPERS.SHA_B], 'issues': 2, 'assignees': [7, 7],
                   'cursor': MODULE.HELPERS.SHA_B,
                   'runs': [{'state': 'FAILED', 'commits': 1}, {'state': 'SUCCEEDED', 'commits': 1}]}
        MODULE.assert_recovered(current, frozen)
        changes = [('request', 'requestId', 'foreign'), ('request', 'actor', 8), ('request', 'source', 'SCHEDULED'),
                   ('request', 'attempts', 1), ('request', 'runId', 20), ('request', 'token', 'old'),
                   ('root', 'commits', [MODULE.HELPERS.SHA_A] * 2), ('root', 'issues', 3),
                   ('root', 'assignees', [7, 8]), ('root', 'cursor', MODULE.HELPERS.SHA_A),
                   ('root', 'runs', [{'state': 'FAILED', 'commits': 1}, {'state': 'SUCCEEDED', 'commits': 2}])]
        for location, key, value in changes:
            changed = copy.deepcopy(current)
            (changed['request'] if location == 'request' else changed)[key] = value
            with self.subTest(location=location, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_recovered(changed, frozen)

    def test_cleanup_never_drops_databases_while_owned_war_remains_alive(self):
        war, fixture = Mock(), Mock()
        war.process.poll.return_value = None
        owners = [self.owner('c'), self.owner('d')]
        self.assertFalse(MODULE.cleanup_owned(war, fixture, owners))
        war.stop.assert_called_once_with(force=True)
        fixture.close.assert_called_once()
        for owner in owners:
            owner.cleanup.assert_not_called()
            owner.verify_target.assert_not_called()

    def test_cleanup_attempts_other_owner_but_refuses_changed_owner_or_active_connections(self):
        for failure in ('ownership', 'connection', 'drop'):
            first, second = self.owner('c'), self.owner('d')
            if failure == 'ownership':
                second.verify_target.side_effect = MODULE.BACKUP.SafetyError('changed owner')
            elif failure == 'connection':
                second.sql.return_value = '1'
            else:
                second.cleanup.side_effect = OSError('synthetic private cleanup detail')
            with self.subTest(failure=failure):
                self.assertFalse(MODULE.cleanup_owned(None, None, [first, second]))
            first.cleanup.assert_called_once()
            if failure != 'drop':
                second.cleanup.assert_not_called()
        fixture = Mock()
        fixture.close.side_effect = OSError('synthetic fixture failure')
        owner = self.owner()
        self.assertFalse(MODULE.cleanup_owned(None, fixture, [owner]))
        owner.cleanup.assert_called_once()

    def test_interrupted_setup_preserves_original_failure_and_evidence_when_cleanup_also_fails(self):
        first, second = self.owner('c'), self.owner('d')
        first.created_here = second.created_here = False
        interrupted = KeyboardInterrupt()
        second.prepare.side_effect = interrupted
        first.cleanup.side_effect = OSError('synthetic private cleanup detail')
        report = self.report()
        with patch.object(MODULE.BACKUP, 'BackupDrill', side_effect=[first, second]), \
                self.assertRaises(KeyboardInterrupt) as failure:
            MODULE.run_scenario(self.args, report)
        self.assertIs(failure.exception, interrupted)
        first.cleanup.assert_called_once()
        second.cleanup.assert_called_once()
        first.create_target.assert_not_called()
        second.create_target.assert_not_called()
        self.assertTrue(report['cleanupFailed'])
        self.assertFalse(report['ownedDatabasesRemoved'])
        self.assertTrue(list(Path(report['logDirectory']).glob('owned-work-*')))
        self.assertFalse(self.args.report.exists())

    def test_source_change_during_failed_scenario_prevents_clean_report_without_replacing_first_error(self):
        first, second = self.owner('c'), self.owner('d')
        first.created_here = second.created_here = False
        first.fingerprints.side_effect = [{'app_user': 'original'}, {'app_user': 'changed'}]
        original = MODULE.BACKUP.SafetyError('synthetic create failure')
        first.create_target.side_effect = original
        report = self.report()
        with patch.object(MODULE.BACKUP, 'BackupDrill', side_effect=[first, second]), \
                self.assertRaises(MODULE.BACKUP.SafetyError) as failure:
            MODULE.run_scenario(self.args, report)
        self.assertIs(failure.exception, original)
        first.cleanup.assert_called_once()
        second.cleanup.assert_called_once()
        first.verify_owned.assert_called_once()
        self.assertFalse(report['checks']['ownedWarsAndFixtureStoppedAndBothDatabasesRemoved'])
        self.assertFalse(report['ownedDatabasesRemoved'])
        self.assertTrue(report['cleanupFailed'])

    def test_main_preserves_interrupt_exit_restores_handler_and_never_promotes_incomplete_cleanup(self):
        for mode in ('sigterm', 'incomplete', 'private-error', 'success'):
            installed = []
            prior = Mock()
            def scenario(args, report):
                if mode == 'sigterm':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                if mode == 'private-error':
                    raise RuntimeError('synthetic-private-response-and-password')
                report['checks']['ownedWarsAndFixtureStoppedAndBothDatabasesRemoved'] = mode == 'success'
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE.LINUX, 'require_linux_user'), \
                    patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run_scenario', side_effect=scenario), \
                    patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                result = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(result, 130 if mode == 'sigterm' else 0 if mode == 'success' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertEqual(report['parentRunToken'], self.args.parent_run_token)
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.assertNotIn('synthetic-private-response-and-password', str(report) + output.getvalue())
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.args.report.unlink()

    def test_report_is_created_exclusively_and_never_follows_a_link(self):
        MODULE.save_report(self.args.report, {'result': 'FAIL'})
        before = self.args.report.read_bytes()
        if os.name != 'nt':
            self.assertEqual(stat.S_IMODE(self.args.report.stat().st_mode), 0o600)
        with self.assertRaises(FileExistsError):
            MODULE.save_report(self.args.report, {'result': 'PASS'})
        self.assertEqual(self.args.report.read_bytes(), before)
        # Reparse fixture also covers Windows without requiring symlink privileges.
        fake = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_file_attributes=0x400)
        with patch.object(Path, 'lstat', return_value=fake), self.assertRaises(MODULE.BACKUP.SafetyError):
            MODULE.save_report(self.workspace / '.local' / 'linked.json', {'result': 'PASS'})
        self.assertEqual(self.args.report.read_bytes(), before)

    def test_invalid_war_prevents_any_database_or_process_setup(self):
        self.args.expected_previous_war_sha256 = '0' * 64
        report = self.report()
        with patch.object(MODULE.BACKUP, 'BackupDrill') as database, \
                patch.object(MODULE.HELPERS, 'OwnedWar') as war, patch.object(MODULE.HELPERS, 'Fixture') as fixture, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.run_scenario(self.args, report)
        database.assert_not_called()
        war.assert_not_called()
        fixture.assert_not_called()
        self.assertFalse(report['ownedDatabasesRemoved'])
        self.assertFalse(self.args.report.exists())


if __name__ == '__main__':
    unittest.main()
