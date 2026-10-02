#!/usr/bin/env python3
"""Non-root Linux entrypoint for an isolated PostgreSQL 17 / Java 25 verification run.

Only this checkout's .local/pg-validation is controlled. Existing Windows or unmarked
clusters, links, occupied ports, and changed postmaster identities are refused.
Optional WAR drills use synthetic loopback Git/AI only; no real AI opt-in is inherited.
"""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import sys
import uuid


WORKSPACE = Path(__file__).absolute().parent.parent
MARKER = 'Isolated local AI Reviewer tests only.'
PLATFORM_MARKER = 'linux-native-pg17'
OPT_INS = ('RUN_GITHUB_SMOKE', 'RUN_GITLAB_SMOKE', 'RUN_OLLAMA_SMOKE', 'RUN_AI_EVALUATION',
           'RUN_GIT_LOAD_SMOKE', 'RUN_REVIEW_LOAD_SMOKE', 'RUN_OPERATIONS_LOAD_SMOKE')


class SafetyError(Exception):
    """Fixed diagnostics only: never include commands, environment values or child output."""


def require(condition, message):
    if not condition:
        raise SafetyError(message)


def absolute_path(value):
    path = Path(value)
    require(path.is_absolute() and '..' not in path.parts and not str(value).startswith(('//', '\\\\'))
            and not any(ord(char) < 32 for char in str(value)), 'An unambiguous absolute local path is required.')
    return path


def checked_path(value, *, directory=False, allow_missing=False):
    path = absolute_path(value)
    for item in (*reversed(path.parents), path):
        try:
            info = item.lstat()
        except FileNotFoundError:
            require(allow_missing, 'A required local test path is missing.')
            return path
        require(not stat.S_ISLNK(info.st_mode) and not (getattr(info, 'st_file_attributes', 0) & 0x400),
                'Links and reparse points are forbidden for local test data.')
        is_directory = item != path or directory
        require(stat.S_ISDIR(info.st_mode) if is_directory else stat.S_ISREG(info.st_mode),
                'A local test path has an unexpected file type.')
    return path


def limited_text(path, limit=16384):
    path = checked_path(path)
    with path.open('rb') as source:
        content = source.read(limit + 1)
    require(len(content) <= limit, 'A local test evidence file exceeded its size limit.')
    try:
        return content.decode('utf-8-sig')
    except UnicodeError:
        raise SafetyError('A local test evidence file has invalid encoding.') from None


def native_filesystem(workspace, mountinfo):
    """Reject Windows/shared mounts even if they were mounted somewhere other than /mnt/c."""
    matches = []
    for line in mountinfo.splitlines():
        before, separator, after = line.partition(' - ')
        fields = before.split()
        if separator and len(fields) >= 5 and after.split():
            mountpoint = re.sub(r'\\([0-7]{3})', lambda match: chr(int(match[1], 8)), fields[4])
            mount = Path(mountpoint)
            if mount == workspace or mount in workspace.parents:
                matches.append((len(mount.parts), after.split()[0]))
    deepest = max((depth for depth, _ in matches), default=-1)
    require(matches and all(kind in ('ext4', 'xfs', 'btrfs', 'tmpfs', 'overlay', 'zfs')
                            for depth, kind in matches if depth == deepest),
            'Use a native Linux filesystem checkout, not a Windows or network mount.')


def require_linux_user():
    require(sys.platform.startswith('linux') and hasattr(os, 'geteuid') and os.geteuid() != 0,
            'Run this entrypoint as a non-root Linux user.')


def postgres17_version(value):
    # PGDG/Ubuntu/Debian append their package version in parentheses.
    return re.fullmatch(r'[^\r\n]+ \(PostgreSQL\) 17(?:\.[0-9]+)?(?: \([^\r\n()]+\))?[ \t]*(?:\r?\n)?', value) is not None


def unused_port(port):
    require(type(port) is int and 1024 <= port <= 65535, 'An unprivileged valid loopback port is required.')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise SafetyError('A requested loopback test port is already occupied.') from None


