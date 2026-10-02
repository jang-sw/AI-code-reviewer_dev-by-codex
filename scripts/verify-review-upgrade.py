#!/usr/bin/env python3
"""Linux-only V12 -> V13 review recovery and fresh-database V12 backup rollback.

Run through the native Linux PostgreSQL parent. Both WAR hashes are explicit.
All writes are confined to two positively owned UUID databases. The existing
reviewer_integration database is used only for parent cluster identification.
Real loopback Git/AI processing creates commit A and an interrupted B request.
Additional historical manual/AI resolution examples are explicitly SQL-seeded.
"""

import argparse
import hashlib
import html
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
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


HELPERS = module('upgrade_restart_helpers', 'verify-review-restart.py')
BACKUP = module('upgrade_backup_helpers', 'verify-postgres-backup.py')
LINUX = BACKUP.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
TABLES = BACKUP.TABLES
OLD_RUN_COLUMNS = 'id,project_id,status,started_at,finished_at,reviewed_commits,error_message'
MANUAL_NOTE = 'Synthetic human review <script>escaped evidence retained</script>'
MANUAL_TITLE = 'Synthetic historical manual review'
AI_TITLE = 'Synthetic historical resolved AI finding'
SEED_SHA_MANUAL, SEED_SHA_AI = 'c' * 40, 'd' * 40


def identifiers(schema, project=None):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, 'Invalid invocation-owned schema')
    if project is not None:
        require(type(project) is int and project > 0, 'Invalid synthetic project identifier')


def verify_war(path, expected_sha256, maximum_version):
    path = LINUX.absolute_path(path)
    LINUX.checked_path(path)
    require(path.suffix.lower() == '.war' and maximum_version in (12, 13)
            and re.fullmatch(r'[a-f0-9]{64}', expected_sha256) is not None,
            'Expected an explicit WAR hash and supported migration boundary')
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    require(digest == expected_sha256, 'WAR bytes do not match the explicitly expected hash')
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'WAR contains duplicate archive entries')
            prefix = 'WEB-INF/classes/db/migration/'
            files = [name[len(prefix):] for name in names if name.startswith(prefix) and not name.endswith('/')]
            parsed = [re.fullmatch(r'V([1-9][0-9]*)__[^/]+\.sql', name) for name in files]
            require(files and all(parsed), 'WAR contains an unexpected migration resource')
            versions = sorted(int(match[1]) for match in parsed)
            require(versions == list(range(1, maximum_version + 1)), 'WAR migration boundary does not match V12 or V13')
            require('org/springframework/boot/loader/launch/WarLauncher.class' in names
                    and 'WEB-INF/jsp/login.jsp' in names, 'WAR lacks its executable launcher or packaged login JSP')
    except (OSError, zipfile.BadZipFile, RuntimeError):
        raise VerificationError('Expected a readable executable WAR archive') from None
    return digest


class UpgradeDatabase(HELPERS.Database):
    """The original constructor validates the parent connection before target substitution."""
    def __init__(self, psql, base_url, work, owned_backup):
        super().__init__(psql, base_url, work)
        owned_backup.verify_target()
        require(BACKUP.RESTORE_PATTERN.fullmatch(owned_backup.target) is not None
                and self.user == 'reviewer_test' and self.password == '',
                'Only a positively owned synthetic database and test account are accepted')
        require(self.url == 'jdbc:postgresql://127.0.0.1:' + str(owned_backup.port) + '/reviewer_integration',
                'Owned target and parent connection ports differ')
        self.owner = owned_backup
        self.deadline = None
        self.url = self.url.rsplit('/', 1)[0] + '/' + owned_backup.target
        index = self.command.index('-d') + 1
        require(self.command[index] == 'reviewer_integration', 'Unexpected original psql database argument')
        self.command[index] = owned_backup.target

    def sql(self, text):
        if self.deadline is not None:
            require(time.monotonic() < self.deadline, 'The populated upgrade drill exceeded its overall deadline')
        self.owner.verify_target()
        return super().sql(text)


def parsed_sql(database, text):
    try:
        return json.loads(database.sql(text))
    except (ValueError, TypeError):
        raise VerificationError('The owned database returned invalid synthetic JSON') from None


