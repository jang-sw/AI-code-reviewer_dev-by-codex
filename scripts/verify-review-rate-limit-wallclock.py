#!/usr/bin/env python3
"""Two actual WARs share a real 65-second Git cooldown without SQL time changes.

All Git/AI traffic is synthetic loopback HTTP. Only a generated, identity-checked
schema in local reviewer_integration is writable; existing public tables remain
read-only. This measures a local bounded interval, not production provider SLAs.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import sys
import tempfile
import time
import types
import urllib.parse
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


RATE = module('wallclock_rate_helpers', 'verify-review-rate-limit.py')
HELPERS, SHARED, LINUX = RATE.HELPERS, RATE.SHARED, RATE.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
RETRY_SECONDS = 65
MAX_EVENTS = 512
CLEANUP_CHECKS = ('ownedWarsStopped', 'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')


class WallClockFixture(HELPERS.Fixture):
    def __init__(self, limited):
        super().__init__()
        self.limited = limited
        self.release_first_b.set()
        self.release_second_b.set()
        self.git_events = []
        self.limited_event = None

    def observe_git(self, project):
        require(project in ('restart', 'peer'), 'Unexpected synthetic wall-clock repository')
        with self.lock:
            require(len(self.git_events) < MAX_EVENTS, 'Synthetic HTTP event budget exceeded')
            limited = self.limited and self.limited_event is None
            require(not limited or project == 'restart', 'The primary request must establish the cooldown')
            event = (time.monotonic(), project)
            self.git_events.append(event)
            if limited:
                # Before response headers are sent: a conservative monotonic lower
                # bound on receipt, never a claim about the client's exact receipt.
                self.limited_event = (event[0], time.time())
            return limited

    def timing(self):
        with self.lock:
            return self.limited_event, tuple(self.git_events)

    def start(self):
        url = super().start()
        parent = self.server.RequestHandlerClass
        fixture = self

        class Handler(parent):
            def do_GET(self):
                original = self.path
                try:
                    self.check_headers()
                    parts = urllib.parse.urlsplit(original)
                    path = urllib.parse.unquote(parts.path)
                    match = re.match(r'^/api/v4/projects/fixture/(restart|peer)/repository/', path)
                    require(match is not None, 'Unexpected synthetic wall-clock Git route')
                    project = match[1]
                    fixture.note(project + '_git_http')
                    if fixture.observe_git(project):
                        fixture.note('git_429')
                        try:
                            self.send_response(429)
                            self.send_header('Retry-After', str(RETRY_SECONDS))
                            self.send_header('Content-Length', '0')
                            self.send_header('Connection', 'close')
                            self.end_headers()
                        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                            pass
                        finally:
                            self.close_connection = True
                        return
                    self.path = path.replace('/fixture/peer/', '/fixture/restart/') + ('?' + parts.query if parts.query else '')
                    super().do_GET()
                except Exception:
                    fixture.fail('SyntheticWallClockGitRejected')
                    self.reply({'error': 'Synthetic fixture request rejected'}, 400)
                finally:
                    self.path = original

            def do_POST(self):
                if fixture.limited:
                    fixture.fail('UnexpectedAiAtGitOnlyOrigin')
                    self.reply({'error': 'Synthetic fixture request rejected'}, 400)
                    return
                super().do_POST()

        self.server.RequestHandlerClass = Handler
        return url


def no_early_calls(fixture, require_retry=False):
    limited, events = fixture.timing()
    require(limited is not None and events and events[0][0] == limited[0], 'The synthetic 429 timing was not recorded')
    delays = [event[0] - limited[0] for event in events[1:]]
    require(all(delay >= RETRY_SECONDS for delay in delays), 'A Git HTTP request occurred before the real Retry-After interval elapsed')
    require(not require_retry or delays, 'No Git request resumed after the real cooldown')
    return delays


def active_wars(wars):
    require(all(war.process is not None and war.process.poll() is None for war in wars), 'An expected owned WAR stopped during observation')


def wait_until(predicate, deadline, wars, fixture, message):
    while time.monotonic() < deadline:
        active_wars(wars)
        no_early_calls(fixture)
        if predicate():
            no_early_calls(fixture)
            return
        time.sleep(0.2)
    raise VerificationError(message)


def assert_pending(current, original, count):
    RATE.same_request(current, original)
    require(current['request']['state'] == 'QUEUED' and current['request']['attempts'] == 1
            and current['wait']['code'] == 'GIT_RATE_LIMITED' and current['wait']['count'] == count
            and current['wait']['first'] is not None and not current['commits'] and current['issues'] == 0
            and current['cursor'] is None and current['progress'] is None,
            'A delayed request lost its identity or fabricated reviewed work')


def assert_complete(current, original, attempts, count):
    RATE.same_request(current, original)
    require(current['request']['state'] == 'SUCCEEDED' and current['request']['attempts'] == attempts
            and current['wait']['count'] == count and current['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B]
            and current['issues'] == 2 and current['cursor'] == HELPERS.SHA_B
            and current['assignees'] == [original['request']['actor']] * 2
            and current['runs'] == ([{'state': 'FAILED', 'commits': 0}] if attempts == 2 else [])
                + [{'state': 'SUCCEEDED', 'commits': 2}],
            'Completion replaced a request or duplicated/lost reviewed work')


def compare_clock(database, schema, project, fixture, origin):
    RATE.identifiers(schema, project)
    require(re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', origin) is not None, 'Unexpected synthetic clock origin')
    key = hashlib.sha256(origin.encode('ascii')).hexdigest()
    before = time.time()
    try:
        value = json.loads(database.sql("SELECT json_build_object('now',extract(epoch FROM clock_timestamp()),"
            "'available',extract(epoch FROM q.available_at),'first',extract(epoch FROM q.rate_limited_at),"
            "'shared',extract(epoch FROM c.retry_at)) "
            f"FROM {schema}.review_request q JOIN {schema}.integration_cooldown c "
            f"ON c.service='GIT' AND c.origin_hash='{key}' WHERE q.project_id={project};"))
        after = time.time()
        limited, _ = fixture.timing()
        require(limited is not None and all(type(value.get(name)) in (int, float) for name in ('now', 'available', 'first', 'shared')),
                'Invalid isolated cooldown clock sample')
        # SQL/Java timestamps have coarser precision than Python's clock. This
        # tolerance concerns clock comparison only; HTTP no-early uses strict 65s.
        require(before - 0.1 <= value['now'] <= after + 0.1
                and value['shared'] >= limited[1] + RETRY_SECONDS - 0.1
                and value['available'] >= value['shared'] - 0.001
                and limited[1] - 0.1 <= value['first'] <= after + 0.1,
                'The persisted cooldown disagreed with the local response or database clock')
        return {'sharedRetryAfterResponseSeconds': round(value['shared'] - limited[1], 6),
                'databaseClockSampleDurationSeconds': round(after - before, 6)}
    except (TypeError, ValueError, KeyError, AttributeError):
        raise VerificationError('Invalid isolated cooldown clock sample') from None


def login(war):
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    return browser


def exercise(wars, database, schema, x, y, origins, report, deadline):
    a, b = wars
    a.start(False)
    b.start(False)
    browser_a, browser_b = login(a), login(b)
    primary = RATE.register(browser_a, a, origins[0], 'restart')
    peer = RATE.register(browser_a, a, origins[0], 'peer')
    other = RATE.register(browser_a, a, origins[1], 'restart')
    RATE.post_review(browser_a, primary)
    first = RATE.snapshot(database, schema, primary)
    require(first['request']['state'] == 'QUEUED' and first['request']['attempts'] == 0
            and first['request']['source'] == 'MANUAL' and first['request']['actor'] is not None
            and not x.observed() and not y.observed(), 'The paused workers touched a new request')
    a.stop()
    a.start(True)
    HELPERS.bounded_wait(lambda: RATE.snapshot(database, schema, primary)['wait']['code'] == 'GIT_RATE_LIMITED',
                         deadline, 'The primary synthetic Git 429 was not persisted', a.process)
    waiting = RATE.snapshot(database, schema, primary)
    assert_pending(waiting, first, 1)
    require(x.observed() == {'restart_git_http': 1, 'git_429': 1}, 'The first 429 performed extra Git requests')
    report['clockComparison'] = compare_clock(database, schema, primary, x, origins[0])
    a.stop()
    RATE.post_review(browser_b, peer)
    peer_first = RATE.snapshot(database, schema, peer)
    require(peer_first['request']['state'] == 'QUEUED' and peer_first['request']['attempts'] == 0,
            'The stopped-worker peer request was not saved untouched')
    before_peer = x.observed()
    b.stop()
    b.start(True)
    browser_b = login(b)
    wait_until(lambda: RATE.snapshot(database, schema, peer)['wait']['code'] == 'GIT_RATE_LIMITED',
               deadline, [b], x, 'The other WAR did not observe the shared Git cooldown')
    peer_wait = RATE.snapshot(database, schema, peer)
    assert_pending(peer_wait, peer_first, 0)
    require(x.observed() == before_peer and a.process is None, 'The other WAR contacted the origin instead of using its stored cooldown')
    RATE.check_waiting_pages(browser_b, primary, RATE.snapshot(database, schema, primary), 'GIT')
    RATE.check_waiting_pages(browser_b, peer, peer_wait, 'GIT')
    report['checks'].update({'actualGit429CountOne': True, 'differentWarCachedCountZeroWithNoOriginHttp': True,
                            'waitingJspUtcAndNoGuaranteeRendered': True})
    RATE.post_review(browser_b, other)
    # Submission may already be running; identity is captured before waiting and
    # verified again after completion, with no need to pause a worker or alter time.
    other_first = RATE.snapshot(database, schema, other)
    wait_until(lambda: RATE.snapshot(database, schema, other)['request']['state'] == 'SUCCEEDED',
               deadline, [b], x, 'A different origin did not complete while X was waiting')
    assert_complete(RATE.snapshot(database, schema, other), other_first, 1, 0)
    limited, _ = x.timing()
    other_delay = time.monotonic() - limited[0]
    require(other_delay < RETRY_SECONDS and x.observed() == before_peer,
            'The different-origin completion did not occur inside the X cooldown interval')
    report['checks']['singleWorkerReleasedForOtherOrigin'] = True
    report['otherOriginCompletedAfter429Seconds'] = round(other_delay, 6)
    a.start(True)
    active_wars(wars)
    both_delay = time.monotonic() - limited[0]
    require(both_delay < RETRY_SECONDS and x.observed() == before_peer,
            'Both owned WARs were not ready before the real cooldown elapsed')
    report['bothWarsRunningAfter429Seconds'] = round(both_delay, 6)
    report['checks']['bothWarsRunningBeforeCooldownExpiry'] = True
    samples = 0
    while time.monotonic() < deadline:
        active_wars(wars)
        no_early_calls(x)
        current = [RATE.snapshot(database, schema, project) for project in (primary, peer)]
        samples += 1
        if all(item['request']['state'] == 'SUCCEEDED' for item in current):
            break
        require(all(item['request']['state'] in ('QUEUED', 'RUNNING', 'SUCCEEDED') for item in current),
                'A wall-clock request entered an unexpected terminal state')
        time.sleep(0.2)
    else:
        raise VerificationError('Original delayed requests did not complete before the bounded deadline')
    assert_complete(current[0], first, 2, 1)
    assert_complete(current[1], peer_first, 2, 0)
    delays = no_early_calls(x, require_retry=True)
    require(not x.errors and not y.errors and x.observed().get('git_429') == 1
            and y.observed().get('ai_A.java') == 3 and y.observed().get('ai_B.java') == 3
            and x.observed().get('diff_A.java') == 2 and x.observed().get('diff_B.java') == 2,
            'Wall-clock completion duplicated HTTP review work or violated the fixture contract')
    report['checks'].update({'entireRecordedIntervalHasNoEarlyOriginHttp': True,
                            'samePrimaryAndPeerRequestsComplete': True, 'noDuplicateCommitsIssuesOrAiCalls': True,
                            'bothWarsRemainAliveThroughCompletion': True})
    report['firstSubsequentOriginHttpAfter429Seconds'] = round(min(delays), 6)
    report['subsequentOriginHttpCount'] = len(delays)
    report['completionPollSamples'] = samples
    report['requestCounts'] = {'originX': x.observed(), 'originYAndAi': y.observed()}


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port-a', type=int, default=18097)
    parser.add_argument('--port-b', type=int, default=18098)
    parser.add_argument('--timeout-seconds', type=int, default=240)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = getattr(args, name).resolve(strict=True) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and args.port_a != args.port_b
            and all(1024 <= port <= 65535 for port in (args.port_a, args.port_b)), 'Distinct valid loopback WAR ports are required')
    require(120 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120,
            'Invalid bounded wall-clock drill timeout')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Wall-clock evidence must remain in this checkout .local directory')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing wall-clock evidence report')
    for port in (args.port_a, args.port_b):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                raise VerificationError('A requested wall-clock WAR port is occupied') from None
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'wallclock-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    wars, works, fixtures = [], [], []
    database, identity, public_before = None, None, None
    owned, failure, cleanup_failed = False, None, False
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    report['logDirectory'] = str(logs)
    try:
        for node in ('a', 'b'):
            directory = logs / ('node-' + node)
            directory.mkdir(mode=0o700)
            works.append(Path(tempfile.mkdtemp(prefix='owned-work-', dir=directory)))
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), works[0])
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Refusing an unexpected database')
        public_before = RATE.public_fingerprints(database)
        database.sql('CREATE SCHEMA ' + schema + ';')
        owned = True
        captured = SHARED.schema_identity(database, schema)
        require(captured is not None and type(captured.get('oid')) is int and captured['owner'] == database.user,
                'Created wall-clock schema ownership could not be established')
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': captured['oid'], 'owner': database.user, 'marker': marker}
        SHARED.verify_schema(database, schema, identity)
        fixtures.extend((WallClockFixture(True), WallClockFixture(False)))
        origins = [fixture.start() for fixture in fixtures]
        require(origins[0] != origins[1], 'Synthetic Git origins must differ')
        for index, port in enumerate((args.port_a, args.port_b)):
            options = types.SimpleNamespace(**vars(args), port=port)
            wars.append(HELPERS.OwnedWar(options, database, schema, origins[1], works[index], works[index].parent, deadline))
        wars[1].password = wars[0].password
        exercise(wars, database, schema, *fixtures, origins, report, deadline)
    except BaseException as error:
        failure = error
    finally:
        for war in wars:
            try:
                war.stop(force=True)
            except BaseException:
                cleanup_failed = True
        for fixture in fixtures:
            try:
                fixture.close()
            except BaseException:
                cleanup_failed = True
        stopped = not any(war.process is not None and war.process.poll() is None for war in wars)
        if owned and stopped:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned wall-clock schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and not owned:
            try:
                if public_before is not None:
                    require(RATE.public_fingerprints(database) == public_before, 'Original public test tables changed during the wall-clock drill')
                    report['checks']['originalTestTablesPreserved'] = True
                    report['originalTableCount'] = len(public_before)
            except BaseException:
                cleanup_failed = True
            for work in works:
                try:
                    require(work.parent.parent == logs and work.parent.name in ('node-a', 'node-b')
                            and work.name.startswith('owned-work-'), 'Unexpected owned wall-clock work directory')
                    LINUX.checked_path(work, directory=True)
                    shutil.rmtree(work)
                except BaseException:
                    cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedSchemaRemoved': not owned,
                                'ownedWorkRemoved': all(not work.exists() for work in works)})
        report['cleanupFailed'] = cleanup_failed or not stopped or owned
        report['warStarts'] = [war.starts for war in wars]
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned wall-clock resources could not be safely removed')


def main(argv=None):
    args, prior, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'retryAfterSeconds': RETRY_SECONDS, 'sqlTimeAcceleration': False,
              'scope': 'Two real WARs, one owned PostgreSQL schema, two synthetic Git origins and synthetic AI',
              'clockScope': 'Monotonic time immediately before the first 429 headers; all later origin-X Git arrivals must be at least 65 seconds later',
              'limitations': ['Local shared-database behavior only; not production TLS, proxy or provider quota validation',
                              'Slow startup that misses the real 65-second observation window fails this drill']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        prior = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        require(report['cleanupFailed'] is False and all(report['checks'].get(key) is True for key in CLEANUP_CHECKS),
                'Successful wall-clock evidence requires confirmed cleanup and unchanged original tables')
        report['result'] = 'PASS'
        code = 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, (VerificationError, LINUX.SafetyError)) else 'Details withheld; no credentials, SQL rows or HTTP responses reported'
    finally:
        if installed:
            signal.signal(signal.SIGTERM, prior)
    if args is not None:
        try:
            LINUX.checked_path(args.report, allow_missing=True)
            descriptor = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                json.dump(report, stream, indent=2)
                stream.write('\n')
        except (OSError, LINUX.SafetyError):
            print('FAIL: fresh wall-clock verification report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': two-WAR real Retry-After waiting and recovery; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
