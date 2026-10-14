#!/usr/bin/env python3
"""Opt-in real 30-minute idle-session expiry and safe form recovery drill.

The packaged default remains 30m. Only one owned WAR and a UUID schema in
loopback reviewer_integration are used. Git/AI are synthetic; no clock is changed.
"""
import argparse
import collections
from html.parser import HTMLParser
import http.cookiejar
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


NETWORK = module('session_expiry_network_helpers', 'verify-review-network-recovery.py')
HELPERS, SHARED, RATE, LINUX = NETWORK.HELPERS, NETWORK.SHARED, NETWORK.RATE, NETWORK.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
IDLE_SECONDS = 1810
CONTROL_SECONDS = 1740
BUSINESS_TABLES = tuple(table for table in RATE.BACKUP.TABLES if table not in ('auth_attempt_policy', 'auth_attempt_bucket'))
CLEANUP = ('ownedWarsStopped', 'ownedFixtureStopped', 'ownedDatabaseSessionsGone', 'ownedSchemaRemoved', 'ownedWorkRemoved',
           'originalTestTablesPreserved', 'databaseProcessUnchanged')
BEHAVIOR = ('defaultThirtyMinuteTimeout', 'publicSignupAndAdministratorApproval', 'userProjectAndAssignedIssues',
            'userRoleUiAndAccess', 'victimsUnrequestedDuringIdle', 'controlAuthenticatedBeforeExpiry',
            'expiredPostRejected', 'expiredGetRedirectedToLogin', 'storedWorkPreservedAfterExpiry',
            'newSessionAfterLogin', 'oldTokenRejectedAfterLogin', 'freshFormSavedExactlyOnce', 'sameLiveWarAndDatabase')
Response = collections.namedtuple('Response', 'status location headers body')


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class Browser:
    """A separate cookie jar, no redirects, and an explicit no-HTTP idle guard."""
    def __init__(self, base, deadline):
        self.base, self.deadline = base, deadline
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(self.jar), NoRedirects())
        self.requests, self.idle_attempts, self.idle = 0, 0, False

    def request(self, path, fields=None):
        if self.idle:
            self.idle_attempts += 1
            raise VerificationError('A protected idle browser was asked to make an HTTP request')
        require(path.startswith('/') and not path.startswith('//') and '\\' not in path,
                'Invalid owned session HTTP route')
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, 'The session verification exceeded its total deadline')
        data = None if fields is None else urllib.parse.urlencode(fields).encode('utf-8')
        self.requests += 1
        try:
            try:
                response = self.opener.open(urllib.request.Request(self.base + path, data=data), timeout=min(10, remaining))
            except urllib.error.HTTPError as error:
                response = error
            with response:
                raw = response.read(2_000_001)
                require(len(raw) <= 2_000_000, 'An owned session response exceeded its bounded size')
                return Response(response.status, response.headers.get('Location', ''), response.headers, raw.decode('utf-8'))
        except (urllib.error.URLError, TimeoutError, UnicodeError, OSError):
            raise VerificationError('An owned session HTTP request failed; private details withheld') from None

    def cookie(self):
        values = [cookie.value for cookie in self.jar if cookie.name == 'JSESSIONID'
                  and cookie.path == urllib.parse.urlsplit(self.base).path]
        require(len(values) == 1 and bool(values[0]), 'The owned browser has no unique context session cookie')
        return values[0]

    def get(self, path):
        response = self.request(path)
        require(response.status == 200, 'An owned authenticated form was not available')
        return response.body

    def login(self, username, password, success=True):
        form = self.get('/login')
        response = self.request('/login', {'_csrf': HELPERS.csrf(form), 'username': username, 'password': password})
        if success:
            redirect(response, self.base, '/')
        else:
            redirect(response, self.base, '/login', 'error')


