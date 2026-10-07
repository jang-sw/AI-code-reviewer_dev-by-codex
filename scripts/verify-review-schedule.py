#!/usr/bin/env python3
"""Bounded real-minute scheduling with two WARs and synthetic Git/AI only.

No SQL clocks or due dates are changed. All application writes stay in a new
identity-checked schema in loopback reviewer_integration. The one-minute cron is
a test configuration; this is not long-duration production scheduling evidence.
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
import subprocess
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


CONCURRENT = module('schedule_concurrency_helpers', 'verify-review-concurrency.py')
RATE = module('schedule_rate_helpers', 'verify-review-rate-limit.py')
HELPERS, SHARED, LINUX = RATE.HELPERS, RATE.SHARED, RATE.LINUX
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
CRON = '0 * * * * *'
OFFLINE_SECONDS = 125
MAX_HISTORY = 64
REQUEST_PATTERN = re.compile(r'source=SCHEDULED; request=([a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12})')
CHECKS = ('ownedWarsStopped', 'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')
RESOURCE_INTERVAL_SECONDS = 10
MAX_RESOURCE_SAMPLES = 160
HEAP_LIMIT_MIB = 384


def application_name(schema, node):
    require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None and node in ('node-a', 'node-b'),
            'Invalid resource observation scope')
    return schema + '_' + node


def proc_text(path):
    with path.open('rb') as stream:
        data = stream.read(32769)
    require(len(data) <= 32768, 'Owned process observation exceeded its bound')
    return data.decode('utf-8')


def process_sample(process, ticks_per_second):
    """Read only the current owned Popen; never discover or attach to other processes."""
    require(type(process.pid) is int and process.pid > 0 and process.poll() is None,
            'An owned process is unavailable for resource observation')
    try:
        root = Path('/proc') / str(process.pid)
        def counters():
            text = proc_text(root / 'stat')
            head, separator, tail = text.rpartition(')')
            require(separator and int(head.split(' ', 1)[0]) == process.pid, 'Invalid owned process counters')
            fields = tail.split()
            return int(fields[19]), int(fields[11]) + int(fields[12])
        before, _ = counters()
        values = dict(line.split(':', 1) for line in proc_text(root / 'status').splitlines() if ':' in line)
        require(int(values['Pid']) == process.pid, 'Owned process status identity changed')
        memory = {}
        for key in ('VmRSS', 'VmHWM'):
            match = re.fullmatch(r'\s*([0-9]+)\s+kB\s*', values[key])
            require(match is not None, 'Owned process memory observation is unavailable')
            memory[key] = int(match[1]) * 1024
        threads = int(values['Threads'])
        after, cpu_ticks = counters()
        require(before == after and after > 0 and threads > 0 and cpu_ticks >= 0 and process.poll() is None,
                'Owned process identity changed during resource observation')
        return {'identity': (process.pid, after), 'rssBytes': memory['VmRSS'], 'hwmBytes': memory['VmHWM'],
                'threads': threads, 'cpuSeconds': cpu_ticks / ticks_per_second}
    except (OSError, ValueError, KeyError, IndexError, UnicodeError):
        raise VerificationError('Owned process resource observation is unavailable') from None


class ResourceMonitor:
    """Ten-second aggregate observations, not a heap-usage or memory-leak verdict."""
    def __init__(self, enabled, wars, database, schema):
        require(type(enabled) is bool, 'Resource observation opt-in must be explicit')
        self.enabled, self.wars, self.database, self.schema = enabled, wars, database, schema
        self.started, self.last, self.samples, self.complete = time.monotonic(), None, 0, False
        self.generations, self.database_samples = {}, None
        self.ticks = 1
        if enabled:
            require(sys.platform.startswith('linux'), 'Resource observation requires Linux procfs')
            self.ticks = os.sysconf('SC_CLK_TCK')
            require(type(self.ticks) is int and self.ticks > 0, 'Linux process clock units are unavailable')

    def sample(self, force=False):
        if not self.enabled:
            return
        now = time.monotonic()
        if not force and self.last is not None and now - self.last < RESOURCE_INTERVAL_SECONDS:
            return
        require(self.samples < MAX_RESOURCE_SAMPLES, 'The resource observation budget was exceeded')
        elapsed = round(max(0, now - self.started), 3)
        running_nodes = []
        for index, war in enumerate(self.wars):
            if war.process is None:
                continue
            node = ('node-a', 'node-b')[index]
            running_nodes.append(node)
            sample = process_sample(war.process, self.ticks)
            key = (node, war.starts)
            previous = self.generations.get(key)
            if previous is None:
                require(len(self.generations) < 8 and type(war.starts) is int and war.starts > 0,
                        'The owned process generation budget was exceeded')
                previous = {'node': node, 'generation': war.starts, 'identity': sample['identity'],
                    'sampleCount': 0, 'firstSampleElapsedSeconds': elapsed, 'cpuSecondsAtFirstSample': sample['cpuSeconds'],
                    'peakRssBytes': 0, 'peakHwmBytes': 0, 'peakThreads': 0}
                self.generations[key] = previous
            require(previous['identity'] == sample['identity']
                    and sample['cpuSeconds'] >= previous.get('cpuSecondsAtLastSample', 0),
                    'An owned process generation changed or its CPU counter regressed')
            previous.update(sampleCount=previous['sampleCount'] + 1, lastSampleElapsedSeconds=elapsed,
                cpuSecondsAtLastSample=sample['cpuSeconds'], lastRssBytes=sample['rssBytes'],
                peakRssBytes=max(previous['peakRssBytes'], sample['rssBytes']),
                peakHwmBytes=max(previous['peakHwmBytes'], sample['hwmBytes']), peakThreads=max(previous['peakThreads'], sample['threads']))
        names = [application_name(self.schema, node) for node in ('node-a', 'node-b')]
        try:
            data = json.loads(self.database.sql(f"""SELECT json_build_object(
                'connections',count(*),'active',count(*) FILTER(WHERE state='active'),
                'nodeAConnections',count(*) FILTER(WHERE application_name='{names[0]}'),
                'nodeBConnections',count(*) FILTER(WHERE application_name='{names[1]}'),
                'idle',count(*) FILTER(WHERE state='idle'),'idleInTransaction',count(*) FILTER(WHERE state LIKE 'idle in transaction%'),
                'schemaBytes',(SELECT COALESCE(sum(pg_total_relation_size(c.oid)),0) FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='{self.schema}' AND c.relkind IN ('r','m')))
                FROM pg_stat_activity WHERE datname=current_database() AND application_name IN ('{names[0]}','{names[1]}');"""))
            keys = ('connections', 'nodeAConnections', 'nodeBConnections', 'active', 'idle', 'idleInTransaction', 'schemaBytes')
            require(isinstance(data, dict) and set(data) == set(keys)
                    and all(type(data[key]) is int and data[key] >= 0 for key in keys)
                    and sum(data[key] for key in ('active', 'idle', 'idleInTransaction')) <= data['connections']
                    and data['nodeAConnections'] + data['nodeBConnections'] == data['connections']
                    and all(data['nodeAConnections' if node == 'node-a' else 'nodeBConnections'] > 0 for node in running_nodes),
                    'Invalid isolated database resource observation')
        except (ValueError, TypeError):
            raise VerificationError('Invalid isolated database resource observation') from None
        if self.database_samples is None:
            self.database_samples = {'sampleCount': 0, 'firstSampleElapsedSeconds': elapsed, 'peaks': dict(data)}
        summary = self.database_samples
        summary.update(sampleCount=summary['sampleCount'] + 1, lastSampleElapsedSeconds=elapsed, last=dict(data))
        summary['peaks'] = {key: max(summary['peaks'][key], data[key]) for key in keys}
        self.last, self.samples = now, self.samples + 1

    def verify_complete(self):
        if self.enabled:
            expected = {(node, generation) for node, war in zip(('node-a', 'node-b'), self.wars)
                        for generation in range(1, war.starts + 1)}
            require(expected and set(self.generations) == expected and self.samples > 1 and self.database_samples is not None,
                    'Requested resource observation missed an owned process generation')
            self.complete = True

    def summary(self):
        return {'enabled': self.enabled, 'complete': self.complete, 'sampleIntervalSeconds': RESOURCE_INTERVAL_SECONDS,
            'heapLimitMiBPerWar': HEAP_LIMIT_MIB, 'samples': self.samples,
            'scope': 'Owned WAR RSS/HWM/threads and cumulative CPU since each process start; sample first/last times bound coverage. DB counts only owned application names and schema relation bytes. No heap-used, leak, host-capacity or production-duration conclusion.',
            'processGenerations': [{key: value for key, value in row.items() if key != 'identity'}
                                   for _, row in sorted(self.generations.items())],
            'database': self.database_samples}


class ScheduledWar(HELPERS.OwnedWar):
    """Reuse ownership/stop behavior; make scheduler configuration explicit at launch."""
    def launch(self, enabled):
        require(type(enabled) is bool, 'Explicit scheduler and worker state is required')
        environment = HELPERS.base_environment()
        database_url = self.database.url + '?currentSchema=' + self.schema
        if getattr(self.args, 'observe_resources', False):
            database_url += '&ApplicationName=' + application_name(self.schema, self.args.resource_node)
        environment.update({'DB_URL': database_url,
            'DB_USERNAME': self.database.user, 'DB_PASSWORD': self.database.password,
            'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(self.args.port), 'SESSION_COOKIE_SECURE': 'false',
            'BOOTSTRAP_ADMIN_USERNAME': self.username, 'BOOTSTRAP_ADMIN_GIT_USERNAME': self.username,
            'BOOTSTRAP_ADMIN_PASSWORD': self.password, 'REVIEW_ENABLED': str(enabled).lower(),
            'REVIEW_WORKER_ENABLED': str(enabled).lower(), 'REVIEW_CRON': CRON, 'REVIEW_CONCURRENCY': '1',
            'REVIEW_MAX_COMMITS': '100', 'GIT_ALLOWED_HOSTS': '127.0.0.1', 'GIT_TOKEN': '',
            'GIT_TOKEN_HOST': '127.0.0.1', 'GIT_TOKEN_ORIGIN': '', 'GITHUB_API_URL': self.fixture_url,
            'GIT_TIMEOUT_SECONDS': '10', 'GIT_OPERATION_TIMEOUT_SECONDS': '30', 'AI_PROVIDER': 'ollama',
            'AI_BASE_URL': self.fixture_url, 'AI_MODEL': HELPERS.MODEL, 'AI_API_KEY': '', 'AI_TIMEOUT_SECONDS': '120',
            'AI_CONTEXT_TOKENS': '32768', 'AI_MAX_OUTPUT_TOKENS': '4096', 'JDBC_QUERY_TIMEOUT_SECONDS': '5',
            'JDBC_SOCKET_TIMEOUT_SECONDS': '10', 'JDBC_CONNECT_TIMEOUT_SECONDS': '5'})
        command = [str(self.args.java), '-Xmx' + str(HEAP_LIMIT_MIB) + 'm', '-Djava.io.tmpdir=' + str(self.work), '-jar', str(self.args.war),
            '--spring.config.location=classpath:/application.properties', '--spring.flyway.schemas=' + self.schema,
            '--spring.flyway.default-schema=' + self.schema, '--server.servlet.context-path=/' + self.schema]
        return command, environment

    def start(self, enabled):
        require(self.process is None and time.monotonic() < self.deadline, 'The owned scheduled WAR cannot be started in its current state')
        command, environment = self.launch(enabled)
        self.starts += 1
        self.log_stream = (self.logs / ('war-start-' + str(self.starts) + '.log')).open('xb')
        try:
            self.process = subprocess.Popen(command, cwd=self.work, env=environment, stdin=subprocess.DEVNULL,
                stdout=self.log_stream, stderr=subprocess.STDOUT, creationflags=HELPERS.CREATE_FLAGS)
        except OSError:
            self.log_stream.close()
            self.log_stream = None
            raise VerificationError('Could not start the explicitly configured scheduled WAR') from None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), HELPERS.LocalRedirects(self.base))
        def ready():
            try:
                with opener.open(self.base + '/login', timeout=3) as response:
                    body = response.read(1_000_001)
                    return response.status == 200 and len(body) <= 1_000_000 and bool(HELPERS.csrf(body.decode('utf-8')))
            except (urllib.error.URLError, TimeoutError, UnicodeError, VerificationError):
                return False
        HELPERS.bounded_wait(ready, min(self.deadline, time.monotonic() + self.args.startup_timeout_seconds),
            'The scheduled WAR did not render its packaged login before the deadline', self.process)
        HELPERS.bounded_wait(lambda: self.database.sql(f"SELECT count(*) FROM {self.schema}.app_user WHERE username='restartadmin';") == '1',
            min(self.deadline, time.monotonic() + 10), 'The synthetic bootstrap account was not created', self.process)


def state(database, schema, project):
    RATE.identifiers(schema, project)
    query = f"""SELECT json_build_object('now',extract(epoch FROM clock_timestamp()),
        'next',extract(epoch FROM p.next_review_at),'owner',p.owner_id,'cursor',p.last_reviewed_sha,
        'request',(SELECT json_build_object('id',q.request_id,'token',q.claim_token,'actor',q.requested_by,
            'source',q.source,'at',extract(epoch FROM q.requested_at),'attempts',q.attempt_count,
            'run',q.run_id,'state',q.state) FROM {schema}.review_request q WHERE q.project_id=p.id),
        'successes',(SELECT count(*) FROM {schema}.review_run r WHERE r.project_id=p.id AND r.status='SUCCEEDED'),
        'runs',(SELECT count(*) FROM {schema}.review_run r WHERE r.project_id=p.id),
        'commits',(SELECT count(*) FROM {schema}.reviewed_commit c WHERE c.project_id=p.id),
        'issues',(SELECT count(*) FROM {schema}.review_issue i WHERE i.project_id=p.id),
        'wrongAssignees',(SELECT count(*) FROM {schema}.review_issue i WHERE i.project_id=p.id AND i.assignee_id<>p.owner_id))
        FROM {schema}.project p WHERE p.id={project};"""
    try:
        value = json.loads(database.sql(query))
        require(isinstance(value, dict), 'Invalid scheduled project snapshot')
        return value
    except (ValueError, TypeError):
        raise VerificationError('Invalid scheduled project snapshot') from None


class Observations:
    """Bounded, private current-request provenance; only aggregate counts are reported."""
    def __init__(self):
        self.requests = {name: {} for name in CONCURRENT.PROJECTS}

    def record(self, name, snapshot):
        current = snapshot['request']
        if current is None:
            return
        require(name in self.requests and REQUEST_PATTERN.fullmatch('source=SCHEDULED; request=' + current['id']) is not None,
                'Invalid scheduled request identity')
        require(current['source'] == 'SCHEDULED' and current['actor'] is None and current['attempts'] in (0, 1)
                and current['state'] in ('QUEUED', 'RUNNING', 'SUCCEEDED'), 'A scheduled request changed source, actor, attempts or outcome')
        records = self.requests[name]
        require(current['id'] in records or len(records) < MAX_HISTORY, 'The scheduled observation budget was exceeded')
        previous = records.get(current['id'])
        if previous is not None:
            require(all(current[key] == previous[key] for key in ('source', 'actor', 'at'))
                    and (previous['run'] is None or previous['run'] == current['run'])
                    and current['attempts'] >= previous['attempts'], 'A scheduled request replaced its provenance or execution')
        if current['state'] in ('RUNNING', 'SUCCEEDED'):
            require(current['attempts'] == 1 and type(current['run']) is int, 'An executing request has no unique first run')
        records[current['id']] = dict(current)


def preserve_slow(original, current):
    require(original['request'] is not None and current['request'] is not None
            and all(current['request'][key] == original['request'][key]
                for key in ('id', 'token', 'actor', 'source', 'at', 'attempts', 'run', 'state'))
            and current['request']['state'] == 'RUNNING' and current['request']['attempts'] == 1
            and current['request']['token'] is not None and current['runs'] == 1
            and current['commits'] == 0 and current['issues'] == 0 and current['cursor'] is None,
            'A merged schedule tick replaced the live slow request or fabricated results')


def collect(database, schema, projects, observations, monitor=None):
    if monitor is not None:
        monitor.sample()
    values = {name: state(database, schema, project) for name, project in projects.items()}
    for name, value in values.items():
        observations.record(name, value)
    return values


def active(wars):
    require(all(war.process is not None and war.process.poll() is None for war in wars), 'An expected scheduled WAR stopped during observation')


def wait_for(check, deadline, wars, message):
    while time.monotonic() < deadline:
        active(wars)
        if check():
            return
        time.sleep(1)
    raise VerificationError(message)


def idle(values, minimum, margin=0):
    return all(value['request'] is not None and value['request']['state'] == 'SUCCEEDED'
        and value['successes'] >= minimum and value['runs'] == value['successes']
        and value['next'] is not None and value['next'] - value['now'] >= margin for value in values.values())


def history(database, schema, project):
    RATE.identifiers(schema, project)
    try:
        result = json.loads(database.sql(f"""SELECT json_build_object(
            'requests',COALESCE((SELECT json_agg(t ORDER BY t.id) FROM (
                SELECT id,actor_id AS actor,detail,extract(epoch FROM created_at) AS at FROM {schema}.audit_event
                WHERE action='REVIEW_REQUESTED' AND target_type='PROJECT' AND target_id={project}
                ORDER BY id LIMIT {MAX_HISTORY}) t),'[]'::json),
            'runs',COALESCE((SELECT json_agg(t ORDER BY t.id) FROM (
                SELECT id,status,reviewed_commits AS saved,extract(epoch FROM started_at) AS at,
                    extract(epoch FROM finished_at) AS finished FROM {schema}.review_run WHERE project_id={project}
                ORDER BY id LIMIT {MAX_HISTORY}) t),'[]'::json));"""))
        require(isinstance(result, dict) and all(isinstance(result.get(key), list) and len(result[key]) < MAX_HISTORY
            for key in ('requests', 'runs')), 'The scheduled history is invalid or exceeded its bound')
        return result
    except (TypeError, ValueError):
        raise VerificationError('Invalid scheduled history comparison') from None


def validate_history(value, observed, minimum):
    requests, runs = value['requests'], value['runs']
    require(len(requests) == len(runs) and len(requests) >= minimum, 'Scheduled request and run counts do not match')
    identities = []
    for index, event in enumerate(requests):
        match = REQUEST_PATTERN.fullmatch(event['detail'])
        require(match is not None and event['actor'] is None, 'A scheduled audit event has an unexpected identity or actor')
        identifier = match[1]
        identities.append(identifier)
        end = requests[index + 1]['at'] if index + 1 < len(requests) else float('inf')
        matching = [run for run in runs if event['at'] <= run['at'] < end]
        require(len(matching) == 1, 'A scheduled request interval has missing or duplicate runs')
        run = matching[0]
        require(run['status'] == 'SUCCEEDED' and run['saved'] == (1 if index == 0 else 0)
                and run['finished'] is not None and run['finished'] >= run['at'],
                'Scheduled runs did not preserve the first result and reuse unchanged history')
        require(identifier in observed and observed[identifier]['at'] == event['at']
                and (observed[identifier]['run'] is None or observed[identifier]['run'] == run['id']),
                'Observed request provenance disagreed with the durable audit and run history')
    require(len(set(identities)) == len(identities) and set(observed) == set(identities),
            'Scheduled request UUIDs were duplicated or an observed request disappeared')
    require(len({event['at'] // 60 for event in requests}) == len(requests),
            'Two schedulers created multiple requests in one UTC cron minute')
    return {'scheduledRequests': len(requests), 'successfulRuns': len(runs), 'firstSavedCommits': 1, 'subsequentSavedCommits': 0}


def exercise(wars, database, schema, fixture, origin, report, deadline, cycles, monitor):
    a, b = wars
    observations = Observations()
    a.start(False)
    monitor.sample(force=True)
    browser = HELPERS.Browser(a.base)
    browser.login(a.username, a.password)
    projects = {name: CONCURRENT.register_project(browser, schema, origin, name) for name in CONCURRENT.PROJECTS}
    initial = collect(database, schema, projects, observations, monitor)
    require(all(value['request'] is None and value['runs'] == 0 for value in initial.values()) and not fixture.observed(),
            'Paused setup created an unexpected review request')
    monitor.sample(force=True)
    a.stop()
    a.start(True)
    monitor.sample(force=True)
    wait_for(fixture.slow_entered.is_set, min(deadline, time.monotonic() + 35), [a], 'The first scheduled slow request did not enter AI')
    held_at = time.monotonic()
    slow = state(database, schema, projects['slow'])
    observations.record('slow', slow)
    preserve_slow(slow, slow)
    require(slow['request']['source'] == 'SCHEDULED' and slow['request']['actor'] is None and slow['next'] is not None,
            'The first request was not scheduled with its next real due time')
    b.start(True)
    monitor.sample(force=True)
    def merged_and_fast():
        values = collect(database, schema, projects, observations, monitor)
        preserve_slow(slow, values['slow'])
        require(not fixture.release_slow.is_set() and not fixture.errors, 'The synthetic slow latch did not remain valid')
        return values['slow']['next'] > slow['next'] and all(values[name]['successes'] >= 1 for name in ('fast1', 'fast2'))
    wait_for(merged_and_fast, min(deadline, held_at + 90), wars, 'The next real schedule tick did not merge while fast projects completed')
    with fixture.lock:
        require(fixture.fast_while_slow == {'fast1', 'fast2'}, 'The other projects did not use the available worker while slow AI was held')
    report['checks'].update({'scheduledSlowRequestPreservedAcrossDueAdvance': True,
                            'bothFastProjectsCompleteWhileSlowHeld': True})
    report['slowHeldSeconds'] = round(time.monotonic() - held_at, 3)
    fixture.release_slow.set()
    latest = {}
    def repeated():
        latest.update(collect(database, schema, projects, observations, monitor))
        require(not fixture.errors, 'The synthetic fixture rejected duplicate or invalid review traffic')
        return idle(latest, cycles, margin=20)
    wait_for(repeated, deadline, wars, 'The real scheduled cycles did not complete in a safe idle interval')
    before_counts = {name: value['successes'] for name, value in latest.items()}
    report['successfulRunsBeforeOutage'] = before_counts
    monitor.sample(force=True)
    b.stop()
    a.stop()
    stopped = collect(database, schema, projects, observations, monitor)
    require(idle(stopped, cycles) and all(stopped[name]['successes'] == before_counts[name] for name in projects),
            'A new scheduled execution raced the idle shutdown')
    report['checks']['minimumRealCyclesPerProjectCompleted'] = True
    offline_started = time.monotonic()
    while time.monotonic() - offline_started < OFFLINE_SECONDS:
        require(time.monotonic() < deadline, 'The schedule deadline expired during the real offline interval')
        require(all(war.process is None for war in wars), 'A supposedly stopped WAR was still owned as running')
        monitor.sample()
        time.sleep(1)
    unchanged = collect(database, schema, projects, observations, monitor)
    require(all(unchanged[name]['request'] == stopped[name]['request'] and unchanged[name]['runs'] == stopped[name]['runs']
                and unchanged[name]['next'] == stopped[name]['next'] and unchanged[name]['now'] >= stopped[name]['next'] + 60
                for name in projects), 'Two real due times did not pass with durable requests unchanged while both servers were stopped')
    # Start near a genuine minute boundary so both startup/reconciliation passes
    # can be inspected before another normal tick. No SQL time adjustment occurs.
    while time.time() % 60 > 2:
        require(time.monotonic() < deadline, 'The schedule deadline expired waiting for a real minute boundary')
        monitor.sample()
        time.sleep(0.2)
    restart_epoch = time.time()
    report['bothWarsOfflineSeconds'] = round(time.monotonic() - offline_started, 3)
    a.start(True)
    monitor.sample(force=True)
    b.start(True)
    monitor.sample(force=True)
    require(time.time() // 60 == restart_epoch // 60, 'Restart crossed the observation minute; single catch-up cannot be established')
    def caught_up():
        latest.update(collect(database, schema, projects, observations, monitor))
        require(all(latest[name]['runs'] <= before_counts[name] + 1 for name in projects), 'Missed schedule ticks were replayed as extra runs')
        require(time.time() // 60 == restart_epoch // 60, 'The next normal minute arrived before catch-up could be isolated')
        return idle(latest, cycles + 1, margin=15) and all(latest[name]['successes'] == before_counts[name] + 1 for name in projects)
    wait_for(caught_up, deadline, wars, 'The persisted schedule did not catch up once per project after downtime')
    monitor.sample(force=True)
    b.stop()
    a.stop()
    final = collect(database, schema, projects, observations, monitor)
    require(all(final[name]['runs'] == before_counts[name] + 1 and final[name]['request']['state'] == 'SUCCEEDED'
                and final[name]['commits'] == 1 and final[name]['issues'] == 1 and final[name]['wrongAssignees'] == 0
                and final[name]['cursor'] == CONCURRENT.PROJECTS[name]['sha'] for name in projects),
            'The final schedule state duplicated or lost requests, reviewed commits, issues or checkpoints')
    report['successfulRunsAfterCatchUp'] = {name: value['successes'] for name, value in final.items()}
    report['projects'] = {name: validate_history(history(database, schema, project), observations.requests[name], cycles + 1)
                          for name, project in projects.items()}
    counts = fixture.observed()
    require(not fixture.errors and all(counts.get(name + '.' + operation) == 1
        for name in projects for operation in ('ai', 'detail', 'diff')), 'Unchanged history repeated AI, detail or diff work')
    report['checks'].update({'twoMissedRealDueTimesCatchUpOncePerProject': True,
        'scheduledAuditIdsUniqueAndOneRunPerRequestInterval': True, 'firstRunSavesOneThenUnchangedRunsSaveZero': True,
        'exactlyThreeCommitsAndIssues': True, 'eachAiDetailDiffCalledOnce': True})
    report['requestCounts'] = counts


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('war', 'java', 'psql', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port-a', type=int, default=18099)
    parser.add_argument('--port-b', type=int, default=18100)
    parser.add_argument('--cycles', type=int, default=5)
    parser.add_argument('--timeout-seconds', type=int, default=780)
    parser.add_argument('--startup-timeout-seconds', type=int, default=45)
    parser.add_argument('--observe-resources', action='store_true', help='Require bounded Linux procfs and isolated DB resource observations')
    args = parser.parse_args(argv)
    for name in ('war', 'java', 'psql'):
        value = getattr(args, name).resolve(strict=True) if name == 'war' else getattr(args, name).absolute()
        require(value.is_file(), 'An explicitly configured WAR or executable is missing')
        setattr(args, name, value)
    require(args.war.suffix.lower() == '.war' and args.port_a != args.port_b
            and all(1024 <= port <= 65535 for port in (args.port_a, args.port_b)), 'Distinct valid loopback WAR ports are required')
    require(3 <= args.cycles <= 10 and args.cycles * 60 + 240 <= args.timeout_seconds <= 1200
            and 15 <= args.startup_timeout_seconds <= 120, 'Invalid real-cycle count or bounded scheduling budget')
    require(not args.observe_resources or sys.platform.startswith('linux'), 'Resource observation requires Linux procfs')
    args.report = LINUX.absolute_path(args.report)
    require(WORKSPACE / '.local' in args.report.parents, 'Schedule evidence must remain in this checkout .local directory')
    LINUX.checked_path(args.report.parent, directory=True)
    LINUX.checked_path(args.report, allow_missing=True)
    require(not args.report.exists(), 'Refusing an existing schedule evidence report')
    for port in (args.port_a, args.port_b):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                raise VerificationError('A requested schedule WAR port is occupied') from None
    return args


def run(args, report):
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = 'restart_test_' + uuid.uuid4().hex
    marker = 'schedule-' + uuid.uuid4().hex
    logs = args.report.parent / schema
    wars, works = [], []
    database, identity, public_before = None, None, None
    fixture = CONCURRENT.ConcurrencyFixture()
    owned, failure, cleanup_failed = False, None, False
    monitor = None
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
                'Created schedule schema ownership could not be established')
        database.sql(f"COMMENT ON SCHEMA {schema} IS '{marker}';")
        identity = {'oid': captured['oid'], 'owner': database.user, 'marker': marker}
        SHARED.verify_schema(database, schema, identity)
        origin = fixture.start()
        for index, port in enumerate((args.port_a, args.port_b)):
            options = types.SimpleNamespace(**vars(args), port=port, resource_node=('node-a', 'node-b')[index])
            wars.append(ScheduledWar(options, database, schema, origin, works[index], works[index].parent, deadline))
        wars[1].password = wars[0].password
        monitor = ResourceMonitor(getattr(args, 'observe_resources', False), wars, database, schema)
        exercise(wars, database, schema, fixture, origin, report, deadline, args.cycles, monitor)
        monitor.verify_complete()
    except BaseException as error:
        failure = error
    finally:
        fixture.release_slow.set()
        for war in wars:
            try:
                war.stop(force=True)
            except BaseException:
                cleanup_failed = True
        try:
            fixture.close()
        except BaseException:
            cleanup_failed = True
        stopped = not any(war.process is not None and war.process.poll() is None for war in wars)
        if owned and stopped:
            try:
                SHARED.verify_schema(database, schema, identity)
                database.sql('DROP SCHEMA ' + schema + ' CASCADE;')
                require(SHARED.schema_identity(database, schema) is None, 'Owned schedule schema removal was not confirmed')
                owned = False
            except BaseException:
                cleanup_failed = True
        if stopped and not owned:
            try:
                if public_before is not None:
                    require(RATE.public_fingerprints(database) == public_before, 'Original public test tables changed during schedule verification')
                    report['checks']['originalTestTablesPreserved'] = True
                    report['originalTableCount'] = len(public_before)
            except BaseException:
                cleanup_failed = True
            for work in works:
                try:
                    require(work.parent.parent == logs and work.parent.name in ('node-a', 'node-b')
                            and work.name.startswith('owned-work-'), 'Unexpected owned schedule work directory')
                    LINUX.checked_path(work, directory=True)
                    shutil.rmtree(work)
                except BaseException:
                    cleanup_failed = True
        report['checks'].update({'ownedWarsStopped': stopped, 'ownedSchemaRemoved': not owned,
                                'ownedWorkRemoved': all(not work.exists() for work in works)})
        report['cleanupFailed'] = cleanup_failed or not stopped or owned
        report['warStarts'] = [war.starts for war in wars]
        report['elapsedMillis'] = round((time.monotonic() - started) * 1000)
        if monitor is not None:
            report['resources'] = monitor.summary()
    if failure is not None:
        raise failure
    require(not report['cleanupFailed'], 'Owned schedule resources could not be safely removed')


def main(argv=None):
    args, prior, installed = None, None, False
    report = {'result': 'FAIL', 'checks': {}, 'externalServicesUsed': False, 'paidAiUsed': False,
        'sqlTimeAcceleration': False, 'testCron': CRON, 'perWarConcurrency': 1,
        'scope': 'Two real schedulers, three synthetic static repositories, real minute ticks and a two-server outage',
        'limitations': ['Bounded local scheduling, not production long-duration availability or exactly-once paid API delivery',
            'Historical request/run association uses audit time intervals corroborated by observed current request mappings; no historical request FK exists']}
    code = 1
    def cancel(_signum, _frame):
        raise KeyboardInterrupt()
    try:
        args = arguments(argv)
        report['minimumCyclesPerProjectBeforeOutage'] = args.cycles
        prior = signal.getsignal(signal.SIGTERM)
        signal.signal(signal.SIGTERM, cancel)
        installed = True
        run(args, report)
        require(report['cleanupFailed'] is False and all(report['checks'].get(key) is True for key in CHECKS),
                'Successful scheduling evidence requires confirmed cleanup and unchanged original tables')
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
            print('FAIL: fresh schedule verification report could not be written', file=sys.stderr)
            return 1
    print(report['result'] + ': two-WAR real scheduling verification; private details withheld')
    return code


if __name__ == '__main__':
    sys.exit(main())