def safe_environment(java, pg_bin, port):
    environment = {name: os.environ[name] for name in ('HOME', 'USER', 'LOGNAME', 'LANG', 'LC_ALL', 'TMPDIR')
                   if name in os.environ}
    environment.update({'PATH': str(java.parent) + ':' + str(pg_bin) + ':/usr/bin:/bin',
                        'JAVA_HOME': str(java.parent.parent), 'MAVEN_SKIP_RC': 'true',
                        'TEST_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{port}/reviewer_integration',
                        'TEST_IDENTITY_DATABASE_URL': f'jdbc:postgresql://127.0.0.1:{port}/identity_security',
                        'TEST_DATABASE_USERNAME': 'reviewer_test', 'TEST_DATABASE_PASSWORD': '',
                        'PGCONNECT_TIMEOUT': '5', 'PGSSLMODE': 'disable',
                        'PGOPTIONS': '-c statement_timeout=30000 -c lock_timeout=10000'})
    environment.update({name: 'false' for name in OPT_INS})
    return environment


def run_command(command, *, environment, cwd, log=None, capture=False, timeout=60, allowed=(0,), cooperative_cancel=False):
    """Kill only this command's new process group on cancellation; never emit child output."""
    stream = None
    process = None
    try:
        if log is not None:
            checked_path(log, allow_missing=True)
            stream = Path(log).open('xb')
        process = subprocess.Popen([str(value) for value in command], cwd=cwd, env=environment,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE if capture else stream or subprocess.DEVNULL,
                                   stderr=subprocess.STDOUT, start_new_session=True, text=capture)
        try:
            output, _ = process.communicate(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            if process.poll() is None:
                cooperative_finished = False
                if cooperative_cancel:
                    # Python drill finally blocks stop owned WARs, restore the PG instance,
                    # and write the verified handoff. Interrupt only the orchestrating child
                    # first, so its still-needed WAR/DB helpers can finish that cleanup.
                    os.kill(process.pid, signal.SIGINT)
                    try:
                        process.communicate(timeout=60)
                        cooperative_finished = True
                    except subprocess.TimeoutExpired:
                        pass
                if not cooperative_finished and process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.communicate(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.communicate(timeout=5)
            raise
        require(process.returncode in allowed, 'A local verification command failed; inspect its owned log.')
        return process.returncode, output or ''
    except (OSError, subprocess.TimeoutExpired):
        raise SafetyError('A local verification command failed or timed out.') from None
    finally:
        if stream is not None:
            stream.close()


class TestRun:
    def __init__(self, args, workspace=WORKSPACE):
        self.args = args
        self.workspace = absolute_path(workspace)
        self.cluster = self.workspace / '.local' / 'pg-validation'
        self.pg_bin = absolute_path(args.pg_bin)
        self.java = absolute_path(args.java).resolve(strict=True)
        self.environment = safe_environment(self.java, self.pg_bin, args.port)
        self.token = uuid.uuid4().hex
        self.logs = self.workspace / '.local' / ('linux-postgres-' + self.token)
        self.lock = self.workspace / '.local' / 'pg-validation-run.lock'
        self.lock_identity = None
        self.cluster_identity = None
        self.marker_identity = None
        self.owned = None
        self.start_attempted = False

    def command(self, command, **kwargs):
        return run_command(command, environment=self.environment, cwd=kwargs.pop('cwd', self.workspace), **kwargs)

    def identity(self, path):
        value = path.lstat()
        return value.st_dev, value.st_ino

    def validate_cluster(self):
        checked_path(self.workspace, directory=True)
        checked_path(self.cluster, directory=True)
        require(limited_text(self.cluster / 'reviewer-test-cluster').strip() == MARKER
                and limited_text(self.cluster / 'reviewer-test-platform').strip() == PLATFORM_MARKER
                and limited_text(self.cluster / 'PG_VERSION').strip() == '17',
                'The Linux test cluster marker or PostgreSQL version does not match.')
        if self.cluster_identity is not None:
            require(self.identity(self.cluster) == self.cluster_identity
                    and self.identity(self.cluster / 'reviewer-test-cluster') == self.marker_identity,
                    'The owned test cluster directory or marker was replaced.')

    def pid_identity(self):
        self.validate_cluster()
        lines = limited_text(self.cluster / 'postmaster.pid').splitlines()
        require(len(lines) >= 6 and all(re.fullmatch(r'[0-9]+', lines[index]) for index in (0, 2, 3)),
                'The test postmaster PID file is invalid.')
        require(0 < int(lines[0]) <= 2147483647 and 0 < int(lines[2]) <= 9223372036854775807
                and absolute_path(lines[1]) == self.cluster and int(lines[3]) == self.args.port
                and lines[5] == '127.0.0.1', 'The test postmaster identity does not match this cluster.')
        return int(lines[0]), int(lines[2])

    def sql(self, text):
        _, output = self.command([self.pg_bin / 'psql', '-X', '-w', '-h', '127.0.0.1', '-p', str(self.args.port),
                                 '-U', 'reviewer_test', '-d', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1', '-c', text],
                                capture=True, timeout=40)
        return output.strip()

    def verify_owned(self, expected):
        require(expected is not None and self.pid_identity() == expected,
                'PostgreSQL ownership changed; automatic cluster control was withheld.')
        try:
            server = json.loads(self.sql("SELECT json_build_object('directory',current_setting('data_directory'),"
                "'port',current_setting('port'),'listen',current_setting('listen_addresses'),"
                "'pid',split_part(pg_read_file('postmaster.pid',0,16384),chr(10),1),"
                "'started',extract(epoch FROM pg_postmaster_start_time())::bigint);"))
            matched = (absolute_path(server['directory']) == self.cluster and int(server['port']) == self.args.port
                       and server['listen'] == '127.0.0.1' and int(server['pid']) == expected[0]
                       and abs(int(server['started']) - expected[1]) <= 1)
        except (ValueError, TypeError, KeyError):
            matched = False
        require(matched, 'The connected PostgreSQL server is not the owned test cluster.')

    def prepare(self):
        require_linux_user()
        checked_path(self.workspace, directory=True)
        mountinfo = Path('/proc/self/mountinfo').read_text(encoding='utf-8')
        # A native checkout can still contain a separately mounted Windows .local/PGDATA.
        for target in (self.workspace, self.workspace / '.local', self.cluster):
            native_filesystem(target, mountinfo)
        checked_path(self.pg_bin, directory=True)
        for name in ('postgres', 'pg_ctl', 'initdb', 'psql', 'createdb'):
            require(os.access(checked_path(self.pg_bin / name), os.X_OK), 'A PostgreSQL executable is unavailable.')
            _, version = self.command([self.pg_bin / name, '--version'], capture=True)
            require(postgres17_version(version),
                    'All configured PostgreSQL tools must be version 17.')
        require(os.access(checked_path(self.java), os.X_OK), 'The configured Java executable is unavailable.')
        _, java_version = self.command([self.java, '-version'], capture=True)
        require(re.search(r'^(?:openjdk|java) version "25(?:[.\-+" ])', java_version, re.MULTILINE) is not None,
                'The configured Java runtime must be version 25.')
        checked_path(self.workspace / 'source' / 'mvnw')
        checked_path(self.cluster, directory=True, allow_missing=True)
        checked_path(self.workspace / '.local', directory=True, allow_missing=True).mkdir(exist_ok=True, mode=0o700)
        checked_path(self.lock, directory=True, allow_missing=True)
        try:
            self.lock.mkdir(mode=0o700)
        except FileExistsError:
            raise SafetyError('Another local cluster run or an unverified stale run lock exists.') from None
        self.lock_identity = self.identity(self.lock)
        self.logs.mkdir(mode=0o700)
        # Explicit nonexistent files prevent libpq from consulting personal credential/service files.
        self.environment.update({'PGPASSFILE': str(self.logs / 'unused-pgpass'),
                                 'PGSERVICEFILE': str(self.logs / 'unused-pgservice')})
        if not self.cluster.exists():
            self.command([self.pg_bin / 'initdb', '-D', self.cluster, '-U', 'reviewer_test', '-A', 'trust',
                          '--encoding=UTF8', '--locale=C'], log=self.logs / 'initdb.log', timeout=120)
            checked_path(self.cluster, directory=True)
            require(limited_text(self.cluster / 'PG_VERSION').strip() == '17', 'Unexpected newly initialized PostgreSQL version.')
            config = checked_path(self.cluster / 'postgresql.conf')
            with config.open('a', encoding='utf-8') as stream:
                stream.write("\n# Isolated loopback tests only; also applies to child recovery restarts.\n"
                             "listen_addresses = '127.0.0.1'\nunix_socket_directories = ''\n")
            for name, content in (('reviewer-test-cluster', MARKER), ('reviewer-test-platform', PLATFORM_MARKER)):
                with (self.cluster / name).open('x', encoding='utf-8') as stream:
                    stream.write(content + '\n')
        self.validate_cluster()
        self.cluster_identity = self.identity(self.cluster)
        self.marker_identity = self.identity(self.cluster / 'reviewer-test-cluster')
        # A stale PID file requires explicit investigation; it is never silently removed here.
        checked_path(self.cluster / 'postmaster.pid', allow_missing=True)
        require(not (self.cluster / 'postmaster.pid').exists(), 'An existing postmaster PID file prevents a new test run.')
        status, _ = self.command([self.pg_bin / 'pg_ctl', '-D', self.cluster, 'status'], allowed=(0, 3))
        require(status == 3, 'The isolated cluster is already running.')
        for port in self.selected_ports():
            unused_port(port)
        _, sockets = self.command([self.pg_bin / 'postgres', '-D', self.cluster, '-C', 'unix_socket_directories'], capture=True)
        require(not sockets.strip(), 'The isolated Linux cluster must disable Unix socket directories.')

    def selected_ports(self):
        ports = [self.args.port]
        if self.args.review_restart:
            ports.append(self.args.review_restart_port)
        if self.args.review_concurrency:
            ports.extend((self.args.review_concurrency_port_a, self.args.review_concurrency_port_b))
        if self.args.review_database_recovery:
            ports.append(self.args.review_database_recovery_port)
        require(len(set(ports)) == len(ports), 'Selected PostgreSQL and WAR ports must all differ.')
        return ports

    def start(self):
        self.validate_cluster()
        unused_port(self.args.port)
        self.start_attempted = True
        self.command([self.pg_bin / 'pg_ctl', '-D', self.cluster, '-l', self.logs / 'postgres.log',
                      '-o', f'-p {self.args.port} -h 127.0.0.1', '-w', '-t', '30', 'start'],
                     log=self.logs / 'start.log', timeout=40)
        self.owned = self.pid_identity()
        self.verify_owned(self.owned)

    def databases(self):
        self.verify_owned(self.owned)
        for database in ('reviewer_integration', 'identity_security'):
            exists = self.sql("SELECT 1 FROM pg_database WHERE datname='" + database + "';")
            require(exists in ('', '1'), 'Unexpected isolated test database inventory.')
            if not exists:
                self.command([self.pg_bin / 'createdb', '-w', '-h', '127.0.0.1', '-p', str(self.args.port),
                              '-U', 'reviewer_test', database], log=self.logs / (database + '-create.log'))

    def adopt_handoff(self, report, token):
        if not report.exists():
            return
        try:
            handoff = json.loads(limited_text(report, 131072))
            require(isinstance(handoff, dict) and handoff.get('parentRunToken') == token,
                    'The DB recovery ownership report belongs to a different invocation.')
            identity = handoff.get('parentOwnedPostmaster')
            if identity is not None:
                require(isinstance(identity, dict) and type(identity.get('pid')) is int
                        and type(identity.get('startedAt')) is int and 0 < identity['pid'] <= 2147483647
                        and 0 < identity['startedAt'] <= 9223372036854775807, 'Invalid DB recovery ownership identity.')
                expected = identity['pid'], identity['startedAt']
                self.verify_owned(expected)
                self.owned = expected
        except (ValueError, TypeError):
            raise SafetyError('Invalid bounded DB recovery ownership report.') from None

    def drill(self, name, options):
        report = self.logs / (name + '-' + self.token + '.json')
        checked_path(report, allow_missing=True)
        require(not report.exists(), 'Refusing an existing child verification report.')
        script = checked_path(self.workspace / 'scripts' / ('verify-' + name + '.py'))
        command = [sys.executable, script, '--war', checked_path(self.workspace / 'source' / 'target' / 'ai-code-reviewer.war'),
                   '--java', self.java, '--psql', self.pg_bin / 'psql', '--report', report, *options]
        if name == 'review-db-recovery':
            self.verify_owned(self.owned)
            command.extend(['--pg-ctl', self.pg_bin / 'pg_ctl', '--cluster-path', self.cluster,
                            '--expected-postmaster-pid', str(self.owned[0]),
                            '--expected-postmaster-started-at', str(self.owned[1]), '--parent-run-token', self.token])
        try:
            self.command(command, log=self.logs / (name + '.log'), timeout=900, cooperative_cancel=True)
        finally:
            if name == 'review-db-recovery':
                self.adopt_handoff(report, self.token)
        try:
            outcome = json.loads(limited_text(report, 131072))
            require(outcome.get('result') == 'PASS' and outcome.get('externalServicesUsed') is False
                    and outcome.get('paidAiUsed') is False, 'A child drill did not produce a successful isolated report.')
        except (ValueError, TypeError, AttributeError):
            raise SafetyError('The child drill report was invalid.') from None

    def verify(self):
        # Clean first: the mandatory PG report gate cannot accidentally accept stale reports.
        self.command(['/usr/bin/bash', self.workspace / 'source' / 'mvnw', '-B', '-ntp', 'clean', 'verify'],
                     log=self.logs / 'maven.log', timeout=5400, cwd=self.workspace / 'source')
        self.command([sys.executable, checked_path(self.workspace / 'scripts' / 'verify-test-reports.py'),
                      self.workspace / 'source' / 'target' / 'surefire-reports'], log=self.logs / 'required-pg.log')
        if getattr(self.args, 'backup_restore', False):
            self.backup_restore()
        if self.args.review_restart:
            self.drill('review-restart', ['--port', str(self.args.review_restart_port)])
        if self.args.review_concurrency:
            self.drill('review-concurrency', ['--port-a', str(self.args.review_concurrency_port_a),
                                              '--port-b', str(self.args.review_concurrency_port_b)])
        if self.args.review_database_recovery:
            self.drill('review-db-recovery', ['--port', str(self.args.review_database_recovery_port)])

    def backup_restore(self):
        self.verify_owned(self.owned)
        report = self.logs / ('postgres-backup-' + self.token + '.json')
        checked_path(report, allow_missing=True)
        require(not report.exists(), 'Refusing an existing backup verification report.')
        script = checked_path(self.workspace / 'scripts' / 'verify-postgres-backup.py')
        self.command([sys.executable, script, '--pg-bin', self.pg_bin,
                      '--expected-postmaster-pid', str(self.owned[0]),
                      '--expected-postmaster-started-at', str(self.owned[1]),
                      '--parent-run-token', self.token, '--report', report],
                     log=self.logs / 'postgres-backup.log', timeout=600, cooperative_cancel=True)
        self.verify_owned(self.owned)
        try:
            outcome = json.loads(limited_text(report, 131072))
            require(outcome.get('result') == 'PASS' and outcome.get('parentRunToken') == self.token
                    and outcome.get('restoreDatabaseRemoved') is True and outcome.get('sourceReadOnly') is True
                    and outcome.get('externalServicesUsed') is False and outcome.get('paidAiUsed') is False,
                    'The backup drill did not produce a successful isolated report.')
        except (ValueError, TypeError, AttributeError):
            raise SafetyError('The backup drill report was invalid.') from None

    def cleanup(self):
        try:
            if self.start_attempted:
                self.verify_owned(self.owned)
                self.command([self.pg_bin / 'pg_ctl', '-D', self.cluster, '-m', 'fast', '-w', '-t', '30', 'stop'],
                             log=self.logs / 'stop.log', timeout=40)
                require(not (self.cluster / 'postmaster.pid').exists(), 'The owned test cluster did not stop fully.')
        finally:
            if self.lock_identity is not None:
                checked_path(self.lock, directory=True)
                require(self.identity(self.lock) == self.lock_identity, 'The owned run lock changed; cleanup was withheld.')
                self.lock.rmdir()
                self.lock_identity = None

    def execute(self):
        try:
            self.prepare()
            self.start()
            self.databases()
            self.verify()
        finally:
            self.cleanup()


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pg-bin', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--port', type=int, default=55439)
    for name in ('review-restart', 'review-concurrency', 'review-database-recovery', 'backup-restore'):
        parser.add_argument('--' + name, action='store_true')
    for name, default in (('review-restart-port', 18089), ('review-concurrency-port-a', 18090),
                          ('review-concurrency-port-b', 18091), ('review-database-recovery-port', 18092)):
        parser.add_argument('--' + name, type=int, default=default)
    args = parser.parse_args(argv)
    for name, value in vars(args).items():
        if name == 'port' or '_port' in name:
            require(1024 <= value <= 65535, 'Invalid local test port.')
    return args


def main(argv=None):
    prior_sigterm = None
    signal_installed = False
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        require_linux_user()
        prior_sigterm = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        signal_installed = True
        TestRun(arguments(argv)).execute()
    except KeyboardInterrupt:
        print('INTERRUPTED: local verification; owned cluster cleanup was attempted.', file=sys.stderr)
        return 130
    except (SafetyError, OSError, ValueError):
        print('FAIL: local verification or ownership validation failed; inspect the invocation-owned logs.', file=sys.stderr)
        return 1
    finally:
        if signal_installed:
            signal.signal(signal.SIGTERM, prior_sigterm)
    print('PASS: isolated Linux PostgreSQL verification and selected synthetic drills.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