def redirect(response, base, path, query=''):
    location = urllib.parse.urlsplit(urllib.parse.urljoin(base + '/', response.location))
    origin = urllib.parse.urlsplit(base)
    require(response.status == 302 and bool(response.location)
            and (location.scheme, location.netloc) == (origin.scheme, origin.netloc)
            and location.path == origin.path + path and location.query == query and not location.fragment,
            'The owned application returned an unexpected redirect')


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hrefs = []

    def handle_starttag(self, tag, attrs):
        if tag == 'a':
            self.hrefs.append(dict(attrs).get('href'))


def csrf_denial(response, base, private_values):
    links = Links()
    links.feed(response.body)
    context = urllib.parse.urlsplit(base).path
    require(response.status == 403 and 'no-store' in response.headers.get('Cache-Control', '').lower()
            and '양식을 다시 열어 주세요' in response.body and '제출 내용을 처리하지 않았습니다' in response.body
            and context + '/issues' in links.hrefs and context + '/login' in links.hrefs,
            'An expired form omitted its fixed safe recovery guidance')
    require(all(value not in response.body for value in private_values if value)
            and not any(text in response.body.lower() for text in ('sqlstate', 'jdbc:', 'exception', 'select ')),
            'An expired form response reflected private input or implementation details')


def user_ui(browser):
    page = browser.get('/')
    require('내 이슈' in page and '프로젝트' in page and '관리자 메뉴' not in page and '가입 승인' not in page,
            'The ordinary user navigation exposed administrator actions or omitted daily work')
    response = browser.request('/admin/users')
    links = Links()
    links.feed(response.body)
    require(response.status == 403 and '요청 권한을 확인할 수 없습니다' in response.body
            and '양식을 다시 열어 주세요' not in response.body
            and urllib.parse.urlsplit(browser.base).path + '/' in links.hrefs,
            'The ordinary user permission denial omitted safe dashboard recovery')