def migration_snapshot(database, schema, maximum):
    identifiers(schema)
    require(maximum in (12, 13), 'Unexpected migration verification version')
    rows = parsed_sql(database, f"SELECT json_agg(json_build_object('version',version,'checksum',checksum,"
        f"'success',success) ORDER BY installed_rank) FROM {schema}.flyway_schema_history;")
    require(isinstance(rows, list) and len(rows) == maximum
            and [row.get('version') for row in rows] == [str(value) for value in range(1, maximum + 1)]
            and all(row.get('success') is True for row in rows), 'Unexpected Flyway migration history')
    return rows


def legacy_fingerprints(database, schema):
    identifiers(schema)
    result = {}
    for table in TABLES:
        projection = OLD_RUN_COLUMNS if table == 'review_run' else '*'
        value = database.sql("SELECT count(*)::text || ':' || md5(coalesce(string_agg(row_to_json(t)::text, "
            "E'\\n' ORDER BY row_to_json(t)::text),'')) FROM (SELECT " + projection + ' FROM ' + schema + '.' + table + ') t;')
        require(re.fullmatch(r'[0-9]+:[a-f0-9]{32}', value) is not None, 'Invalid legacy table fingerprint')
        result[table] = value
    return result


def application_fingerprints(values):
    return {name: value for name, value in values.items() if name != 'flyway_schema_history'}


def snapshot_v12(database, schema, project):
    identifiers(schema, project)
    return parsed_sql(database, f"""
SELECT json_build_object(
 'request',(SELECT json_build_object('requestId',request_id,'actor',requested_by,'source',source,
  'state',state,'attempts',attempt_count,'token',claim_token,'runId',run_id) FROM {schema}.review_request WHERE project_id={project}),
 'cursor',(SELECT last_reviewed_sha FROM {schema}.project WHERE id={project}),
 'commits',COALESCE((SELECT json_agg(commit_sha ORDER BY id) FROM {schema}.reviewed_commit WHERE project_id={project}),'[]'::json),
 'issues',(SELECT count(*) FROM {schema}.review_issue WHERE project_id={project}),
 'assignees',COALESCE((SELECT json_agg(assignee_id ORDER BY id) FROM {schema}.review_issue WHERE project_id={project}),'[]'::json),
 'runs',COALESCE((SELECT json_agg(json_build_object('state',status,'commits',reviewed_commits) ORDER BY id)
  FROM {schema}.review_run WHERE project_id={project}),'[]'::json));
""")


