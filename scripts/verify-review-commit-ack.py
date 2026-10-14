#!/usr/bin/env python3
"""Drop one real PostgreSQL COMMIT acknowledgement after review results are durable.

Only an owned WAR, loopback proxy, psql observer and UUID schema are created.
The database stays live. Git/AI are synthetic; no network rules or DB processes change.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import types
import uuid
import importlib.util


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


NETWORK = module('commit_ack_network_helpers', 'verify-review-network-recovery.py')
PROXY = module('commit_ack_proxy_helpers', 'pg-commit-ack-fixture.py')
HELPERS, SHARED, LINUX, RATE = NETWORK.HELPERS, NETWORK.SHARED, NETWORK.LINUX, NETWORK.RATE
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
CLEANUP = ('ownedWarsStopped', 'ownedProxyStopped', 'ownedObserverStopped', 'ownedDatabaseSessionsGone',
           'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')


class OwnedObserver:
    """One persistent owned psql session; stdout accepts bounded fixed markers only."""
    def __init__(self, database, schema):
        require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned observer schema')
        self.database, self.schema = database, schema
        self.process, self.reader = None, None
        self.lines = queue.Queue(maxsize=16)
        self.reader_failed = threading.Event()
        self.token = uuid.uuid4().hex
        self.pid = None
        self.locked = False

    def _read(self):
        try:
            for _ in range(16):
                line = self.process.stdout.readline(256)
                if line == '':
                    return
                if len(line) >= 256 or not line.endswith('\n'):
                    self.reader_failed.set()
                    return
                line = line.strip()
                if not line:
                    continue
                if re.fullmatch(r'ACK_(?:PID_[0-9]{1,10}|LOCKED|RELEASED)_' + self.token, line) is None:
                    self.reader_failed.set()
                    return
                self.lines.put_nowait(line)
            self.reader_failed.set()
        except (OSError, ValueError, queue.Full):
            self.reader_failed.set()

    def _send(self, text):
        require(self.process is not None and self.process.poll() is None, 'The owned observer is not running')
        try:
            self.process.stdin.write(text)
            self.process.stdin.flush()
        except (OSError, ValueError):
            raise VerificationError('The owned observer could not receive its bounded command') from None

    def _next(self, deadline):
        while time.monotonic() < deadline:
            require(not self.reader_failed.is_set(), 'The owned observer returned an invalid bounded response')
            try:
                return self.lines.get(timeout=min(0.1, max(0.001, deadline - time.monotonic())))
            except queue.Empty:
                require(self.process.poll() is None, 'The owned observer exited before confirming its command')
        raise VerificationError('The owned observer did not confirm its command before the deadline')

    def acquire(self, project):
        require(self.process is None and type(project) is int and project > 0, 'Invalid owned observer lock request')
        environment = dict(self.database.environment)
        environment['PGAPPNAME'] = self.schema + '-observer'
        environment['PGOPTIONS'] += ' -c idle_in_transaction_session_timeout=10000'
        try:
            self.process = subprocess.Popen([*self.database.command, '-q'], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding='utf-8',
                env=environment, creationflags=HELPERS.CREATE_FLAGS)
        except OSError:
            raise VerificationError('The owned observer process could not start') from None
        self.reader = threading.Thread(target=self._read, name='review-owned-commit-observer', daemon=True)
        self.reader.start()
        deadline = time.monotonic() + 3
        self._send("BEGIN;\nSELECT 'ACK_PID_' || pg_backend_pid() || '_" + self.token + "';\n"
                   "SELECT 'ACK_LOCKED_" + self.token + "' FROM " + self.schema + '.project WHERE id=' + str(project) + ' FOR UPDATE;\n')
        line = self._next(deadline)
        match = re.fullmatch(r'ACK_PID_([0-9]{1,10})_' + self.token, line)
        require(match is not None and 0 < int(match.group(1)) <= 2147483647, 'The owned observer did not return its process identity')
        self.pid = int(match.group(1))
        require(self._next(deadline) == 'ACK_LOCKED_' + self.token, 'The owned observer did not acquire the expected row')
        self.locked = True
        return self.pid

    def release(self):
        require(self.locked, 'The owned observer has no row lock to release')
        self._send('ROLLBACK;\n\\echo ACK_RELEASED_' + self.token + '\n')
        require(self._next(time.monotonic() + 2) == 'ACK_RELEASED_' + self.token,
                'The owned observer did not confirm rollback')
        self.locked = False

    def stopped(self):
        return self.process is None or self.process.poll() is not None

    def close(self):
        process = self.process
        if process is not None:
            if process.poll() is None:
                try:
                    self._send('ROLLBACK;\n\\q\n')
                except VerificationError:
                    pass
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=2)
            require(process.poll() is not None, 'The owned observer process did not stop')
            for stream in (process.stdin, process.stdout):
                if stream is not None:
                    stream.close()
        if self.reader is not None:
            self.reader.join(2)
            require(not self.reader.is_alive(), 'The owned observer reader did not stop')
        self.locked = False


def owned_sessions(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned session identity')
    return int(database.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name IN ('" + schema + "','" + schema + "-observer');"))


def blocking_backend(database, schema, observer_pid):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None
            and type(observer_pid) is int and 0 < observer_pid <= 2147483647,
            'Invalid owned observer identity')
    owner = database.sql("SELECT count(*) FROM pg_stat_activity WHERE pid=" + str(observer_pid)
        + " AND application_name='" + schema + "-observer' AND usename=current_user AND datname=current_database();")
    require(owner == '1', 'The locking observer session no longer has the expected identity')
    values = database.sql("SELECT pid FROM pg_stat_activity WHERE application_name='" + schema
        + "' AND usename=current_user AND datname=current_database() AND wait_event_type='Lock' AND "
        + str(observer_pid) + '=ANY(pg_blocking_pids(pid));').splitlines()
    require(len(values) <= 1, 'More than one owned backend is waiting on the observer')
    if not values:
        return None
    require(re.fullmatch(r'[0-9]{1,10}', values[0]) is not None and 0 < int(values[0]) <= 2147483647,
            'The waiting backend identity is invalid')
    return int(values[0])


def stored_rows(database, schema, project):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None and type(project) is int and project > 0,
            'Invalid durable result identity')
    # Raw rows remain in memory solely for equality; they never enter evidence or diagnostics.
    return json.loads(database.sql(f"""SELECT json_build_object(
      'commits',(SELECT json_agg(row_to_json(c) ORDER BY id) FROM {schema}.reviewed_commit c WHERE project_id={project}),
      'issues',(SELECT json_agg(row_to_json(i) ORDER BY id) FROM {schema}.review_issue i WHERE project_id={project}),
      'assignments',(SELECT json_agg(row_to_json(a) ORDER BY id) FROM {schema}.audit_event a
        WHERE target_type='REVIEWED_COMMIT' AND target_id IN (SELECT id FROM {schema}.reviewed_commit WHERE project_id={project}))
    );"""))


def assert_committed(current, initial):
    require(current['request'] == initial['request'] and current['ownership'] == initial['ownership']
            and current['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B] and current['issues'] == 2
            and current['assignees'] == [initial['request']['actor']] * 2 and current['cursor'] is None
            and current['runs'] == [{'state': 'RUNNING', 'commits': 2}]
            and current['progress']['runId'] == initial['progress']['runId']
            and current['progress']['stage'] == 'REVIEWING' and current['progress']['saved'] == 2
            and current['progress']['lastSavedAt'] is not None,
            'A committed result or unfinished request changed before the acknowledgement failure')


def acknowledgement_dropped(proxy):
    metrics = proxy.metrics()
    return (metrics.get('failed') is False and metrics.get('faultState') == 'DROPPING'
            and type(metrics.get('commitAcknowledgementsDropped')) is int and metrics['commitAcknowledgementsDropped'] == 1
            and metrics.get('readyForQueryIdleDropped') is True)


def assert_saved_rows(saved):
    require(isinstance(saved, dict) and isinstance(saved.get('commits'), list) and isinstance(saved.get('issues'), list)
            and len(saved['commits']) == len(saved['issues']) == 2 and isinstance(saved.get('assignments'), list)
            and sum(isinstance(row, dict) and row.get('action') == 'ISSUES_ASSIGNED' for row in saved['assignments']) == 2,
            'The committed result and assignment audit rows are incomplete')


def exercise(war, database, schema, fixture, fixture_url, proxy, observer, args, report, deadline):
    war.start(True)
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    project = RATE.register(browser, war, fixture_url, 'restart')
    RATE.post_review(browser, project)
    HELPERS.bounded_wait(fixture.first_b_entered.is_set, deadline, 'The unfinished AI result was not reached', war.process)
    initial = NETWORK.snapshot(database, schema, project)
    require(initial['request']['state'] == 'RUNNING' and initial['request']['attempts'] == 1
            and initial['request']['source'] == 'MANUAL' and initial['request']['actor'] is not None
            and initial['progress']['stage'] == 'REVIEWING' and initial['progress']['saved'] == 1
            and initial['progress']['lastSavedAt'] is not None,
            'The initial review did not preserve exactly commit A')
    NETWORK.assert_preserved(initial, initial)
    server = NETWORK.server_identity(database)
    observer.acquire(project)
    lock_started = time.monotonic()
    fixture.release_first_b.set()
    target = None
    while time.monotonic() < min(deadline, lock_started + 2.5):
        target = blocking_backend(database, schema, observer.pid)
        if target is not None:
            break
        time.sleep(0.05)
    require(target is not None, 'The owned review backend did not wait on the controlled row lock')
    proxy.arm_backend(target)
    observer.release()
    require(time.monotonic() - lock_started < 5, 'Arming and releasing the controlled transaction exceeded five seconds')
    started = time.monotonic()
    HELPERS.bounded_wait(lambda: acknowledgement_dropped(proxy), min(deadline, started + 10),
                         'The exact COMMIT and idle acknowledgements were not dropped', war.process)
    committed = NETWORK.snapshot(database, schema, project)
    assert_committed(committed, initial)
    saved = stored_rows(database, schema, project)
    assert_saved_rows(saved)
    require(not NETWORK.RECOVERY.worker_failed(war.logs / 'war-start-1.log', project),
            'The client failure preceded the independent committed-state observation')
    report['checks']['committedBeforeClientFailure'] = True
    while True:
        require(time.monotonic() < min(deadline, started + 25) and war.process.poll() is None,
                'The acknowledgement failure did not reach its bounded worker outcome')
        assert_committed(NETWORK.snapshot(database, schema, project), initial)
        metrics = proxy.metrics()
        require(metrics.get('failed') is False, 'The acknowledgement proxy failed')
        if metrics.get('targetClientClosed') is True and NETWORK.RECOVERY.worker_failed(war.logs / 'war-start-1.log', project):
            break
        time.sleep(0.15)
    report['workerFailureAfterSeconds'] = round(time.monotonic() - started, 3)
    require(stored_rows(database, schema, project) == saved, 'Stored results changed while the client timed out')
    proxy.heal()
    HELPERS.bounded_wait(lambda: NETWORK.snapshot(database, schema, project)['request']['state'] == 'SUCCEEDED',
                         deadline, 'The same WAR did not reconcile the acknowledged-lost result', war.process)
    final = NETWORK.snapshot(database, schema, project)
    require(all(final['request'][key] == initial['request'][key] for key in ('requestId', 'actor', 'source'))
            and final['request']['attempts'] == 2 and final['ownership']['requestedAt'] == initial['ownership']['requestedAt']
            and final['ownership']['claim'] != initial['ownership']['claim'] and final['ownership']['run'] != initial['ownership']['run']
            and final['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B] and final['issues'] == 2
            and final['cursor'] == HELPERS.SHA_B and final['runs'] == [{'state': 'FAILED', 'commits': 2}, {'state': 'SUCCEEDED', 'commits': 0}]
            and final['progress']['stage'] == 'FINALIZING' and final['progress']['saved'] == 0
            and final['progress']['lastSavedAt'] is None and stored_rows(database, schema, project) == saved,
            'Recovery replaced or duplicated durable results, ownership, checkpoint or attempt progress')
    counts = fixture.observed()
    require(all(counts.get(kind + '_' + file + '.java') == 1 for kind in ('ai', 'detail', 'diff') for file in ('A', 'B'))
            and not fixture.second_b_entered.is_set() and not fixture.errors,
            'A stored commit was fetched or reviewed again after acknowledgement loss')
    require(war.starts == 1 and war.process.poll() is None and NETWORK.server_identity(database) == server,
            'The WAR or database restarted during acknowledgement recovery')
    NETWORK.probe(war.base, 'readiness', 200, 'UP')
    report['checks'].update({'sameLiveWarRecovered': True, 'databaseProcessUnchanged': True,
                            'savedRowsReusedWithoutAi': True, 'twoIssuesAndSafeCheckpoint': True,
                            'requestActorAndReceptionPreserved': True})
    report['requestCounts'], report['attempts'] = counts, 2


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=180)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = LINUX.checked_path(getattr(args, name)) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and 1024 <= args.port <= 65535
            and 90 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120,
            'Invalid bounded acknowledgement drill arguments')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Acknowledgement evidence must stay in this checkout .local')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing acknowledgement report')
    LINUX.unused_port(args.port)
    args.plain_postgres_protocol = True
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'commit-ack-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    work = Path(tempfile.mkdtemp(prefix='owned-work-', dir=logs))
    database, identity, war, proxy, observer, originals, server = (None,) * 7
    fixture = HELPERS.Fixture()
    owned, failure, cleanup_failed = False, None, False
    report['logDirectory'] = str(logs)
    try:
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work)
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Unexpected observer database')
        originals, server = RATE.public_fingerprints(database), NETWORK.server_identity(database)
        database.sql('CREATE SCHEMA ' + schema + ';')
        owned = True
        captured = SHARED.schema_identity(database, schema)
        require(captured is not None and type(captured.get('oid')) is int and captured['owner'] == database.user,
                'The new schema identity could not be established')
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': captured['oid'], 'owner': database.user, 'marker': marker}
        SHARED.verify_schema(database, schema, identity)
        target_port = int(HELPERS.DB_PATTERN.fullmatch(database.url).group(2))
        require(target_port != args.port, 'The WAR and observer database ports must differ')
        proxy = PROXY.PgCommitAckProxy(target_port, expected_application_name=schema,
                                     expected_user=database.user, expected_database='reviewer_integration').start()
        observer = OwnedObserver(database, schema)
        routed = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:' + str(proxy.port) + '/reviewer_integration',
                                      user=database.user, password=database.password, sql=database.sql)
        fixture_url = fixture.start()
        war = HELPERS.OwnedWar(args, routed, schema, fixture_url, work, logs, deadline)
        exercise(war, database, schema, fixture, fixture_url, proxy, observer, args, report, deadline)
    except BaseException as error:
        failure = error
    finally:
        # Release the controlling transaction before stopping the WAR or removing its schema.
        observer_stopped = observer is None
        try:
            if observer is not None:
                observer.close()
                observer_stopped = observer.stopped()
                require(observer_stopped, 'The owned observer did not stop')
        except BaseException:
            cleanup_failed = True
        fixture.release_first_b.set()
        fixture.release_second_b.set()
        try:
            if war is not None:
                war.stop(force=True)
        except BaseException:
            cleanup_failed = True
        try:
            fixture.close()
        except BaseException:
            cleanup_failed = True
        proxy_closed = proxy is None
        try:
            if proxy is not None:
                proxy.close()
                report['proxy'] = proxy.metrics()
                proxy_closed = report['proxy']['mode'] == 'CLOSED' and report['proxy']['active'] == 0
                require(proxy_closed, 'The owned acknowledgement proxy did not stop')
        except BaseException:
            cleanup_failed = True
        stopped = war is None or war.process is None or war.process.poll() is not None
        sessions_gone = False
        if database is not None and stopped and observer_stopped and proxy_closed:
            try:
                HELPERS.bounded_wait(lambda: owned_sessions(database, schema) == 0, time.monotonic() + 10,
                                     'Owned WAR or observer database sessions remain')
                sessions_gone = True
            except BaseException:
                cleanup_failed = True
        if owned and stopped and observer_stopped and proxy_closed and sessions_gone:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and observer_stopped and proxy_closed and not owned:
            try:
                if originals is not None:
                    require(RATE.public_fingerprints(database) == originals, 'Original public test tables changed')
                    require(NETWORK.server_identity(database) == server, 'Observer database process changed')
                    report['checks']['originalTestTablesPreserved'] = True
                    report['originalTableCount'] = len(originals)
                require(work.parent == logs and work.name.startswith('owned-work-'), 'Unexpected owned work directory')
                LINUX.checked_path(work, directory=True)
                shutil.rmtree(work)
            except BaseException:
                cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedProxyStopped': proxy_closed,
            'ownedObserverStopped': observer_stopped, 'ownedDatabaseSessionsGone': sessions_gone,
            'ownedSchemaRemoved': not owned, 'ownedWorkRemoved': not work.exists()})
        report['cleanupFailed'] = cleanup_failed or not stopped or not observer_stopped or not proxy_closed or owned
        report['warStarts'] = war.starts if war is not None else 0
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned acknowledgement resources could not be safely removed')


def main(argv=None):
    args, previous, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'scope': 'One live WAR; one durable per-commit transaction with its wire COMMIT acknowledgement dropped',
              'limits': {'queryTimeoutSeconds': 5, 'socketTimeoutSeconds': 10, 'connectTimeoutSeconds': 5},
              'limitations': ['Synthetic Git/AI only; no operational recovery SLA',
                              'Does not drop claim or final-success COMMIT acknowledgements']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        previous = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        require(report['cleanupFailed'] is False and all(report['checks'].get(key) is True for key in CLEANUP)
                and all(report['checks'].get(key) is True for key in ('sameLiveWarRecovered', 'databaseProcessUnchanged',
                    'committedBeforeClientFailure', 'savedRowsReusedWithoutAi', 'twoIssuesAndSafeCheckpoint'))
                and type(report.get('proxy', {}).get('commitAcknowledgementsDropped')) is int
                and report['proxy']['commitAcknowledgementsDropped'] == 1
                and report.get('proxy', {}).get('readyForQueryIdleDropped') is True
                and report.get('proxy', {}).get('targetClientClosed') is True,
                'Successful evidence requires the committed result, acknowledgement fault and complete cleanup')
        report['result'], code = 'PASS', 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, (VerificationError, LINUX.SafetyError, PROXY.ProxyError)) else 'Private error details withheld'
    finally:
        if installed:
            signal.signal(signal.SIGTERM, previous)
    if args is not None:
        try:
            LINUX.checked_path(args.report, allow_missing=True)
            descriptor = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                json.dump(report, stream, indent=2)
                stream.write('\n')
        except (OSError, LINUX.SafetyError):
            print('FAIL: fresh acknowledgement report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': bounded committed-review acknowledgement recovery; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
