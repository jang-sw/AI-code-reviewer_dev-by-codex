#!/usr/bin/env python3
"""Actual WAR 429 waiting/restart drill with synthetic loopback Git and AI only.

Only one newly created UUID schema in local reviewer_integration is written.
Retry-After headers are real synthetic HTTP (AI 60s, Git 30s); SQL expiry of the
owned request/cooldown is deliberate test-time acceleration, not wall-clock proof.
"""
import argparse
import hashlib
import importlib.util
import io
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
import urllib.parse
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


SHARED = module('rate_limit_shared_helpers', 'verify-shared-auth.py')
BACKUP = module('rate_limit_backup_helpers', 'verify-postgres-backup.py')
HELPERS, LINUX = SHARED.HELPERS, SHARED.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent


class RateLimitFixture(HELPERS.Fixture):
    def __init__(self):
        super().__init__()
        self.release_first_b.set()
        self.release_second_b.set()
        self.ai_limited = False
        self.git_armed = False
        self.git_limited = False

    def limit_ai(self, sha):
        with self.lock:
            if sha == HELPERS.SHA_B and not self.ai_limited:
                self.ai_limited = True
                return True
        return False

    def limit_git(self, project, path):
        with self.lock:
            if project == 'restart' and path.endswith('/commits/' + HELPERS.SHA_B + '/diff') and self.git_armed and not self.git_limited:
                self.git_limited = True
                return True
        return False

    def start(self):
        url = super().start()
        parent = self.server.RequestHandlerClass
        fixture = self

        class Handler(parent):
            def limited(self, seconds):
                try:
                    self.send_response(429)
                    self.send_header('Retry-After', str(seconds))
                    self.send_header('Content-Length', '0')
                    self.send_header('Connection', 'close')
                    self.end_headers()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
                finally:
                    self.close_connection = True

            def do_GET(self):
                original = self.path
                try:
                    self.check_headers()
                    url_parts = urllib.parse.urlsplit(original)
                    decoded = urllib.parse.unquote(url_parts.path)
                    match = re.match(r'^/api/v4/projects/fixture/(restart|peer)/repository/', decoded)
                    require(match is not None, 'Unexpected synthetic rate-limit Git route')
                    project = match[1]
                    fixture.note(project + '_git_http')
                    for sha, name in HELPERS.FILES.items():
                        if decoded.endswith('/commits/' + sha + '/diff'):
                            fixture.note(project + '_diff_' + name)
                    if fixture.limit_git(project, decoded):
                        fixture.note('git_429')
                        self.limited(30)
                        return
                    self.path = decoded.replace('/fixture/peer/', '/fixture/restart/') + ('?' + url_parts.query if url_parts.query else '')
                    super().do_GET()
                except Exception:
                    fixture.fail('SyntheticGitRequestRejected')
                    self.reply({'error': 'Synthetic fixture request rejected'}, 400)
                finally:
                    self.path = original

            def do_POST(self):
                original = self.rfile
                try:
                    self.check_headers()
                    require(self.path == '/api/chat', 'Unexpected synthetic rate-limit AI route')
                    length = int(self.headers.get('Content-Length', '0'))
                    require(0 < length <= 65536, 'Unexpected synthetic AI request size')
                    self.connection.settimeout(5)
                    payload = original.read(length)
                    require(len(payload) == length, 'Truncated synthetic AI request')
                    parsed = json.loads(payload)
                    messages = parsed.get('messages')
                    require(isinstance(messages, list) and len(messages) == 2, 'Unexpected synthetic AI messages')
                    sha = json.loads(messages[1]['content']).get('commitSha')
                    require(sha in HELPERS.FILES, 'Unexpected synthetic AI commit')
                    if fixture.limit_ai(sha):
                        fixture.note('ai_' + HELPERS.FILES[sha])
                        fixture.note('ai_429')
                        self.limited(60)
                        return
                    self.rfile = io.BytesIO(payload)
                    super().do_POST()
                except Exception:
                    fixture.fail('SyntheticAiRequestRejected')
                    self.reply({'error': 'Synthetic fixture request rejected'}, 400)
                finally:
                    self.rfile = original

        self.server.RequestHandlerClass = Handler
        return url


def identifiers(schema, project):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None and type(project) is int and project > 0,
            'Unexpected invocation-owned review identifiers')


def public_fingerprints(database):
    values = {}
    for table in BACKUP.TABLES:
        require(re.fullmatch(r'[a-z_]+', table) is not None, 'Unexpected baseline comparison table')
        value = database.sql("SELECT count(*)::text || ':' || md5(coalesce("
            "string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text), '')) FROM public." + table + ' t;')
        require(re.fullmatch(r'[0-9]+:[a-f0-9]{32}', value) is not None, 'Invalid baseline comparison result')
        values[table] = value
    return values