def seed_history(database, schema, project, actor):
    identifiers(schema, project)
    require(type(actor) is int and actor > 0, 'Invalid synthetic actor')
    # These constant values deliberately distinguish SQL migration fixtures from
    # the actual Git/AI-created commit A and its interrupted request.
    data = parsed_sql(database, f"""
WITH dismissed AS (UPDATE {schema}.review_issue SET status='DISMISSED' WHERE project_id={project} RETURNING id),
 p AS (INSERT INTO {schema}.project(name,repository_url,provider,repository_host,repository_path,owner_id,status,last_reviewed_sha)
 VALUES('SQL-seeded historical review','http://127.0.0.1:9/fixture/history','GITLAB','127.0.0.1','fixture/history',{actor},'PAUSED','{SEED_SHA_AI}') RETURNING id),
 r AS (INSERT INTO {schema}.review_run(project_id,status,started_at,finished_at,reviewed_commits)
 SELECT id,'SUCCEEDED',TIMESTAMP '2020-01-01 00:00:00',TIMESTAMP '2020-01-01 00:01:00',2 FROM p RETURNING id),
 cm AS (INSERT INTO {schema}.reviewed_commit(project_id,commit_sha,author_login,summary,coverage_type,coverage_details)
 SELECT id,'{SEED_SHA_MANUAL}','restartadmin','SQL-seeded manual history','MANUAL_ONLY','Synthetic pinned tree evidence' FROM p RETURNING id,project_id),
 ca AS (INSERT INTO {schema}.reviewed_commit(project_id,commit_sha,author_login,summary,coverage_type)
 SELECT id,'{SEED_SHA_AI}','restartadmin','SQL-seeded AI history','FULL' FROM p RETURNING id,project_id),
 m AS (INSERT INTO {schema}.manual_review_file(reviewed_commit_id,project_id,file_path,new_object_sha,new_mode,reason_code)
 SELECT id,project_id,'evidence.bin','{'e' * 40}','100644','SOURCE_DIFF_UNAVAILABLE' FROM cm RETURNING id,reviewed_commit_id,project_id),
 mi AS (INSERT INTO {schema}.review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,
 issue_kind,manual_file_id,status,resolution_note,assignment_reason)
 SELECT project_id,reviewed_commit_id,{actor},NULL,'{MANUAL_TITLE}','evidence.bin','SQL-seeded evidence','Synthetic human confirmation',
 'MANUAL_REVIEW',id,'RESOLVED','{MANUAL_NOTE}','PROJECT_OWNER_FALLBACK' FROM m RETURNING id),
 ai AS (INSERT INTO {schema}.review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,line_number,description,suggestion,
 issue_kind,status,assignment_reason)
 SELECT project_id,id,{actor},'HIGH','{AI_TITLE}','Archived.java',1,'SQL-seeded historical finding','Synthetic resolution',
 'AI_FINDING','RESOLVED','PROJECT_OWNER_FALLBACK' FROM ca RETURNING id),
 au AS (INSERT INTO {schema}.audit_event(actor_id,action,target_type,target_id,detail)
 SELECT {actor},'ISSUE_STATUS_CHANGED','REVIEW_ISSUE',id,'SQL-seeded RESOLVED: {MANUAL_NOTE}' FROM mi RETURNING id),
 ad AS (INSERT INTO {schema}.audit_event(actor_id,action,target_type,target_id,detail)
 SELECT {actor},'ISSUE_STATUS_CHANGED','REVIEW_ISSUE',id,'SQL-seeded DISMISSED state' FROM dismissed RETURNING id)
SELECT json_build_object('project',(SELECT id FROM p),'manualIssue',(SELECT id FROM mi),
 'aiIssue',(SELECT id FROM ai),'run',(SELECT id FROM r),'dismissedIssue',(SELECT id FROM dismissed),
 'manualFile',(SELECT id FROM m),'audit',(SELECT id FROM au));
""")
    require(isinstance(data, dict) and all(type(value) is int and value > 0 for value in data.values()),
            'Historical synthetic rows were not created with unique identities')
    return data


def preserved_history(database, schema, seeded):
    identifiers(schema, seeded['project'])
    return parsed_sql(database, f"""
SELECT json_build_object('project',(SELECT row_to_json(p) FROM {schema}.project p WHERE id={seeded['project']}),
 'commits',(SELECT json_agg(c ORDER BY id) FROM {schema}.reviewed_commit c WHERE project_id={seeded['project']}),
 'issues',(SELECT json_agg(i ORDER BY id) FROM {schema}.review_issue i WHERE project_id={seeded['project']}),
 'evidence',(SELECT row_to_json(m) FROM {schema}.manual_review_file m WHERE id={seeded['manualFile']}),
 'audit',(SELECT row_to_json(a) FROM {schema}.audit_event a WHERE id={seeded['audit']}),
 'run',(SELECT row_to_json(r) FROM (SELECT {OLD_RUN_COLUMNS} FROM {schema}.review_run WHERE id={seeded['run']}) r),
 'dismissed',(SELECT row_to_json(i) FROM {schema}.review_issue i WHERE id={seeded['dismissedIssue']}));
""")


def check_pages(war, seeded):
    browser = HELPERS.Browser(war.base)
    browser.login(war.username, war.password)
    _, manual = browser.request('/issues/' + str(seeded['manualIssue']))
    require(MANUAL_TITLE in manual and html.escape(MANUAL_NOTE) in manual
            and MANUAL_NOTE not in manual and 'e' * 40 in manual,
            'Stored manual evidence or escaped human resolution was not rendered')
    _, ai = browser.request('/issues/' + str(seeded['aiIssue']))
    require(AI_TITLE in ai, 'Stored historical AI finding was not rendered')
    _, dismissed = browser.request('/issues/' + str(seeded['dismissedIssue']))
    require('Synthetic finding A.java' in dismissed, 'Stored actual AI finding was not rendered')


