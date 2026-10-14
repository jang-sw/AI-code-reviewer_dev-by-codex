#!/usr/bin/env python3
"""Opt-in actual WAR drill: a live PostgreSQL connection loses application bytes.

Only a new UUID schema is written. The observer connects directly to the local
test DB; only the owned WAR uses a bounded loopback TCP proxy. No DB process,
firewall, external Git/AI service, or system network setting is changed.
"""
import argparse
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
import urllib.error
import urllib.request
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


RATE = module('network_rate_helpers', 'verify-review-rate-limit.py')
PROXY = module('network_proxy_helpers', 'review-network-fixture.py')
RECOVERY = module('network_db_helpers', 'verify-review-db-recovery.py')
HELPERS, SHARED, LINUX = RATE.HELPERS, RATE.SHARED, RATE.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent


def snapshot(database, schema, project):
    current = database.snapshot(schema, project)
    current['ownership'] = json.loads(database.sql(
        "SELECT json_build_object('requestedAt',requested_at,'claim',claim_token,'run',run_id) "
        f"FROM {schema}.review_request WHERE project_id={project};"))
    return current


def assert_preserved(current, original):
    require(current['request'] == original['request'] and current['ownership'] == original['ownership']
            and current['commits'] == [HELPERS.SHA_A] and current['issues'] == 1
            and current['cursor'] is None and current['runs'] == [{'state': 'RUNNING', 'commits': 1}]
            and current['progress'] == original['progress'],
            'The disconnected request, claim, saved commit or progress changed unexpectedly')


def server_identity(database):
    # Used only for equality; no PID, address, SQL or connection settings enter the report.
    return database.sql("SELECT pg_postmaster_start_time()::text || ':' || "
                        "split_part(pg_read_file('postmaster.pid',0,16384),chr(10),1);")