def snapshot(database, schema, project):
    identifiers(schema, project)
    result = database.snapshot(schema, project)
    try:
        result['wait'] = json.loads(database.sql(f"SELECT json_build_object('requestedAt',requested_at,'availableAt',available_at,"
            "'availableLabel',to_char(available_at AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS'),"
            "'future',available_at>clock_timestamp(),'code',result_code,'count',rate_limit_count,'first',rate_limited_at) "
            f"FROM {schema}.review_request WHERE project_id={project};"))
    except (ValueError, TypeError):
        raise VerificationError('Invalid synthetic request snapshot') from None
    return result


def same_request(current, original):
    require(all(current['request'][key] == original['request'][key] for key in ('requestId', 'actor', 'source'))
            and current['wait']['requestedAt'] == original['wait']['requestedAt'],
            'Rate-limit handling replaced the original request actor source or reception time')


def assert_waiting(current, original, service, actual_count):
    require(service in ('GIT', 'AI'), 'Unexpected synthetic cooldown service')
    same_request(current, original)
    require(current['request']['state'] == 'QUEUED' and current['wait']['code'] == service + '_RATE_LIMITED'
            and current['wait']['count'] == actual_count and current['wait']['first'] is not None
            and current['commits'] == [HELPERS.SHA_A] and current['issues'] == 1 and current['cursor'] is None
            and current['assignees'] == [original['request']['actor']] and current['progress'] is None,
            'Waiting state lost saved commit A, assignment, cursor or rate-limit provenance')


def check_waiting_pages(browser, project, state, service):
    for path in ('/projects/' + str(project), '/reviews?projectId=' + str(project)):
        _, page = browser.request(path)
        require('data-request-state="QUEUED"' in page and 'id="review-progress"' not in page
                and ('Git 서버' if service == 'GIT' else 'AI 서비스') + ' 호출 제한' in page
                and state['wait']['availableLabel'] in page and '재시도 가능 시각 (UTC)' in page
                and '실행이나 완료를 보장하지 않' in page,
                'Packaged JSP did not render bounded rate-limit waiting and UTC retry guidance')


def check_exhausted_pages(browser, project):
    for path in ('/projects/' + str(project), '/reviews?projectId=' + str(project)):
        _, page = browser.request(path)
        require('data-request-state="FAILED"' in page and '자동 재시도를 중단' in page
                and '직접 리뷰' in page and '남은 이력은 다음 예약 요청' not in page
                and '자동 리뷰가 켜져 있어요' not in page,
                'Packaged JSP did not render manual recovery guidance for synthetic exhaustion')


def register(browser, war, fixture_url, repository):
    require(repository in ('restart', 'peer'), 'Unexpected synthetic repository')
    _, page = browser.request('/projects')
    location, page = browser.request('/projects', {'_csrf': HELPERS.csrf(page), 'name': 'Synthetic rate-limit ' + repository,
        'repositoryUrl': fixture_url + '/fixture/' + repository, 'reviewBranch': ''})
    match = re.fullmatch(re.escape('/' + war.schema + '/projects/') + r'([0-9]+)', urllib.parse.urlsplit(location).path)
    require(match is not None, 'Synthetic project registration failed')
    project = int(match[1])
    browser.request('/admin/projects/' + str(project) + '/approve', {'_csrf': HELPERS.csrf(page)})
    return project


def post_review(browser, project):
    _, page = browser.request('/projects/' + str(project))
    browser.request('/projects/' + str(project) + '/review', {'_csrf': HELPERS.csrf(page)})


def expire_owned(database, schema, identity, project, service, fixture_url):
    identifiers(schema, project)
    require(service in ('GIT', 'AI') and re.fullmatch(r'http://127\.0\.0\.1:[0-9]{1,5}', fixture_url) is not None,
            'Unexpected synthetic expiry target')
    SHARED.verify_schema(database, schema, identity)
    key = hashlib.sha256(fixture_url.encode('ascii')).hexdigest()
    database.sql(f"BEGIN; UPDATE {schema}.integration_cooldown SET retry_at=clock_timestamp()-INTERVAL '1 second' "
        f"WHERE service='{service}' AND origin_hash='{key}'; "
        f"UPDATE {schema}.review_request SET available_at=clock_timestamp()-INTERVAL '1 second' "
        f"WHERE project_id={project} AND state='QUEUED' AND result_code='{service}_RATE_LIMITED'; COMMIT;")


def quiet_wait(fixture, war, deadline, seconds=6):
    before = fixture.observed()
    end = min(deadline, time.monotonic() + seconds)
    require(end - time.monotonic() >= seconds - 0.1, 'Insufficient deadline for the synthetic no-call observation')
    while time.monotonic() < end:
        require(war.process.poll() is None and fixture.observed() == before, 'A waiting request made unexpected external HTTP calls')
        time.sleep(0.15)
    require(fixture.observed() == before, 'A waiting request made unexpected external HTTP calls')