def assert_recovered(current, frozen):
    before, after = frozen['request'], current['request']
    require(all(after[key] == before[key] for key in ('requestId', 'actor', 'source'))
            and after['state'] == 'SUCCEEDED' and after['attempts'] == before['attempts'] + 1
            and after['runId'] != before['runId'] and after['token'] != before['token'],
            'Recovered request identity actor attempts or claim transition is incorrect')
    require(current['commits'] == [HELPERS.SHA_A, HELPERS.SHA_B] and current['issues'] == 2
            and current['assignees'] == [before['actor']] * 2 and current['cursor'] == HELPERS.SHA_B
            and current['runs'] == [{'state': 'FAILED', 'commits': 1}, {'state': 'SUCCEEDED', 'commits': 1}],
            'Recovered request duplicated or lost saved commits issues or checkpoint')


def cleanup_owned(war, fixture, owners):
    complete = True
    if war is not None:
        try:
            war.stop(force=True)
        except BaseException:
            complete = False
    if fixture is not None:
        try:
            fixture.close()
        except BaseException:
            complete = False
    alive = war is not None and war.process is not None and war.process.poll() is None
    if alive:
        return False
    for owner in reversed(owners):
        try:
            if owner.created_here:
                owner.verify_target()
                require(owner.sql('postgres', "SELECT count(*) FROM pg_stat_activity WHERE datname='" + owner.target + "';") == '0',
                        'An owned database still has connections; clients will not be terminated')
            owner.cleanup()
        except BaseException:
            complete = False
    return complete


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'previous-war', 'java', 'pg-bin', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--psql', type=Path)
    parser.add_argument('--expected-war-sha256', required=True)
    parser.add_argument('--expected-previous-war-sha256', required=True)
    parser.add_argument('--expected-postmaster-pid', type=int, required=True)
    parser.add_argument('--expected-postmaster-started-at', type=int, required=True)
    parser.add_argument('--parent-run-token', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=300)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    args = parser.parse_args(argv)
    for name in ('expected_war_sha256', 'expected_previous_war_sha256'):
        value = getattr(args, name)
        require(re.fullmatch(r'[a-fA-F0-9]{64}', value) is not None, 'Expected an explicit 64-character WAR SHA256')
        setattr(args, name, value.lower())
    for name in ('war', 'previous_war', 'java', 'pg_bin', 'report'):
        setattr(args, name, LINUX.absolute_path(getattr(args, name)))
    args.psql = LINUX.absolute_path(args.psql) if args.psql is not None else args.pg_bin / 'psql'
    require(1024 <= args.port <= 65535 and 120 <= args.timeout_seconds <= 900
            and 15 <= args.startup_timeout_seconds <= 120, 'Invalid bounded upgrade port or timeout')
    for path in (args.java, args.psql):
        LINUX.checked_path(path)
        require(os.access(path, os.X_OK), 'Explicit Java or psql executable is not executable')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing to overwrite an existing upgrade report')
    require(BACKUP.WORKSPACE / '.local' in args.report.parents, 'Upgrade evidence must stay in this native checkout .local directory')
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(('127.0.0.1', args.port))
        except OSError:
            raise VerificationError('The requested loopback WAR port is occupied') from None
    return args