def owned_sessions(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned session identity')
    return int(database.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='" + schema + "';"))


def probe(base, name, expected_status, expected_state):
    require(name in ('liveness', 'readiness'), 'Invalid health probe')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(base + '/actuator/health/' + name, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read(4097)
        require(response.status == expected_status and len(body) <= 4096
                and json.loads(body) == {'status': expected_state},
                'Public health probe returned an unexpected status or exposed details')


def exercise(war, database, schema, fixture, fixture_url, proxy, args, report, deadline):
    war.start(True)
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    project = RATE.register(browser, war, fixture_url, 'restart')
    RATE.post_review(browser, project)
    HELPERS.bounded_wait(fixture.first_b_entered.is_set, deadline,
                         'The first unfinished AI response was not reached', war.process)
    initial = snapshot(database, schema, project)
    require(initial['request']['state'] == 'RUNNING' and initial['request']['attempts'] == 1
            and initial['request']['source'] == 'MANUAL' and initial['request']['actor'] is not None
            and initial['progress']['stage'] == 'REVIEWING' and initial['progress']['saved'] == 1
            and initial['progress']['lastSavedAt'] is not None,
            'The initial actual review did not save exactly commit A before the fault')
    assert_preserved(initial, initial)
    require(owned_sessions(database, schema) > 0, 'The owned WAR sessions could not be identified')
    probe(war.base, 'readiness', 200, 'UP')
    identity = server_identity(database)
    proxy.pause()
    started = time.monotonic()
    require(proxy.metrics()['mode'] == 'BLACKHOLE', 'Proxy did not confirm the disconnected mode')
    fixture.release_first_b.set()
    probe(war.base, 'liveness', 200, 'UP')
    probe(war.base, 'readiness', 503, 'DOWN')
    failed_after = None
    observations = 0
    while True:
        require(time.monotonic() < deadline and war.process.poll() is None,
                'The actual WAR stopped or exceeded the disconnected observation deadline')
        assert_preserved(snapshot(database, schema, project), initial)
        require(server_identity(database) == identity, 'The observer database restarted during the connection fault')
        counts = fixture.observed()
        require(counts.get('ai_A.java') == counts.get('ai_B.java') == 1 and not fixture.errors,
                'The disconnected attempt repeated AI or lost synthetic fixture validation')
        observations += 1
        if failed_after is None and RECOVERY.worker_failed(war.logs / 'war-start-1.log', project):
            failed_after = round(time.monotonic() - started, 3)
        elapsed = time.monotonic() - started
        if elapsed >= args.blackhole_seconds:
            break
        time.sleep(min(1, args.blackhole_seconds - elapsed))
    require(failed_after is not None, 'The worker did not report a database failure before the connection healed')
    report['blackholeSecondsObserved'] = round(time.monotonic() - started, 3)
    report['workerFailureAfterSeconds'] = failed_after
    report['preservedStateObservations'] = observations
    report['checks'].update({'liveDatabaseStayedAvailable': True, 'unchangedRequestClaimAndSavedA': True,
                            'noCommitBStoredDuringFault': True, 'workerFailedBeforeHealing': True,
                            'disconnectedReadiness503Liveness200StatusOnly': True})
    before_heal = proxy.metrics()
    require(before_heal['droppedBytes'] > 0 and before_heal['failed'] is False,
            'The connection fault dropped no bytes or the proxy itself failed')
    proxy.heal()
    require(proxy.metrics()['mode'] == 'FORWARD', 'The proxy did not confirm reconnection')
    HELPERS.bounded_wait(fixture.second_b_entered.is_set, deadline,
                         'The same live WAR did not recover the unfinished request', war.process)
    recovering = snapshot(database, schema, project)
    require(all(recovering['request'][key] == initial['request'][key]
                for key in ('requestId', 'actor', 'source'))
            and recovering['ownership']['requestedAt'] == initial['ownership']['requestedAt']
            and recovering['ownership']['claim'] != initial['ownership']['claim']
            and recovering['ownership']['run'] != initial['ownership']['run']
            and recovering['request']['state'] == 'RUNNING' and recovering['request']['attempts'] == 2
            and recovering['commits'] == [HELPERS.SHA_A] and recovering['issues'] == 1 and recovering['cursor'] is None
            and recovering['runs'] == [{'state': 'FAILED', 'commits': 1}, {'state': 'RUNNING', 'commits': 0}]
            and recovering['progress']['stage'] == 'REVIEWING' and recovering['progress']['saved'] == 0
            and recovering['progress']['lastSavedAt'] is None,
            'Reconnection replaced the request or lost per-attempt ownership and saved work')
    probe(war.base, 'readiness', 200, 'UP')
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    for path in ('/projects/' + str(project), '/reviews?projectId=' + str(project)):
        _, page = browser.request(path)
        HELPERS.verify_progress_page(page, 'REVIEWING', 0)
    fixture.release_second_b.set()
    HELPERS.bounded_wait(lambda: snapshot(database, schema, project)['request']['state'] == 'SUCCEEDED',
                         deadline, 'The reconnected request did not complete', war.process)
    final = snapshot(database, schema, project)
    require(final['request']['requestId'] == initial['request']['requestId']
            and final['request']['actor'] == initial['request']['actor'] and final['request']['attempts'] == 2
            and final['ownership'] == recovering['ownership']
            and final['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B] and final['issues'] == 2
            and final['assignees'] == [initial['request']['actor']] * 2 and final['cursor'] == HELPERS.SHA_B
            and final['runs'] == [{'state': 'FAILED', 'commits': 1}, {'state': 'SUCCEEDED', 'commits': 1}]
            and final['progress']['stage'] == 'FINALIZING' and final['progress']['saved'] == 1
            and final['progress']['lastSavedAt'] is not None,
            'Recovery duplicated or lost a request, commit, assignment, checkpoint or progress')
    counts = fixture.observed()
    require(all(counts.get(kind + '_A.java') == 1 and counts.get(kind + '_B.java') == 2
                for kind in ('ai', 'detail', 'diff')) and not fixture.errors,
            'Recovery repeated saved content or failed to retry only unfinished commit B')
    require(war.starts == 1 and war.process.poll() is None and server_identity(database) == identity,
            'Recovery restarted an actual WAR or database instead of healing connections')
    report['checks'].update({'sameLiveWarRecovered': True, 'requestActorAndReceptionPreserved': True,
                            'replacementClaimIssued': True, 'savedAReusedUnfinishedBRetried': True,
                            'twoIssuesAndSafeCheckpoint': True, 'newAttemptProgressAndJspCorrect': True,
                            'readinessReturnedTo200': True, 'databaseProcessUnchanged': True})
    report['requestCounts'] = counts
    report['attempts'] = 2


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--blackhole-seconds', type=int, default=65)
    parser.add_argument('--timeout-seconds', type=int, default=300)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        # Preserve a configured executable symlink: Debian psql dispatches by argv[0].
        value = LINUX.checked_path(getattr(args, name)) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and 1024 <= args.port <= 65535
            and 45 <= args.blackhole_seconds <= 180 and args.blackhole_seconds + 90 <= args.timeout_seconds <= 600
            and 15 <= args.startup_timeout_seconds <= 120, 'Invalid bounded connection-fault drill arguments')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Network drill evidence must stay in this checkout .local')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing network drill report')
    LINUX.unused_port(args.port)
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'network-recovery-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    work = Path(tempfile.mkdtemp(prefix='owned-work-', dir=logs))
    database, identity, war, proxy, original_tables, original_server = None, None, None, None, None, None
    fixture = HELPERS.Fixture()
    owned, failure, cleanup_failed = False, None, False
    report['logDirectory'] = str(logs)
    try:
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work)
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Unexpected observer database')
        original_tables = RATE.public_fingerprints(database)
        original_server = server_identity(database)
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
        proxy = PROXY.OwnedTcpProxy(target_port).start()
        routed = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:' + str(proxy.port) + '/reviewer_integration',
                                      user=database.user, password=database.password, sql=database.sql)
        fixture_url = fixture.start()
        war = HELPERS.OwnedWar(args, routed, schema, fixture_url, work, logs, deadline)
        exercise(war, database, schema, fixture, fixture_url, proxy, args, report, deadline)
    except BaseException as error:
        failure = error
    finally:
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
                require(proxy_closed, 'The owned TCP proxy did not finish cleanup')
        except BaseException:
            cleanup_failed = True
        stopped = war is None or war.process is None or war.process.poll() is not None
        sessions_gone = False
        if database is not None and stopped and proxy_closed:
            try:
                HELPERS.bounded_wait(lambda: owned_sessions(database, schema) == 0, time.monotonic() + 10,
                                     'The owned WAR still has PostgreSQL sessions')
                sessions_gone = True
            except BaseException:
                cleanup_failed = True
        if owned and stopped and proxy_closed and sessions_gone:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and proxy_closed and not owned:
            try:
                if original_tables is not None:
                    require(RATE.public_fingerprints(database) == original_tables, 'Original public test tables changed')
                    require(server_identity(database) == original_server, 'Observer database process changed')
                    report['checks']['originalTestTablesPreserved'] = True
                    report['originalTableCount'] = len(original_tables)
                require(work.parent == logs and work.name.startswith('owned-work-'), 'Unexpected owned work directory')
                LINUX.checked_path(work, directory=True)
                shutil.rmtree(work)
            except BaseException:
                cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedProxyStopped': proxy_closed,
                                'ownedDatabaseSessionsGone': sessions_gone, 'ownedSchemaRemoved': not owned,
                                'ownedWorkRemoved': not work.exists()})
        report['cleanupFailed'] = cleanup_failed or not stopped or not proxy_closed or owned
        report['warStarts'] = war.starts if war is not None else 0
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned network recovery resources could not be safely removed')


def main(argv=None):
    args, previous, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'scope': 'One live WAR and live local PostgreSQL; synthetic Git/AI; bidirectional application-byte blackhole',
              'limits': {'queryTimeoutSeconds': 5, 'socketTimeoutSeconds': 10, 'connectTimeoutSeconds': 5},
              'limitations': ['Not kernel packet loss, TCP retransmission, or an operational recovery SLA',
                              'No commit acknowledgement-only loss, database process stop or real external AI']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        previous = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        require(report['cleanupFailed'] is False and all(report['checks'].get(key) is True
                for key in ('ownedWarsStopped', 'ownedProxyStopped', 'ownedDatabaseSessionsGone',
                            'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')),
                'Successful evidence requires complete cleanup and unchanged original tables')
        report['result'] = 'PASS'
        code = 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, (VerificationError, LINUX.SafetyError, PROXY.ProxyError)) \
            else 'Private error details withheld'
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
            print('FAIL: fresh network recovery report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': bounded live database connection recovery; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