def exercise(war, database, schema, identity, fixture, fixture_url, report, deadline):
    war.start(False)
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    primary = register(browser, war, fixture_url, 'restart')
    peer = register(browser, war, fixture_url, 'peer')
    post_review(browser, primary)
    original = snapshot(database, schema, primary)
    require(original['request']['state'] == 'QUEUED' and original['request']['attempts'] == 0
            and original['request']['source'] == 'MANUAL' and original['request']['actor'] is not None
            and not original['commits'] and not fixture.observed(), 'Worker pause did not preserve a fresh untouched request')
    war.stop()
    war.start(True)
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    HELPERS.bounded_wait(lambda: snapshot(database, schema, primary)['wait']['code'] == 'AI_RATE_LIMITED',
                         deadline, 'The synthetic AI 429 was not persisted', war.process)
    waiting = snapshot(database, schema, primary)
    assert_waiting(waiting, original, 'AI', 1)
    require(waiting['request']['attempts'] == 1 and fixture.observed().get('ai_429') == 1,
            'The first AI rate limit did not produce exactly one execution attempt')
    check_waiting_pages(browser, primary, waiting, 'AI')
    counts = fixture.observed()
    post_review(browser, peer)
    HELPERS.bounded_wait(lambda: snapshot(database, schema, peer)['wait']['code'] == 'AI_RATE_LIMITED',
                         deadline, 'A peer project did not observe the shared origin cooldown', war.process)
    peer_wait = snapshot(database, schema, peer)
    require(peer_wait['wait']['count'] == 0 and not peer_wait['commits'] and peer_wait['issues'] == 0
            and all(fixture.observed().get(key, 0) == counts.get(key, 0) for key in ('ai_A.java', 'ai_B.java', 'ai_429')),
            'A shared-origin cooldown made another AI HTTP request or fabricated reviewed work')
    post_review(browser, primary)
    require(snapshot(database, schema, primary) == waiting, 'An active manual request did not coalesce without mutation')
    _, page = browser.request('/projects/' + str(peer))
    browser.request('/admin/projects/' + str(peer) + '/pause', {'_csrf': HELPERS.csrf(page)})
    counts = fixture.observed()
    war.stop()
    war.start(True)
    require(snapshot(database, schema, primary) == waiting and waiting['wait']['future'] is True,
            'Restart replaced the persisted rate-limit waiting state')
    quiet_wait(fixture, war, deadline)
    require(fixture.observed() == counts, 'Restart bypassed the stored external-service cooldown')
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    check_waiting_pages(browser, primary, snapshot(database, schema, primary), 'AI')
    report['checks'].update({'actualAi429PreservesRequestAndCommitA': True, 'sameOriginPeerMakesNoAiCall': True,
                            'activeManualRequestCoalesces': True, 'restartPreservesWaitAndNoHttpCalls': True,
                            'aiWaitingJspAndUtcRetryRendered': True})
    with fixture.lock:
        fixture.git_armed = True
    expire_owned(database, schema, identity, primary, 'AI', fixture_url)
    HELPERS.bounded_wait(lambda: snapshot(database, schema, primary)['wait']['code'] == 'GIT_RATE_LIMITED',
                         deadline, 'The synthetic Git 429 was not persisted', war.process)
    git_wait = snapshot(database, schema, primary)
    assert_waiting(git_wait, original, 'GIT', 2)
    require(git_wait['wait']['first'] == waiting['wait']['first'], 'Retry replaced the first rate-limit observation')
    check_waiting_pages(browser, primary, git_wait, 'GIT')
    require(fixture.observed().get('git_429') == 1 and fixture.observed().get('ai_B.java') == 1,
            'Git rate limiting unexpectedly invoked AI or retried its own 429')
    expire_owned(database, schema, identity, primary, 'GIT', fixture_url)
    HELPERS.bounded_wait(lambda: snapshot(database, schema, primary)['request']['state'] == 'SUCCEEDED',
                         deadline, 'The unfinished commit did not complete after synthetic expiry', war.process)
    final = snapshot(database, schema, primary)
    same_request(final, original)
    require(final['request']['attempts'] == 3 and final['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B]
            and final['issues'] == 2 and final['cursor'] == HELPERS.SHA_B
            and final['assignees'] == [original['request']['actor']] * 2
            and final['runs'] == [{'state': 'FAILED', 'commits': 1}, {'state': 'FAILED', 'commits': 0}, {'state': 'SUCCEEDED', 'commits': 1}],
            'Rate-limit recovery duplicated or lost commits, issues, attempts or the checkpoint')
    counts = fixture.observed()
    require(counts.get('ai_A.java') == 1 and counts.get('ai_B.java') == 2
            and counts.get('restart_diff_A.java') == 1 and counts.get('restart_diff_B.java') == 3
            and not fixture.errors, 'Saved commit A was repeated or unfinished B was not retried exactly')
    report['checks'].update({'actualGit429PreservesRequestAndSavedWork': True, 'gitWaitingJspAndUtcRetryRendered': True,
                            'onlyUnfinishedCommitCompletesAfterSqlExpiry': True, 'exactIssuesAndCheckpoint': True})
    report['requestCounts'] = counts
    report['attempts'] = final['request']['attempts']
    SHARED.verify_schema(database, schema, identity)
    require(database.sql(f"WITH changed AS (UPDATE {schema}.review_request SET state='FAILED',result_code='RATE_LIMIT_EXHAUSTED',"
        "rate_limit_count=6,finished_at=clock_timestamp() "
        f"WHERE project_id={primary} AND state='SUCCEEDED' RETURNING 1) SELECT count(*)::text FROM changed;") == '1',
        'The owned completed request could not enter the synthetic terminal UI fixture')
    check_exhausted_pages(browser, primary)
    require(fixture.observed() == counts, 'The synthetic terminal UI check made unexpected external calls')
    report['checks']['syntheticExhaustedJspRequiresManualRequest'] = True


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=180)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = getattr(args, name).resolve(strict=True) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and 1024 <= args.port <= 65535
            and 90 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120, 'Invalid bounded rate-limit drill arguments')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Rate-limit evidence must remain in this checkout .local directory')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing rate-limit evidence report')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(('127.0.0.1', args.port))
        except OSError:
            raise VerificationError('The requested loopback WAR port is occupied') from None
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'rate-limit-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    work = Path(tempfile.mkdtemp(prefix='owned-work-', dir=logs))
    database, identity, war, public_before = None, None, None, None
    fixture = RateLimitFixture()
    owned, failure, cleanup_failed = False, None, False
    report['logDirectory'] = str(logs)
    try:
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work)
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Refusing an unexpected database')
        public_before = public_fingerprints(database)
        database.sql('CREATE SCHEMA ' + schema + ';')
        owned = True
        captured = SHARED.schema_identity(database, schema)
        require(captured is not None and type(captured.get('oid')) is int and captured['owner'] == database.user,
                'Created rate-limit schema ownership could not be established')
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': captured['oid'], 'owner': database.user, 'marker': marker}
        SHARED.verify_schema(database, schema, identity)
        fixture_url = fixture.start()
        war = HELPERS.OwnedWar(args, database, schema, fixture_url, work, logs, deadline)
        exercise(war, database, schema, identity, fixture, fixture_url, report, deadline)
    except BaseException as error:
        failure = error
    finally:
        try:
            if war is not None:
                war.stop(force=True)
        except BaseException:
            cleanup_failed = True
        try:
            fixture.close()
        except BaseException:
            cleanup_failed = True
        stopped = war is None or war.process is None or war.process.poll() is not None
        if owned and stopped:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned rate-limit schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and not owned:
            try:
                if public_before is not None:
                    require(public_fingerprints(database) == public_before, 'The original public test tables changed during this drill')
                    report['checks']['originalTestTablesPreserved'] = True
                    report['originalTableCount'] = len(public_before)
            except BaseException:
                cleanup_failed = True
            try:
                require(work.parent == logs and work.name.startswith('owned-work-'), 'Unexpected owned rate-limit work directory')
                LINUX.checked_path(work, directory=True)
                shutil.rmtree(work)
            except BaseException:
                cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedSchemaRemoved': not owned, 'ownedWorkRemoved': not work.exists()})
        report['cleanupFailed'] = cleanup_failed or not stopped or owned
        report['warStarts'] = war.starts if war is not None else 0
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned rate-limit resources could not be safely removed')


def main(argv=None):
    args, prior, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'scope': 'One actual WAR restarted twice; synthetic loopback Git/AI and one owned PostgreSQL schema',
              'sqlTimeAcceleration': 'Only owned primary request and matching service-origin cooldown expiry is advanced',
              'limitations': ['Six-second no-call observation is not long-duration rate-limit validation',
                              'SQL expiry is synthetic; real provider clocks, quotas and 24-hour limits are not exercised',
                              'Terminal UI uses an owned synthetic SQL state with scheduling disabled; it is not a quota exhaustion test']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        prior = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        require(report['cleanupFailed'] is False and all(report['checks'].get(key) is True
                for key in ('ownedWarsStopped', 'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')),
                'Successful evidence requires confirmed cleanup and unchanged original tables')
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
            print('FAIL: fresh rate-limit verification report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': synthetic Git/AI rate-limit waiting and recovery; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