def run_scenario(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    logs = BACKUP.WORKSPACE / '.local' / ('review-upgrade-' + uuid.uuid4().hex)
    logs.mkdir(mode=0o700)
    report['logDirectory'] = str(logs)
    schema = 'restart_test_' + uuid.uuid4().hex
    owners, war, fixture = [], None, None
    failure = None
    work = None
    source_before = None
    try:
        report['stage'] = 'verify_war_boundaries'
        report['previousWarSha256'] = verify_war(args.previous_war, args.expected_previous_war_sha256, 12)
        report['warSha256'] = verify_war(args.war, args.expected_war_sha256, 13)
        work = Path(tempfile.mkdtemp(prefix='owned-work-', dir=logs))
        for label in ('upgrade', 'rollback'):
            options = types.SimpleNamespace(**vars(args))
            options.report = logs / (label + '-owner.json')
            owner = BACKUP.BackupDrill(options)
            owners.append(owner)
            owner.prepare()
        original = owners[0]
        source_before = original.fingerprints(BACKUP.SOURCE)
        original.create_target()
        database = UpgradeDatabase(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work, original)
        database.deadline = deadline
        database.sql('CREATE SCHEMA ' + schema + ';')
        fixture = HELPERS.Fixture()
        fixture_url = fixture.start()
        war_args = types.SimpleNamespace(**vars(args))
        war_args.war = args.previous_war
        war = HELPERS.OwnedWar(war_args, database, schema, fixture_url, work, logs, deadline)
        report['stage'] = 'old_war_actual_ai_and_interruption'
        war.start(False)
        old_migrations = migration_snapshot(database, schema, 12)
        browser = HELPERS.Browser(war.base)
        browser.login(war.username, war.password)
        _, page = browser.request('/projects')
        location, page = browser.request('/projects', {'_csrf': HELPERS.csrf(page), 'name': 'Synthetic upgrade recovery',
            'repositoryUrl': fixture_url + '/fixture/restart', 'reviewBranch': ''})
        match = re.fullmatch('/' + schema + r'/projects/([1-9][0-9]*)', urllib.parse.urlsplit(location).path)
        require(match is not None, 'Synthetic old-WAR project registration failed')
        project = int(match[1])
        _, page = browser.request('/admin/projects/' + str(project) + '/approve', {'_csrf': HELPERS.csrf(page)})
        browser.request('/projects/' + str(project) + '/review', {'_csrf': HELPERS.csrf(page)})
        initial = snapshot_v12(database, schema, project)
        require(initial['request']['state'] == 'QUEUED' and initial['request']['attempts'] == 0
                and not fixture.observed(), 'Old WAR worker pause did not retain an untouched queued request')
        war.stop()
        war.start(True)
        HELPERS.bounded_wait(fixture.first_b_entered.is_set, deadline, 'Old WAR did not reach its interrupted B call', war.process)
        live = snapshot_v12(database, schema, project)
        require(live['commits'] == [HELPERS.SHA_A] and live['issues'] == 1 and live['cursor'] is None
                and live['request']['state'] == 'RUNNING' and live['request']['attempts'] == 1
                and live['runs'] == [{'state': 'RUNNING', 'commits': 1}], 'Old WAR did not persist the intended interrupted state')
        war.stop(force=True)
        fixture.release_first_b.set()
        seeded = seed_history(database, schema, project, live['request']['actor'])
        history = preserved_history(database, schema, seeded)
        frozen = snapshot_v12(database, schema, project)
        before = legacy_fingerprints(database, schema)
        require(before['review_run'].startswith('2:') and before['review_issue'].startswith('3:')
                and before['manual_review_file'].startswith('1:'), 'Populated historical fixtures were not present before backup')
        report['checks']['oldWarActualCommitSavedBeforeForcedKill'] = True
        report['checks']['historicalSqlFixturesPresent'] = True
        report['historicalFixtureOrigin'] = 'SQL-seeded manual evidence/resolution and historical resolved AI; actual commit A finding SQL-dismissed'
        report['stage'] = 'back_up_populated_v12'
        original.verify_target()
        dump = logs / 'v12-populated.dump'
        original.command('pg_dump', [*original.connection, '--format=custom', '--no-owner', '--no-acl',
            '--schema', schema, '--lock-wait-timeout=30s', '--file', dump, original.target], readonly=True)
        LINUX.checked_path(dump)
        dump.chmod(0o600)
        require(before == legacy_fingerprints(database, schema), 'V12 rows changed while its WAR was stopped')
        with dump.open('rb') as stream:
            report['dumpSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        report['checks']['populatedV12BackupCreated'] = True
        report['stage'] = 'migrate_without_workers'
        war.args.war = args.war
        war.start(False)
        current_migrations = migration_snapshot(database, schema, 13)
        require(current_migrations[:12] == old_migrations
                and application_fingerprints(legacy_fingerprints(database, schema)) == application_fingerprints(before),
                'Migration changed historical rows or existing migration checksums')
        require(database.sql(f'SELECT count(*) FROM {schema}.review_run WHERE progress_stage IS NOT NULL OR '
                'progress_updated_at IS NOT NULL OR last_saved_at IS NOT NULL;') == '0',
                'V13 invented progress for historical runs')
        require(snapshot_v12(database, schema, project) == frozen, 'Worker-disabled migration changed the interrupted request')
        check_pages(war, seeded)
        report['checks'].update({'populatedLegacyRowsPreservedAcrossV13': True,
            'priorMigrationChecksumsPreserved': True, 'legacyProgressRemainsNull': True,
            'migratedHistoricalIssuePagesRendered': True})
        war.stop()
        report['stage'] = 'recover_request_on_v13'
        war.start(True)
        HELPERS.bounded_wait(fixture.second_b_entered.is_set, deadline, 'V13 did not recover unfinished commit B', war.process)
        recovering = snapshot_v12(database, schema, project)
        require(recovering['request']['requestId'] == frozen['request']['requestId']
                and recovering['request']['actor'] == frozen['request']['actor']
                and recovering['request']['token'] != frozen['request']['token']
                and recovering['request']['runId'] != frozen['request']['runId']
                and recovering['request']['attempts'] == frozen['request']['attempts'] + 1,
                'V13 recovery did not replace only the interrupted claim and run')
        progress = database.snapshot(schema, project)['progress']
        require(progress['stage'] == 'REVIEWING' and progress['saved'] == 0 and progress['lastSavedAt'] is None,
                'V13 recovery borrowed historical saved progress')
        browser = HELPERS.Browser(war.base)
        browser.login(war.username, war.password)
        _, page = browser.request('/projects/' + str(project))
        HELPERS.verify_progress_page(page, 'REVIEWING', 0)
        fixture.release_second_b.set()
        HELPERS.bounded_wait(lambda: snapshot_v12(database, schema, project)['request']['state'] == 'SUCCEEDED',
            deadline, 'V13 recovered request did not finish', war.process)
        completed = snapshot_v12(database, schema, project)
        assert_recovered(completed, frozen)
        counts = fixture.observed()
        require(counts.get('ai_A.java') == 1 and counts.get('ai_B.java') == 2
                and counts.get('diff_A.java') == 1 and counts.get('diff_B.java') == 2,
                'V13 repeated saved commit work or skipped unfinished work')
        require(preserved_history(database, schema, seeded) == history, 'V13 recovery changed historical human decisions or evidence')
        check_pages(war, seeded)
        report['checks'].update({'v13SameRequestActorNewClaimAndAttempt': True, 'v13OnlyUnfinishedCommitRetried': True,
            'v13ExactIssuesAndCheckpoint': True, 'v13NewAttemptProgressIsolated': True, 'v13HistoricalDecisionsPreserved': True})
        war.stop()
        upgraded = legacy_fingerprints(database, schema)
        report['stage'] = 'restore_v12_into_another_new_database'
        rollback = owners[1]
        rollback.create_target()
        rollback.verify_target()
        with dump.open('rb') as stream:
            require(hashlib.file_digest(stream, 'sha256').hexdigest() == report['dumpSha256'], 'Owned V12 dump changed')
        rollback.command('pg_restore', [*rollback.connection, '--exit-on-error', '--single-transaction',
            '--no-owner', '--no-acl', '--dbname', rollback.target, dump])
        restored = UpgradeDatabase(args.psql, os.environ.get('TEST_DATABASE_URL', ''), work, rollback)
        restored.deadline = deadline
        require(legacy_fingerprints(restored, schema) == before and migration_snapshot(restored, schema, 12) == old_migrations,
                'New V12 rollback database differs from its stopped backup')
        war.database = restored
        war.args.war = args.previous_war
        war.start(False)
        require(snapshot_v12(restored, schema, project) == frozen
                and legacy_fingerprints(restored, schema) == before, 'Old rollback WAR changed saved legacy state with workers paused')
        check_pages(war, seeded)
        report['checks'].update({'newDatabaseV12RestoreExact': True, 'oldWarRollbackHistoricalPagesRendered': True,
                                 'oldWarPausedInterruptedRequestPreserved': True})
        war.stop()
        report['stage'] = 'recover_same_backup_on_old_war'
        war.start(True)
        HELPERS.bounded_wait(lambda: snapshot_v12(restored, schema, project)['request']['state'] == 'SUCCEEDED',
            deadline, 'Old rollback WAR did not recover its interrupted request', war.process)
        assert_recovered(snapshot_v12(restored, schema, project), frozen)
        counts = fixture.observed()
        require(counts.get('ai_A.java') == 1 and counts.get('ai_B.java') == 3
                and counts.get('diff_A.java') == 1 and counts.get('diff_B.java') == 3
                and not fixture.errors, 'Rollback repeated saved work or synthetic endpoints rejected requests')
        require(preserved_history(restored, schema, seeded) == history, 'Old rollback recovery changed historical decisions or evidence')
        check_pages(war, seeded)
        war.stop()
        require(legacy_fingerprints(database, schema) == upgraded, 'Rollback altered the upgraded database')
        require(original.fingerprints(BACKUP.SOURCE) == source_before, 'The original test database changed during the rehearsal')
        report['checks'].update({'oldWarRollbackRecoversSameRequest': True, 'oldWarRollbackOnlyRetriesUnfinishedCommit': True,
            'rollbackPreservesHistoricalDecisions': True, 'upgradedDatabasePreservedDuringRollback': True,
            'originalTestDatabasePreserved': True})
        report['requestCounts'] = counts
    except BaseException as error:
        failure = error
    finally:
        cleaned = cleanup_owned(war, fixture, owners)
        if source_before is not None and owners:
            try:
                owners[0].verify_owned()
                require(owners[0].fingerprints(BACKUP.SOURCE) == source_before,
                        'The original test database changed before final cleanup verification')
                report['checks']['originalTestDatabasePreservedAfterCleanup'] = True
            except BaseException:
                cleaned = False
                if failure is None:
                    failure = VerificationError('Original database preservation could not be confirmed after cleanup')
        report['checks']['ownedWarsAndFixtureStoppedAndBothDatabasesRemoved'] = cleaned
        if cleaned and work is not None:
            try:
                require(work.parent == logs and work.name.startswith('owned-work-'), 'Temporary cleanup target escaped its owned logs')
                LINUX.checked_path(work, directory=True)
                shutil.rmtree(work)
            except BaseException:
                cleaned = False
                report['checks']['ownedWarsAndFixtureStoppedAndBothDatabasesRemoved'] = False
        report['ownedDatabasesRemoved'] = cleaned and len(owners) == 2 and all(not owner.created_here for owner in owners)
        if not cleaned:
            report['cleanupFailed'] = True
            if failure is None:
                failure = VerificationError('Owned resource cleanup could not be confirmed')
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
        report['warStarts'] = war.starts if war is not None else 0
    if failure is not None:
        raise failure


def save_report(path, report):
    LINUX.checked_path(path, allow_missing=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')


def main(argv=None):
    prior = None
    installed = False
    args = None
    report = {'result': 'FAIL', 'stage': 'arguments', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
        'scope': 'Isolated Linux V12/V13 WARs, owned databases, loopback Git/AI and explicit SQL historical fixtures'}
    exit_code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        LINUX.require_linux_user()
        args = arguments(argv)
        prior = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        report['parentRunToken'] = args.parent_run_token
        run_scenario(args, report)
        require(report['checks'].get('ownedWarsAndFixtureStoppedAndBothDatabasesRemoved') is True,
                'A successful report requires confirmed resource cleanup')
        report.update({'result': 'PASS', 'stage': 'complete'})
        exit_code = 0
    except BaseException as error:
        if isinstance(error, SystemExit):
            raise
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report['failureType'] = type(error).__name__
        if isinstance(error, (VerificationError, BACKUP.SafetyError)):
            report['failure'] = str(error)
        else:
            report['failure'] = 'Details withheld; no environment HTTP bodies or database rows reported'
    finally:
        if installed:
            signal.signal(signal.SIGTERM, prior)
    if args is not None:
        try:
            save_report(args.report, report)
        except (OSError, BACKUP.SafetyError):
            print('FAIL: fresh upgrade evidence report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': populated synthetic WAR upgrade and rollback; secrets and rows withheld')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