def business_fingerprints(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned session schema')
    result = {}
    for table in BUSINESS_TABLES:
        require(re.fullmatch(r'[a-z_]+', table) is not None, 'Invalid fixed business comparison table')
        value = database.sql("SELECT count(*)::text || ':' || md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' "
            'ORDER BY row_to_json(t)::text),\'\')) FROM ' + schema + '.' + table + ' t;')
        require(re.fullmatch(r'[0-9]+:[a-f0-9]{32}', value) is not None, 'Invalid business comparison result')
        result[table] = value
    return result


def issue_rows(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned issue schema')
    return json.loads(database.sql('SELECT coalesce(json_agg(row_to_json(t) ORDER BY id),\'[]\'::json) FROM '
                                 + schema + '.review_issue t;'))


def audit_rows(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid owned audit schema')
    return json.loads(database.sql('SELECT coalesce(json_agg(row_to_json(t) ORDER BY id),\'[]\'::json) FROM '
                                 + schema + '.audit_event t;'))


def verify_packaged_timeout(war):
    try:
        with zipfile.ZipFile(war) as archive:
            require(sum(item.filename == 'WEB-INF/classes/application.properties' for item in archive.infolist()) == 1,
                    'The configured WAR must contain exactly one packaged application properties entry')
            info = archive.getinfo('WEB-INF/classes/application.properties')
            require(info.file_size <= 65536, 'Packaged application properties exceed the fixture limit')
            with archive.open(info) as stream:
                raw = stream.read(65537)
            require(len(raw) <= 65536, 'Packaged application properties exceed the fixture read limit')
            content = raw.decode('utf-8')
    except (OSError, KeyError, zipfile.BadZipFile, UnicodeError):
        raise VerificationError('The configured WAR has no readable bounded packaged application properties') from None
    values = [line.strip().split('=', 1)[1].strip() for line in content.splitlines()
              if re.match(r'^\s*server\.servlet\.session\.timeout\s*=', line)]
    require(values == ['30m'], 'The packaged session timeout must remain exactly the default 30m')


def prepare_business(war, database, schema, fixture_url, deadline, report):
    admin = Browser(war.base, deadline)
    admin.login(war.username, war.password)
    username, password = 'expiryuser' + uuid.uuid4().hex[:16], 'Session-fixture!' + secrets.token_hex(16)
    victim = Browser(war.base, deadline)
    response = victim.request('/signup', {'_csrf': HELPERS.csrf(victim.get('/signup')), 'username': username,
        'password': password, 'confirmPassword': password, 'gitUsername': username})
    redirect(response, war.base, '/signup', 'submitted')
    raw = database.sql("SELECT json_build_object('id',id,'role',role,'approval',approval_status,'enabled',enabled) FROM "
                       + schema + ".app_user WHERE username='" + username + "';")
    account = json.loads(raw)
    require(account['role'] == 'USER' and account['approval'] == 'PENDING' and account['enabled'] is False,
            'Public signup bypassed ordinary-user approval')
    victim.login(username, password, success=False)
    admin_users = admin.get('/admin/users')
    require('가입 승인' in admin_users and username in admin_users, 'The administrator approval screen omitted the pending user')
    response = admin.request('/admin/users/' + str(account['id']) + '/approve', {'_csrf': HELPERS.csrf(admin_users)})
    redirect(response, war.base, '/admin/users')
    victim.login(username, password)
    user_ui(victim)
    report['checks'].update({'publicSignupAndAdministratorApproval': True, 'userRoleUiAndAccess': True})
    response = victim.request('/projects', {'_csrf': HELPERS.csrf(victim.get('/projects')),
        'name': 'Synthetic session recovery', 'repositoryUrl': fixture_url + '/fixture/restart', 'reviewBranch': ''})
    location = urllib.parse.urlsplit(urllib.parse.urljoin(war.base + '/', response.location))
    match = re.fullmatch(re.escape(urllib.parse.urlsplit(war.base).path) + r'/projects/([0-9]+)', location.path)
    require(match is not None, 'The ordinary user project registration omitted its detail redirect')
    project = int(match[1])
    redirect(response, war.base, '/projects/' + str(project))
    page = victim.get('/projects/' + str(project))
    require('승인을 기다리고 있어요' in page, 'The ordinary user project omitted pending-approval guidance')
    admin_project = admin.get('/projects/' + str(project))
    require('프로젝트 승인' in admin_project, 'The administrator project screen omitted the approval action')
    response = admin.request('/admin/projects/' + str(project) + '/approve', {'_csrf': HELPERS.csrf(admin_project)})
    redirect(response, war.base, '/projects/' + str(project))
    response = victim.request('/projects/' + str(project) + '/review',
                              {'_csrf': HELPERS.csrf(victim.get('/projects/' + str(project)))})
    redirect(response, war.base, '/reviews', 'projectId=' + str(project))
    HELPERS.bounded_wait(lambda: database.snapshot(schema, project)['request']['state'] == 'SUCCEEDED',
                         min(deadline, time.monotonic() + 90), 'The ordinary user review did not finish', war.process)
    state, issues = database.snapshot(schema, project), issue_rows(database, schema)
    require(state['request']['actor'] == account['id'] and state['request']['source'] == 'MANUAL'
            and state['request']['attempts'] == 1 and state['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B]
            and state['cursor'] == HELPERS.SHA_B and state['runs'] == [{'state': 'SUCCEEDED', 'commits': 2}]
            and len(issues) == 2 and all(row['assignee_id'] == account['id'] and row['status'] == 'OPEN'
                                       and row['issue_kind'] == 'AI_FINDING' for row in issues),
            'The completed review did not preserve its ordinary user request and assigned issues')
    report['checks']['userProjectAndAssignedIssues'] = True
    return victim, username, password, account['id'], project, issues


def observe_idle(victims, control, war, database, schema, baseline, server, issue, report, deadline):
    counters = [browser.requests for browser in victims]
    for browser in victims:
        browser.idle = True
    started = time.monotonic()
    next_probe, controlled, observations = started, False, 0
    print('SESSION_IDLE_WAIT_STARTED', flush=True)
    try:
        while True:
            now = time.monotonic()
            require(now < deadline and war.process.poll() is None, 'The owned WAR stopped or the idle deadline expired')
            elapsed = now - started
            if not controlled and elapsed >= CONTROL_SECONDS:
                require(elapsed < 1800, 'The pre-expiry control observation missed its 30-minute boundary')
                page = control.get('/issues/' + str(issue['id']))
                require(issue['title'] in page and '처리 결과 기록' in page,
                        'The control session was not authenticated before thirty idle minutes')
                report['controlProbeAfterSeconds'] = round(time.monotonic() - started, 3)
                require(report['controlProbeAfterSeconds'] < 1800, 'The pre-expiry response arrived after thirty minutes')
                controlled = True
            if now >= next_probe or elapsed >= IDLE_SECONDS:
                NETWORK.probe(war.base, 'liveness', 200, 'UP')
                require(business_fingerprints(database, schema) == baseline and NETWORK.server_identity(database) == server,
                        'Business records or the database process changed during the idle interval')
                observations += 1
                next_probe = time.monotonic() + 60
            if time.monotonic() - started >= IDLE_SECONDS:
                break
            time.sleep(min(1, max(0.001, IDLE_SECONDS - (time.monotonic() - started))))
        require(controlled and all(browser.requests == count and browser.idle_attempts == 0
                                   for browser, count in zip(victims, counters)),
                'A victim browser was touched during its required no-request interval')
        report.update({'idleSecondsObserved': round(time.monotonic() - started, 3),
                       'victimRequestsDuringIdle': sum(browser.requests - count for browser, count in zip(victims, counters)),
                       'idleStateObservations': observations})
        report['checks'].update({'controlAuthenticatedBeforeExpiry': True, 'victimsUnrequestedDuringIdle': True})
    finally:
        for browser in victims:
            browser.idle = False


def assert_final_rows(before, after, issue_id):
    require(len(before) == len(after), 'Issue recovery changed the number of assigned issues')
    changed = 0
    for original, current in zip(before, after):
        require(original['id'] == current['id'], 'Issue recovery replaced an existing issue')
        if original['id'] == issue_id:
            allowed = dict(original, status='RESOLVED', updated_at=current.get('updated_at'))
            require(current == allowed and current['updated_at'] != original['updated_at'],
                    'The fresh form changed issue ownership, content or unexpected fields')
            changed += 1
        else:
            require(current == original, 'The fresh form changed another assigned issue')
    require(changed == 1, 'The intended recovered issue was not found exactly once')


def exercise(war, database, schema, fixture, fixture_url, args, report, deadline):
    war.start(True)
    original_process, server = war.process, NETWORK.server_identity(database)
    post, username, password, user, project, issues = prepare_business(war, database, schema, fixture_url, deadline, report)
    get, control = Browser(war.base, deadline), Browser(war.base, deadline)
    for browser in (get, control):
        browser.login(username, password)
    issue = issues[0]
    control.get('/issues/' + str(issue['id']))
    get.get('/issues/' + str(issue['id']))
    token = HELPERS.csrf(post.get('/issues/' + str(issue['id'])))
    old_cookie = post.cookie()
    require(len({old_cookie, get.cookie(), control.cookie()}) == 3, 'The three browser sessions are not independent')
    baseline, original_audits = business_fingerprints(database, schema), audit_rows(database, schema)
    observe_idle((post, get), control, war, database, schema, baseline, server, issue, report, deadline)
    fields = {'_csrf': token, 'status': 'RESOLVED', 'filterStatus': 'OPEN', 'page': '0',
              'reason': 'SYNTHETIC_UNTRUSTED_' + uuid.uuid4().hex}
    response = post.request('/issues/' + str(issue['id']) + '/status', fields)
    csrf_denial(response, war.base, (token, old_cookie, password, fields['reason']))
    report['checks']['expiredPostRejected'] = True
    redirect(get.request('/issues/' + str(issue['id'])), war.base, '/login')
    report['checks']['expiredGetRedirectedToLogin'] = True
    require(business_fingerprints(database, schema) == baseline, 'Expired sessions mutated existing business records')
    post.login(username, password)
    require(post.cookie() != old_cookie, 'Reauthentication retained the expired session identifier')
    report['checks']['newSessionAfterLogin'] = True
    response = post.request('/issues/' + str(issue['id']) + '/status', fields)
    csrf_denial(response, war.base, (token, old_cookie, password, fields['reason']))
    require(business_fingerprints(database, schema) == baseline, 'An old form mutated business records after reauthentication')
    report['checks'].update({'oldTokenRejectedAfterLogin': True, 'storedWorkPreservedAfterExpiry': True})
    user_ui(post)
    fresh = HELPERS.csrf(post.get('/issues/' + str(issue['id'])))
    response = post.request('/issues/' + str(issue['id']) + '/status', dict(fields, _csrf=fresh))
    redirect(response, war.base, '/issues', 'status=OPEN&page=0')
    page = post.get('/issues/' + str(issue['id']))
    require(issue['title'] in page and '해결' in page, 'The recovered issue did not return to its ordinary user workflow')
    assert_final_rows(issues, issue_rows(database, schema), issue['id'])
    final_audits = audit_rows(database, schema)
    require(len(final_audits) == len(original_audits) + 1 and final_audits[:-1] == original_audits,
            'The recovered form replaced existing audits or produced additional audit records')
    final = business_fingerprints(database, schema)
    require(all(final[table] == baseline[table] for table in BUSINESS_TABLES if table not in ('review_issue', 'audit_event')),
            'The recovered form altered unrelated users, project, commits or original request')
    require(database.sql(f"SELECT count(*) FROM {schema}.audit_event WHERE action='ISSUE_STATUS_CHANGED' "
                        f"AND target_type='REVIEW_ISSUE' AND target_id={issue['id']} AND actor_id={user};") == '1'
            and database.sql(f"SELECT count(*) FROM {schema}.audit_event WHERE action='ISSUE_STATUS_CHANGED';") == '1',
            'The recovered form did not produce exactly one ordinary-user status audit')
    counts = fixture.observed()
    require(all(counts.get('ai_' + name) == 1 for name in ('A.java', 'B.java')) and not fixture.errors,
            'Session recovery repeated AI work or lost fixture validation')
    require(war.process is original_process and war.starts == 1 and war.process.poll() is None
            and NETWORK.server_identity(database) == server,
            'The WAR or database restarted during the session expiry verification')
    report['checks'].update({'freshFormSavedExactlyOnce': True, 'sameLiveWarAndDatabase': True})
    report.update({'businessTableCount': len(BUSINESS_TABLES), 'assignedIssueCount': 2, 'successfulStatusAudits': 1})


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=2100)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = LINUX.checked_path(getattr(args, name)) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and 1024 <= args.port <= 65535
            and 1950 <= args.timeout_seconds <= 3600 and 15 <= args.startup_timeout_seconds <= 120,
            'Invalid bounded session expiry arguments')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Session evidence must stay in this checkout .local')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing session expiry report')
    LINUX.unused_port(args.port)
    return args


def run(args, report):
    started, schema, marker = time.monotonic(), 'restart_test_' + uuid.uuid4().hex, 'session-expiry-' + uuid.uuid4().hex
    deadline = started + args.timeout_seconds
    verify_packaged_timeout(args.war)
    report['checks']['defaultThirtyMinuteTimeout'] = True
    logs = args.report.parent / schema
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    work = Path(tempfile.mkdtemp(prefix='owned-work-', dir=logs))
    database, identity, war, originals, server = (None,) * 5
    fixture, owned, failure, cleanup_failed = HELPERS.Fixture(), False, None, False
    fixture.release_first_b.set()
    fixture.release_second_b.set()
    try:
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work)
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Unexpected observer database')
        originals, server = RATE.public_fingerprints(database), NETWORK.server_identity(database)
        database.sql('CREATE SCHEMA ' + schema + ';')
        owned = True
        captured = SHARED.schema_identity(database, schema)
        require(captured is not None and type(captured.get('oid')) is int and captured['owner'] == database.user,
                'The new session schema identity could not be established')
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': captured['oid'], 'owner': database.user, 'marker': marker}
        SHARED.verify_schema(database, schema, identity)
        require(int(HELPERS.DB_PATTERN.fullmatch(database.url).group(2)) != args.port,
                'The WAR and database ports must differ')
        fixture_url = fixture.start()
        war = HELPERS.OwnedWar(args, database, schema, fixture_url, work, logs, deadline)
        exercise(war, database, schema, fixture, fixture_url, args, report, deadline)
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
        fixture_stopped = False
        try:
            fixture.close()
            fixture_stopped = fixture.thread is None or not fixture.thread.is_alive()
            require(fixture_stopped, 'The owned synthetic fixture did not stop')
        except BaseException:
            cleanup_failed = True
        stopped = war is None or war.process is None or war.process.poll() is not None
        sessions_gone = False
        if database is not None and stopped:
            try:
                HELPERS.bounded_wait(lambda: NETWORK.owned_sessions(database, schema) == 0, time.monotonic() + 10,
                                     'Owned WAR database sessions remain')
                sessions_gone = True
            except BaseException:
                cleanup_failed = True
        if owned and stopped and sessions_gone:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned session schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and fixture_stopped and not owned:
            try:
                if originals is not None:
                    require(RATE.public_fingerprints(database) == originals, 'Original public test tables changed')
                    require(NETWORK.server_identity(database) == server, 'Observer database process changed')
                    report['checks'].update({'originalTestTablesPreserved': True, 'databaseProcessUnchanged': True})
                    report['originalTableCount'] = len(originals)
                require(work.parent == logs and work.name.startswith('owned-work-'), 'Unexpected owned work directory')
                LINUX.checked_path(work, directory=True)
                shutil.rmtree(work)
            except BaseException:
                cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedFixtureStopped': fixture_stopped, 'ownedDatabaseSessionsGone': sessions_gone,
                                'ownedSchemaRemoved': not owned, 'ownedWorkRemoved': not work.exists()})
        report['cleanupFailed'] = cleanup_failed or not stopped or not fixture_stopped or owned or not sessions_gone
        report['warStarts'] = war.starts if war is not None else 0
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned session resources could not be safely removed')


