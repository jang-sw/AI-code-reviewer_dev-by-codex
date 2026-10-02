#!/usr/bin/env python3
"""Synthetic Linux PostgreSQL backup drill; never restore into an existing database.

Run only against the parent-owned native Linux .local/pg-validation cluster.
The source is fixed to reviewer_integration. Only an invocation-created UUID
database is writable, and its OID/owner/comment are rechecked before removal.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import sys
import uuid
from datetime import datetime, timezone


SPEC = importlib.util.spec_from_file_location('linux_postgres_safety', Path(__file__).with_name('test-postgres-linux.py'))
LINUX = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LINUX)
SafetyError, require = LINUX.SafetyError, LINUX.require
WORKSPACE = Path(__file__).absolute().parent.parent
SOURCE = 'reviewer_integration'
TABLES = ('app_user', 'project', 'review_request', 'review_run', 'reviewed_commit', 'review_issue',
          'manual_review_file', 'audit_event', 'git_author_mapping', 'flyway_schema_history')
DB_PATTERN = re.compile(r'jdbc:postgresql://127\.0\.0\.1:([0-9]{4,5})/reviewer_integration')
RESTORE_PATTERN = re.compile(r'reviewer_restore_[a-f0-9]{32}')


class BackupDrill:
    def __init__(self, args, workspace=None):
        self.args = args
        self.workspace = LINUX.absolute_path(workspace or WORKSPACE)
        self.cluster = self.workspace / '.local' / 'pg-validation'
        self.pg_bin = LINUX.absolute_path(args.pg_bin)
        match = DB_PATTERN.fullmatch(os.environ.get('TEST_DATABASE_URL', ''))
        require(match is not None and os.environ.get('TEST_DATABASE_USERNAME') == 'reviewer_test'
                and os.environ.get('TEST_DATABASE_PASSWORD', '') == '',
                'Only the isolated loopback test database and test account are accepted.')
        self.port = int(match[1])
        require(1024 <= self.port <= 65535, 'Invalid isolated PostgreSQL port.')
        require(type(args.expected_postmaster_pid) is int and 0 < args.expected_postmaster_pid <= 2147483647
                and type(args.expected_postmaster_started_at) is int
                and 0 < args.expected_postmaster_started_at <= 9223372036854775807,
                'Explicit parent postmaster PID and start time are required.')
        require(re.fullmatch(r'[a-f0-9]{32}', args.parent_run_token) is not None,
                'An exact parent invocation token is required.')
        self.expected = (args.expected_postmaster_pid, args.expected_postmaster_started_at)
        self.token = uuid.uuid4().hex
        self.target = 'reviewer_restore_' + self.token
        self.comment = 'isolated-backup-' + self.token
        self.logs = self.workspace / '.local' / ('postgres-backup-' + self.token)
        self.dump = self.logs / 'database.dump'
        self.report = LINUX.absolute_path(args.report)
        require(self.workspace / '.local' in self.report.parents,
                'The backup report must be inside this checkout\'s local evidence directory.')
        self.environment = {'PATH': str(self.pg_bin) + ':/usr/bin:/bin', 'LANG': 'C.UTF-8',
                            'PGCONNECT_TIMEOUT': '5', 'PGSSLMODE': 'disable',
                            'PGOPTIONS': '-c statement_timeout=30000 -c lock_timeout=10000',
                            'PGPASSFILE': str(self.logs / 'unused-pgpass'),
                            'PGSERVICEFILE': str(self.logs / 'unused-pgservice')}
        self.path_identity = None
        self.marker_identity = None
        self.created_here = False
        self.database_oid = None
        self.connection = ['-w', '-h', '127.0.0.1', '-p', str(self.port), '-U', 'reviewer_test']

    @staticmethod
    def identity(path):
        value = path.lstat()
        return value.st_dev, value.st_ino

    def command(self, name, options, *, capture=False, readonly=False, timeout=120):
        environment = dict(self.environment)
        if readonly:
            environment['PGOPTIONS'] += ' -c default_transaction_read_only=on'
        return LINUX.run_command([self.pg_bin / name, *options], environment=environment,
                                 cwd=self.workspace, capture=capture, timeout=timeout)[1]

    def sql(self, database, query, *, readonly=True):
        require(database in ('postgres', SOURCE, self.target), 'Unexpected backup drill database.')
        require(readonly or database == self.target or (database == 'postgres'
                and query == 'COMMENT ON DATABASE ' + self.target + " IS '" + self.comment + "';"),
                'Writes are restricted to the invocation-created restore database.')
        return self.command('psql', [*self.connection, '-X', '-At', '-v', 'ON_ERROR_STOP=1',
                                    '-d', database, '-c', query], capture=True, readonly=readonly).strip()

    def validate_directory(self):
        LINUX.checked_path(self.workspace, directory=True)
        LINUX.checked_path(self.cluster, directory=True)
        require(LINUX.limited_text(self.cluster / 'reviewer-test-cluster').strip() == LINUX.MARKER
                and LINUX.limited_text(self.cluster / 'reviewer-test-platform').strip() == LINUX.PLATFORM_MARKER
                and LINUX.limited_text(self.cluster / 'PG_VERSION').strip() == '17',
                'The native Linux test cluster marker or version is invalid.')
        if self.path_identity is not None:
            require(self.identity(self.cluster) == self.path_identity
                    and self.identity(self.cluster / 'reviewer-test-cluster') == self.marker_identity,
                    'The owned test cluster directory or marker was replaced.')

    def verify_owned(self):
        self.validate_directory()
        lines = LINUX.limited_text(self.cluster / 'postmaster.pid').splitlines()
        require(len(lines) >= 6 and all(re.fullmatch(r'[0-9]+', lines[index]) for index in (0, 2, 3)),
                'Invalid isolated PostgreSQL PID record.')
        require((int(lines[0]), int(lines[2])) == self.expected and int(lines[3]) == self.port
                and LINUX.absolute_path(lines[1]) == self.cluster and lines[5] == '127.0.0.1',
                'The isolated PostgreSQL process identity changed.')
        raw = self.sql('postgres', "SELECT json_build_object('directory',current_setting('data_directory'),"
            "'port',current_setting('port'),'listen',current_setting('listen_addresses'),"
            "'pid',split_part(pg_read_file('postmaster.pid',0,16384),chr(10),1),"
            "'started',extract(epoch FROM pg_postmaster_start_time())::bigint,"
            "'user',current_user,'version',current_setting('server_version_num'));")
        try:
            server = json.loads(raw)
            matched = (LINUX.absolute_path(server['directory']) == self.cluster and int(server['port']) == self.port
                       and server['listen'] == '127.0.0.1' and int(server['pid']) == self.expected[0]
                       and abs(int(server['started']) - self.expected[1]) <= 1
                       and server['user'] == 'reviewer_test' and 170000 <= int(server['version']) < 180000)
        except (ValueError, TypeError, KeyError):
            matched = False
        require(matched, 'Connected PostgreSQL does not match the parent-owned test cluster.')

    def prepare(self):
        LINUX.require_linux_user()
        self.validate_directory()
        mountinfo = Path('/proc/self/mountinfo').read_text(encoding='utf-8')
        for path in (self.workspace, self.workspace / '.local', self.cluster):
            LINUX.native_filesystem(path, mountinfo)
        self.path_identity = self.identity(self.cluster)
        self.marker_identity = self.identity(self.cluster / 'reviewer-test-cluster')
        LINUX.checked_path(self.pg_bin, directory=True)
        for name in ('psql', 'pg_dump', 'pg_restore', 'createdb', 'dropdb'):
            require(os.access(LINUX.checked_path(self.pg_bin / name), os.X_OK), 'A PostgreSQL executable is unavailable.')
            version = self.command(name, ['--version'], capture=True)
            require(LINUX.postgres17_version(version), 'All backup tools must be PostgreSQL 17.')
        LINUX.checked_path(self.report, allow_missing=True)
        LINUX.checked_path(self.report.parent, directory=True)
        require(not self.report.exists(), 'Refusing an existing backup report.')
        LINUX.checked_path(self.logs, directory=True, allow_missing=True)
        self.logs.mkdir(mode=0o700)
        self.verify_owned()

    def fingerprints(self, database):
        results = {}
        for table in TABLES:
            value = self.sql(database, "SELECT count(*)::text || ':' || md5(coalesce("
                "string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text), '')) FROM public." + table + ' t;')
            require(re.fullmatch(r'[0-9]+:[a-f0-9]{32}', value) is not None,
                    'Invalid isolated table comparison result.')
            results[table] = value
        return results

    def target_identity(self):
        require(RESTORE_PATTERN.fullmatch(self.target) is not None, 'Invalid generated restore target.')
        # PostgreSQL renders the oid type as JSON text; use an explicit numeric type.
        raw = self.sql('postgres', "SELECT json_build_object('oid',d.oid::bigint,'owner',r.rolname,"
            "'comment',shobj_description(d.oid,'pg_database')) FROM pg_database d "
            "JOIN pg_roles r ON r.oid=d.datdba WHERE datname='" + self.target + "';")
        if not raw:
            return None
        try:
            data = json.loads(raw)
            require(type(data.get('oid')) is int and data['oid'] > 0 and data.get('owner') == 'reviewer_test'
                    and 'comment' in data and (data['comment'] is None or isinstance(data['comment'], str)),
                    'The restore database identity is invalid.')
            return data
        except (ValueError, TypeError, AttributeError):
            raise SafetyError('The restore database identity is invalid.') from None

    def create_target(self):
        self.verify_owned()
        require(self.target_identity() is None, 'Refusing an existing restore database.')
        self.command('createdb', [*self.connection, '--template=template0', '--owner=reviewer_test', self.target])
        # A failed/ambiguous createdb is deliberately never adopted for automatic deletion.
        self.created_here = True
        current = self.target_identity()
        require(current is not None, 'The newly created restore database could not be identified.')
        self.database_oid = current['oid']
        self.verify_owned()
        self.sql('postgres', 'COMMENT ON DATABASE ' + self.target + " IS '" + self.comment + "';", readonly=False)
        self.verify_target()

    def verify_target(self):
        self.verify_owned()
        require(self.created_here and self.database_oid is not None, 'Restore database ownership was not established.')
        current = self.target_identity()
        require(current is not None and current['oid'] == self.database_oid and current['comment'] == self.comment,
                'Restore database ownership changed; mutation and cleanup were withheld.')

    def verify_inserts(self):
        self.verify_target()
        self.sql(self.target, "BEGIN; INSERT INTO public.audit_event(action,target_type,detail) "
                 "VALUES ('RESTORE_DRILL','SYSTEM','isolated validation'); ROLLBACK;", readonly=False)
        self.verify_target()
        self.sql(self.target, "BEGIN; WITH restore_user AS (INSERT INTO public.app_user(username,password_hash,git_username,role) "
            "VALUES ('restore_" + self.token + "','non-authenticating-restore-fixture','restore_" + self.token + "','USER') RETURNING id), "
            "restore_project AS (INSERT INTO public.project(name,repository_url,provider,repository_host,repository_path,owner_id,status) "
            "SELECT 'Restore queue fixture','https://github.com/restore/" + self.token + "','GITHUB','github.com','restore/" + self.token + "',id,'APPROVED' "
            "FROM restore_user RETURNING id) INSERT INTO public.review_request(project_id,request_id,state,source,requested_at,available_at) "
            "SELECT id,'" + str(uuid.uuid4()) + "','QUEUED','SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM restore_project; ROLLBACK;",
            readonly=False)

    def cleanup(self):
        if self.created_here:
            self.verify_target()
            self.command('dropdb', [*self.connection, self.target])
            require(self.target_identity() is None, 'The invocation-owned restore database was not removed.')
            self.created_here = False

    def execute(self):
        primary_failed = False
        try:
            self.prepare()
            self.verify_owned()
            before = self.fingerprints(SOURCE)
            self.command('pg_dump', [*self.connection, '--format=custom', '--no-owner', '--no-acl',
                                    '--lock-wait-timeout=30s', '--file', self.dump, SOURCE], readonly=True)
            LINUX.checked_path(self.dump)
            self.dump.chmod(0o600)
            self.create_target()
            self.verify_target()
            self.command('pg_restore', [*self.connection, '--exit-on-error', '--single-transaction',
                                       '--no-owner', '--no-acl', '--dbname', self.target, self.dump])
            self.verify_target()
            require(before == self.fingerprints(self.target) == self.fingerprints(SOURCE),
                    'Source changed during the drill or restored table fingerprints differ.')
            self.verify_inserts()
        except BaseException:
            primary_failed = True
            raise
        finally:
            try:
                self.cleanup()
            except BaseException:
                if not primary_failed:
                    raise
                # Keep the first failure (including interruption) while reporting that
                # ownership checks or cleanup also failed. Never echo either exception.
                print('CLEANUP INCOMPLETE: restore database removal could not be confirmed; the original failure is retained.',
                      file=sys.stderr)
        LINUX.checked_path(self.dump)
        with self.dump.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        report = {'generatedAt': datetime.now(timezone.utc).isoformat(), 'parentRunToken': self.args.parent_run_token,
                  'result': 'PASS', 'verifiedTables': list(TABLES), 'dumpSha256': digest,
                  'freshIdentityInsert': True, 'projectKeyedQueueInsert': True, 'restoreDatabaseRemoved': True,
                  'sourceReadOnly': True, 'externalServicesUsed': False, 'paidAiUsed': False,
                  'scope': 'isolated synthetic test database only'}
        LINUX.checked_path(self.report, allow_missing=True)
        with self.report.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, indent=2)
            stream.write('\n')
        print('PASS: 10 synthetic tables restored; fresh identities and queue constraints verified; owned restore database removed.')


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--expected-postmaster-pid', type=int, required=True)
    parser.add_argument('--expected-postmaster-started-at', type=int, required=True)
    parser.add_argument('--parent-run-token', required=True)
    parser.add_argument('--report', type=Path, required=True)
    return parser.parse_args(argv)


def main(argv=None):
    prior_sigterm = None
    signal_installed = False
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        LINUX.require_linux_user()
        prior_sigterm = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        signal_installed = True
        BackupDrill(arguments(argv)).execute()
    except KeyboardInterrupt:
        print('INTERRUPTED: synthetic backup drill; owned restore database cleanup was attempted.', file=sys.stderr)
        return 130
    except (SafetyError, OSError, ValueError):
        print('FAIL: synthetic backup validation or ownership checks failed; no source database was restored or deleted.', file=sys.stderr)
        return 1
    finally:
        if signal_installed:
            signal.signal(signal.SIGTERM, prior_sigterm)
    return 0


if __name__ == '__main__':
    sys.exit(main())
