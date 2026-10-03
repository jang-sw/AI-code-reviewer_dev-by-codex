#!/usr/bin/env python3
"""Two real WARs share authentication quotas across requests, restart and a DB fault.

Only a generated schema in loopback reviewer_integration is written. Workers and
scheduling stay disabled; no repository, AI endpoint or cluster lifecycle is used.
Expiry and an unavailable bucket table are deliberately injected through owned SQL.
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
import urllib.error
import urllib.parse
import urllib.request
import uuid


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


HELPERS = module('shared_auth_restart_helpers', 'verify-review-restart.py')
LINUX = module('shared_auth_path_helpers', 'test-postgres-linux.py')
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
ADDRESS_KEY = 'ip:' + hashlib.sha256(b'127.0.0.1').hexdigest()
BUCKET = 'auth_attempt_bucket'
FAULT_BUCKET = 'auth_bucket_unavailable'
UNAVAILABLE = {'/login': '지금은 로그인을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.',
               '/signup': '지금은 가입 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.'}


def account_key(value):
    return 'account:' + hashlib.sha256(value.strip().lower().encode('utf-8')).hexdigest()


class ProbeBrowser(HELPERS.Browser):
    """Keep the existing cookie and redirect restrictions, including error responses."""
    def request_checked(self, path, deadline, fields=None):
        require(path in ('/login', '/signup'), 'Unexpected authentication fixture route')
        remaining = deadline - time.monotonic()
        require(remaining > 0, 'Shared authentication verification exceeded its deadline')
        data = None if fields is None else urllib.parse.urlencode(fields).encode('utf-8')
        request = urllib.request.Request(self.base + path, data=data)
        try:
            try:
                response = self.opener.open(request, timeout=min(10, remaining))
            except urllib.error.HTTPError as error:
                response = error
            with response:
                body = response.read(2_000_001)
                require(len(body) <= 2_000_000, 'Authentication response exceeded the fixture limit')
                return response.status, response.geturl(), response.headers, body.decode('utf-8')
        except (urllib.error.URLError, TimeoutError, UnicodeError):
            raise VerificationError('Owned authentication HTTP request failed') from None


def post_form(war, path, fields, deadline):
    browser = ProbeBrowser(war.base)
    status, _, _, page = browser.request_checked(path, deadline)
    require(status == 200, 'Owned authentication form was not available')
    token = HELPERS.csrf(page)
    jars = [handler.cookiejar for handler in browser.opener.handlers
            if isinstance(handler, urllib.request.HTTPCookieProcessor)]
    require(token and any(cookie.name == 'JSESSIONID' for jar in jars for cookie in jar),
            'Authentication form did not establish its CSRF session cookie')
    return browser.request_checked(path, deadline, {'_csrf': token, **fields})


def login(war, username, password, deadline, expected=200, success=False):
    response = post_form(war, '/login', {'username': username, 'password': password}, deadline)
    status, location, headers, body = response
    require(status == expected, 'Unexpected shared login response status')
    if expected == 200:
        target = urllib.parse.urlsplit(location)
        if success:
            require(target.path != urllib.parse.urlsplit(war.base).path + '/login', 'Synthetic administrator login failed')
        else:
            require(target.path == urllib.parse.urlsplit(war.base).path + '/login' and target.query == 'error',
                    'An allowed failed login did not reach its normal failure redirect')
    else:
        denial('/login', headers, body, expected, username, password)


def signup(war, username, password, deadline, expected=200):
    status, location, headers, body = post_form(war, '/signup', {'username': username, 'password': password,
        'confirmPassword': password, 'gitUsername': username}, deadline)
    require(status == expected, 'Unexpected shared signup response status')
    if expected == 200:
        target = urllib.parse.urlsplit(location)
        require(target.path == urllib.parse.urlsplit(war.base).path + '/signup' and target.query == 'submitted',
                'An allowed signup did not reach its submitted redirect')
    else:
        denial('/signup', headers, body, expected, username, password)


def denial(path, headers, body, status, username, password):
    require('no-store' in headers.get('Cache-Control', '').lower()
            and re.fullmatch(r'[1-9][0-9]*', headers.get('Retry-After', '')) is not None,
            'Authentication denial omitted bounded retry or no-store headers')
    require(username not in body and password not in body
            and not any(value in body.lower() for value in ('auth_attempt_', 'select ', 'sqlstate', 'jdbc:')),
            'Authentication failure exposed fixture input or database internals')
    if status == 503:
        require(headers.get('Retry-After') == '30' and body == UNAVAILABLE[path],
                'Unavailable storage did not return the fixed fail-closed response')


def schema_identity(database, schema):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Unexpected owned authentication schema')
    raw = database.sql("SELECT json_build_object('oid',n.oid::bigint,'owner',r.rolname,"
        "'marker',obj_description(n.oid,'pg_namespace')) FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner "
        "WHERE n.nspname='" + schema + "';")
    return json.loads(raw) if raw else None


def verify_schema(database, schema, identity):
    require(identity is not None and schema_identity(database, schema) == identity,
            'Owned authentication schema identity changed; mutation was withheld')


def table_oid(database, schema, table):
    require(table in (BUCKET, FAULT_BUCKET), 'Unexpected authentication fault table')
    return database.sql("SELECT c.oid::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
        "WHERE n.nspname='" + schema + "' AND c.relname='" + table + "' AND c.relkind='r';")


def restore_bucket(database, schema, identity, expected_oid):
    if expected_oid is None:
        return
    verify_schema(database, schema, identity)
    normal, hidden = table_oid(database, schema, BUCKET), table_oid(database, schema, FAULT_BUCKET)
    if normal == expected_oid and not hidden:
        return
    require(not normal and hidden == expected_oid, 'Authentication fault table identity changed; restore was withheld')
    database.sql(f'ALTER TABLE {schema}.{FAULT_BUCKET} RENAME TO {BUCKET};')
    require(table_oid(database, schema, BUCKET) == expected_oid and not table_oid(database, schema, FAULT_BUCKET),
            'Owned authentication fault table restoration was not confirmed')


def bucket_count(database, schema, scope, key):
    require(scope in ('LOGIN', 'SIGNUP') and re.fullmatch(r'(account:|ip:)[a-f0-9]{64}', key),
            'Unexpected authentication bucket probe')
    value = database.sql(f"SELECT attempt_count FROM {schema}.{BUCKET} WHERE scope='{scope}' AND key_hash='{key}';")
    require(not value or re.fullmatch(r'[1-9][0-9]*', value), 'Invalid authentication bucket counter')
    return int(value) if value else 0


def quota_fingerprint(database, schema, scope):
    require(scope in ('LOGIN', 'SIGNUP'), 'Unexpected authentication scope')
    return database.sql(f"SELECT md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY key_hash),'')) "
                        f"FROM (SELECT * FROM {schema}.{BUCKET} WHERE scope='{scope}') t;")


def expire(database, schema, identity, scope):
    verify_schema(database, schema, identity)
    require(scope in ('LOGIN', 'SIGNUP'), 'Unexpected authentication expiration scope')
    database.sql(f"UPDATE {schema}.{BUCKET} SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE scope='{scope}';")


def users(database, schema):
    return int(database.sql(f'SELECT count(*) FROM {schema}.app_user;'))


def exercise(wars, database, schema, identity, report, deadline):
    a, b = wars
    suffix = uuid.uuid4().hex
    unknown = 'sharedunknown' + suffix
    wrong = 'Synthetic-invalid!' + suffix
    checks = report['checks']
    report['stage'] = 'cross_node_account_limit'
    require(database.sql(f'SELECT count(*) FROM {schema}.{BUCKET};') == '0', 'Fresh schema already has authentication buckets')
    for index in range(10):
        login(wars[index % 2], unknown, wrong, deadline)
    require(bucket_count(database, schema, 'LOGIN', account_key(unknown)) == 10
            and bucket_count(database, schema, 'LOGIN', ADDRESS_KEY) == 10, 'Account limit was not shared across both WARs')
    frozen = quota_fingerprint(database, schema, 'LOGIN')
    for war in wars:
        login(war, unknown, wrong, deadline, 429)
    require(quota_fingerprint(database, schema, 'LOGIN') == frozen, 'Denied requests changed counts or extended expiry')
    checks['tenAlternatingAccountAttemptsThenBoth429WithoutMutation'] = True

    report['stage'] = 'restart_second_war'
    first_pid = a.process.pid
    b.stop(force=True)
    b.start(False)
    login(b, unknown, wrong, deadline, 429)
    require(a.process.poll() is None and a.process.pid == first_pid
            and quota_fingerprint(database, schema, 'LOGIN') == frozen, 'Restart lost the shared quota or restarted the other WAR')
    checks['secondWarRestartPreservesQuotaAndFirstWar'] = True

    report['stage'] = 'injected_expiration_and_successful_login'
    expire(database, schema, identity, 'LOGIN')
    for war in wars:
        login(war, unknown, wrong, deadline)
    login(a, a.username, wrong, deadline)
    require(bucket_count(database, schema, 'LOGIN', account_key(a.username)) == 1, 'Failed administrator login was not counted')
    before_ip = bucket_count(database, schema, 'LOGIN', ADDRESS_KEY)
    login(b, a.username, a.password, deadline, success=True)
    require(bucket_count(database, schema, 'LOGIN', account_key(a.username)) == 0
            and bucket_count(database, schema, 'LOGIN', ADDRESS_KEY) == before_ip + 1
            and bucket_count(database, schema, 'LOGIN', account_key(unknown)) == 2,
            'Successful login did not clear only its account while preserving the address quota')
    checks['sqlExpiredWindowReopensBothNodesAndSuccessOnlyClearsAccount'] = True

    report['stage'] = 'cross_node_signup_limit'
    initial_users = users(database, schema)
    frozen_login = quota_fingerprint(database, schema, 'LOGIN')
    for index in range(10):
        signup(wars[index % 2], 'sharedsignup' + suffix + str(index), wrong, deadline)
    for war in wars:
        signup(war, 'sharedblocked' + suffix, wrong, deadline, 429)
    require(users(database, schema) == initial_users + 10
            and database.sql(f"SELECT count(*) FROM {schema}.app_user WHERE approval_status='PENDING' AND enabled=FALSE;") == '10'
            and bucket_count(database, schema, 'SIGNUP', ADDRESS_KEY) == 10
            and bucket_count(database, schema, 'SIGNUP', account_key('127.0.0.1')) == 10
            and quota_fingerprint(database, schema, 'LOGIN') == frozen_login,
            'Signup quota, pending accounts or scope isolation differed from the shared contract')
    login(a, 'sharedindependent' + suffix, wrong, deadline)
    checks['tenAlternatingSignupsThenBoth429WithSeparateLoginScope'] = True

    report['stage'] = 'cross_node_address_limit'
    accepted = bucket_count(database, schema, 'LOGIN', ADDRESS_KEY)
    require(0 < accepted < 100, 'Unexpected pre-address-limit login count')
    for index in range(accepted, 100):
        login(wars[index % 2], 'sharedaddress' + suffix + str(index), wrong, deadline)
    require(bucket_count(database, schema, 'LOGIN', ADDRESS_KEY) == 100, 'Login address quota did not stop at exactly one hundred')
    frozen = quota_fingerprint(database, schema, 'LOGIN')
    for war in wars:
        login(war, 'sharedaddressoverflow' + suffix, wrong, deadline, 429)
    require(quota_fingerprint(database, schema, 'LOGIN') == frozen, 'Address-limited requests changed or created buckets')
    checks['hundredAddressAttemptsAcrossNodesThenBoth429'] = True

    report['stage'] = 'storage_unavailable_fail_closed'
    before_users = users(database, schema)
    verify_schema(database, schema, identity)
    oid = table_oid(database, schema, BUCKET)
    require(re.fullmatch(r'[1-9][0-9]*', oid) and not table_oid(database, schema, FAULT_BUCKET),
            'Authentication fault table is missing or a fault target already exists')
    report['faultTableOid'] = int(oid)
    try:
        database.sql(f'ALTER TABLE {schema}.{BUCKET} RENAME TO {FAULT_BUCKET};')
        for war in wars:
            login(war, 'sharedoutage' + suffix, wrong, deadline, 503)
            signup(war, 'sharedoutagesignup' + suffix, wrong, deadline, 503)
        require(users(database, schema) == before_users, 'Unavailable limiter allowed an account to be created')
    finally:
        restore_bucket(database, schema, identity, oid)
    checks['bothEndpointsBothNodes503WithoutInputOrSqlDisclosure'] = True
    for war in wars:
        login(war, 'sharedrecovered' + suffix, wrong, deadline, 429)
        signup(war, 'sharedrecoveredsignup' + suffix, wrong, deadline, 429)
    checks['restoredStorageRetainsBothExistingQuotas'] = True
    expire(database, schema, identity, 'LOGIN')
    expire(database, schema, identity, 'SIGNUP')
    login(a, 'sharedfinal' + suffix, wrong, deadline)
    signup(b, 'sharedfinalsignup' + suffix, wrong, deadline)
    require(users(database, schema) == before_users + 1, 'Recovered signup did not create exactly one pending account')
    checks['normalRequestsResumeAfterSqlInjectedExpiration'] = True
    report['successfulSignupPosts'] = 11
    report['addressWindowAcceptedLoginPosts'] = 100


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port-a', type=int, default=18094)
    parser.add_argument('--port-b', type=int, default=18095)
    parser.add_argument('--timeout-seconds', type=int, default=300)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = getattr(args, name).resolve(strict=True) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicit WAR or executable is not a regular file')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and args.port_a != args.port_b
            and all(1024 <= value <= 65535 for value in (args.port_a, args.port_b)), 'Distinct valid loopback WAR ports are required')
    require(120 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120, 'Invalid bounded shared authentication timeout')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Shared authentication evidence must remain in this checkout .local directory')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing shared authentication report')
    for port in (args.port_a, args.port_b):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                raise VerificationError('A requested shared authentication WAR port is occupied') from None
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'shared-auth-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    LINUX.checked_path(logs, directory=True, allow_missing=True)
    logs.mkdir(mode=0o700)
    report['logDirectory'] = str(logs)
    wars, works = [], []
    database, identity, created_oid = None, None, None
    owned_schema = False
    failure = None
    cleanup_failed = False
    try:
        for node in ('a', 'b'):
            directory = logs / ('node-' + node)
            directory.mkdir(mode=0o700)
            works.append(Path(tempfile.mkdtemp(prefix='owned-work-', dir=directory)))
        database = HELPERS.Database(args.psql, os.environ.get('TEST_DATABASE_URL', ''), works[0])
        require(database.sql('SELECT current_database();') == 'reviewer_integration', 'Refusing an unexpected database')
        database.sql('CREATE SCHEMA ' + schema + ';')
        owned_schema = True
        # Failed/ambiguous identity establishment is never replaced with a guessed DROP target.
        captured = schema_identity(database, schema)
        require(captured is not None and type(captured.get('oid')) is int and captured['owner'] == database.user,
                'Created authentication schema ownership could not be established')
        created_oid = captured['oid']
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': created_oid, 'owner': database.user, 'marker': marker}
        verify_schema(database, schema, identity)
        for index, port in enumerate((args.port_a, args.port_b)):
            options = types.SimpleNamespace(**vars(args), port=port)
            wars.append(HELPERS.OwnedWar(options, database, schema, 'http://127.0.0.1:9',
                                        works[index], works[index].parent, deadline))
        wars[1].password = wars[0].password
        for war in wars:
            war.start(False)
        exercise(wars, database, schema, identity, report, deadline)
    except BaseException as error:
        failure = error
    finally:
        # Stop both independently. Never remove DB state while an owned process is alive.
        for war in wars:
            try:
                war.stop(force=True)
            except BaseException:
                cleanup_failed = True
        stopped = not any(war.process is not None and war.process.poll() is None for war in wars)
        if owned_schema and stopped:
            try:
                verify_schema(database, schema, identity)
                if 'faultTableOid' in report:
                    restore_bucket(database, schema, identity, str(report['faultTableOid']))
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(schema_identity(database, schema) is None, 'Owned authentication schema removal was not confirmed')
                owned_schema = False
            except BaseException:
                cleanup_failed = True
        if stopped and not owned_schema:
            for work in works:
                try:
                    require(work.parent.parent == logs and work.parent.name in ('node-a', 'node-b')
                            and work.name.startswith('owned-work-'), 'Unexpected owned authentication work directory')
                    LINUX.checked_path(work, directory=True)
                    shutil.rmtree(work)
                except BaseException:
                    cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedSchemaRemoved': not owned_schema})
        report['cleanupFailed'] = cleanup_failed or not stopped or owned_schema
        report['warStarts'] = [war.starts for war in wars]
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
        report.pop('faultTableOid', None)
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned shared authentication cleanup was not confirmed')


def main(argv=None):
    args, prior, installed = None, None, False
    report = {'result': 'FAIL', 'stage': 'setup', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
              'scope': 'Two real WARs and one owned PostgreSQL schema; workers and scheduler disabled',
              'sqlFaultInjection': ['DB-clock-based expiry of owned authentication buckets', 'Temporary owned bucket table rename'],
              'limitations': ['Sequential cross-node HTTP requests; separate PostgreSQL tests cover simultaneous acquisition',
                              'Synthetic HTTP only; production ingress, TLS and distributed availability are separate']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        prior = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        report.update({'result': 'PASS', 'stage': 'complete'})
        code = 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        report['failure'] = str(error) if isinstance(error, (VerificationError, LINUX.SafetyError)) else 'Details withheld; fixture input and responses are not reported'
    finally:
        if installed:
            signal.signal(signal.SIGTERM, prior)
    if args is not None:
        try:
            LINUX.checked_path(args.report, allow_missing=True)
            descriptor = os.open(args.report, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                json.dump(report, stream, indent=2)
                stream.write('\n')
        except (OSError, LINUX.SafetyError):
            print('FAIL: fresh shared authentication report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': shared authentication across two owned WARs; fixture inputs withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