def evidence_gate(report):
    idle, control = report.get('idleSecondsObserved'), report.get('controlProbeAfterSeconds')
    require(report.get('cleanupFailed') is False and all(report.get('checks', {}).get(key) is True for key in CLEANUP + BEHAVIOR)
            and type(idle) in (int, float) and math.isfinite(idle) and IDLE_SECONDS <= idle <= 3600
            and type(control) in (int, float) and math.isfinite(control) and CONTROL_SECONDS <= control < 1800
            and type(report.get('victimRequestsDuringIdle')) is int and report['victimRequestsDuringIdle'] == 0
            and type(report.get('sessionTimeoutSeconds')) is int and report['sessionTimeoutSeconds'] == 1800
            and type(report.get('idleStateObservations')) is int and report['idleStateObservations'] >= 30
            and report.get('warStarts') == 1 and type(report.get('warStarts')) is int
            and type(report.get('successfulStatusAudits')) is int and report['successfulStatusAudits'] == 1
            and type(report.get('assignedIssueCount')) is int and report['assignedIssueCount'] == 2,
            'Successful evidence requires real default-timeout expiry, safe recovery and complete cleanup')


def main(argv=None):
    args, previous, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'sessionTimeoutSeconds': 1800, 'requiredIdleSeconds': IDLE_SECONDS,
              'scope': 'One live WAR; three independent ordinary-user cookie sessions; real default idle timeout',
              'limitations': ['Synthetic Git/AI and loopback HTTP only; no operational session-capacity SLA']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        previous = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        evidence_gate(report)
        report['result'], code = 'PASS', 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, (VerificationError, LINUX.SafetyError)) else 'Private error details withheld'
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
            print('FAIL: fresh session expiry report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': bounded real idle-session expiry and safe form recovery; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
