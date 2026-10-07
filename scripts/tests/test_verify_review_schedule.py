"""Scheduling-drill boundaries with mocked clocks/processes; no real database or WAR."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_schedule',
    Path(__file__).resolve().parents[1] / 'verify-review-schedule.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
ORIGIN = 'http://127.0.0.1:18101'
IDS = ['00000000-0000-0000-0000-' + str(number).zfill(12) for number in range(1, 5)]


class ScheduleDrillTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='schedule-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port_a=18099, port_b=18100,
            cycles=5, timeout_seconds=780, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items()
                for part in ('--' + key.replace('_', '-'), str(value))]

    def running(self):
        return {'now': 105.0, 'next': 120.0, 'owner': 5, 'cursor': None,
                'request': {'id': IDS[0], 'token': 'private-token', 'actor': None, 'source': 'SCHEDULED',
                    'at': 100.0, 'attempts': 1, 'run': 10, 'state': 'RUNNING'},
                'successes': 0, 'runs': 1, 'commits': 0, 'issues': 0, 'wrongAssignees': 0}

    def historical(self):
        events, runs, observed = [], [], {}
        for index, identifier in enumerate(IDS[:3]):
            instant = 100.0 + index * 60
            events.append({'id': index + 1, 'actor': None, 'detail': 'source=SCHEDULED; request=' + identifier, 'at': instant})
            runs.append({'id': index + 10, 'at': instant + 1, 'finished': instant + 2, 'status': 'SUCCEEDED', 'saved': 1 if index == 0 else 0})
            observed[identifier] = {'at': instant, 'run': index + 10}
        return {'requests': events, 'runs': runs}, observed

    def test_cli_bounds_cycles_budget_distinct_ports_and_fresh_local_report(self):
        for changes in ({'cycles': 2}, {'cycles': 11}, {'cycles': 10, 'timeout_seconds': 839}, {'timeout_seconds': 1201},
                        {'port_b': 18099}, {'port_a': 0}, {'startup_timeout_seconds': 121}, {'report': self.workspace / 'outside.json'}):
            with self.subTest(changes=changes), patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(**changes))
            socket.assert_not_called()
        with patch.object(MODULE.socket, 'socket') as socket:
            parsed = MODULE.arguments(self.argv(cycles=10, timeout_seconds=840))
        self.assertEqual(parsed.cycles, 10)
        self.assertEqual(parsed.java, self.args.java)
        self.assertEqual([call.args for call in socket.return_value.__enter__.return_value.bind.call_args_list],
                         [(('127.0.0.1', 18099),), (('127.0.0.1', 18100),)])
        self.args.report.write_text('preserved evidence', encoding='utf-8')
        with patch.object(MODULE.socket, 'socket') as socket, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        socket.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'preserved evidence')

    def test_launch_changes_only_explicit_test_schedule_and_keeps_safe_runtime(self):
        options = types.SimpleNamespace(**vars(self.args), port=18099)
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration', user='reviewer_test', password='')
        war = MODULE.ScheduledWar(options, database, SCHEMA, ORIGIN, self.workspace, self.workspace, 100)
        with patch.dict(MODULE.os.environ, {'JAVA_TOOL_OPTIONS': 'private-agent', 'AI_API_KEY': 'private-key',
                                         'REVIEW_CRON': '* * * * * *', 'SPRING_CONFIG_LOCATION': 'private-path'}):
            for enabled in (False, True):
                command, environment = war.launch(enabled)
                self.assertEqual(environment['REVIEW_ENABLED'], str(enabled).lower())
                self.assertEqual(environment['REVIEW_WORKER_ENABLED'], str(enabled).lower())
                self.assertEqual(environment['REVIEW_CRON'], '0 * * * * *')
                self.assertEqual(environment['REVIEW_CONCURRENCY'], '1')
                self.assertEqual(environment['AI_TIMEOUT_SECONDS'], '120')
                self.assertEqual(environment['AI_API_KEY'], '')
                self.assertEqual(environment['SERVER_ADDRESS'], '127.0.0.1')
                self.assertEqual(environment['GIT_ALLOWED_HOSTS'], '127.0.0.1')
                self.assertIn('-Xmx384m', command)
                self.assertNotIn('JAVA_TOOL_OPTIONS', environment)
                self.assertNotIn('SPRING_CONFIG_LOCATION', environment)
                self.assertIn('--spring.config.location=classpath:/application.properties', command)
                self.assertIn('--spring.flyway.default-schema=' + SCHEMA, command)
        with self.assertRaises(MODULE.VerificationError):
            war.launch('true')

    def test_resource_opt_in_is_linux_only_and_scopes_database_connections_without_inherited_values(self):
        with patch.object(MODULE.sys, 'platform', 'win32'), self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv() + ['--observe-resources'])
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.socket, 'socket'):
            parsed = MODULE.arguments(self.argv(cycles=8, timeout_seconds=900) + ['--observe-resources'])
        self.assertTrue(parsed.observe_resources)
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration', user='reviewer_test', password='')
        for node in ('node-a', 'node-b'):
            options = types.SimpleNamespace(**vars(self.args), port=18099, observe_resources=True, resource_node=node)
            war = MODULE.ScheduledWar(options, database, SCHEMA, ORIGIN, self.workspace, self.workspace, 100)
            _, environment = war.launch(True)
            self.assertEqual(environment['DB_URL'], database.url + '?currentSchema=' + SCHEMA + '&ApplicationName=' + SCHEMA + '_' + node)
        for schema, node in (('public', 'node-a'), (SCHEMA, 'unowned')):
            with self.assertRaises(MODULE.VerificationError):
                MODULE.application_name(schema, node)

    @staticmethod
    def proc_stat(pid=123, started=9000, cpu=150):
        fields = ['0'] * 22
        fields[0], fields[11], fields[12], fields[19] = 'S', str(cpu), '50', str(started)
        return str(pid) + ' (owned Java fixture) ' + ' '.join(fields)

    def test_proc_observation_reads_only_owned_pid_and_rejects_missing_or_replaced_counters(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        status = 'Pid:\t123\nVmRSS:\t2048 kB\nVmHWM:\t4096 kB\nThreads:\t12\n'
        with patch.object(MODULE, 'proc_text', side_effect=[self.proc_stat(), status, self.proc_stat()]) as reader:
            result = MODULE.process_sample(process, 100)
        self.assertEqual(result, {'identity': (123, 9000), 'rssBytes': 2097152, 'hwmBytes': 4194304, 'threads': 12, 'cpuSeconds': 2})
        self.assertEqual([call.args[0].as_posix() for call in reader.call_args_list], ['/proc/123/stat', '/proc/123/status', '/proc/123/stat'])
        variants = ([self.proc_stat(), status.replace('VmRSS:', 'Missing:'), self.proc_stat()],
                    [self.proc_stat(), status.replace('2048 kB', 'unknown'), self.proc_stat()],
                    [self.proc_stat(), status, self.proc_stat(started=9001)],
                    [self.proc_stat(), status.replace('Pid:\t123', 'Pid:\t999'), self.proc_stat()])
        for values in variants:
            with self.subTest(values=values), patch.object(MODULE, 'proc_text', side_effect=values), self.assertRaises(MODULE.VerificationError):
                MODULE.process_sample(process, 100)
        with patch.object(MODULE, 'proc_text', side_effect=OSError('private-process-path')):
            with self.assertRaises(MODULE.VerificationError) as error:
                MODULE.process_sample(process, 100)
        self.assertNotIn('private-process-path', str(error.exception))
        process.poll.return_value = 1
        with patch.object(MODULE, 'proc_text') as reader, self.assertRaises(MODULE.VerificationError):
            MODULE.process_sample(process, 100)
        reader.assert_not_called()

    def monitor(self, enabled=True):
        wars = [Mock(starts=1, process=Mock(pid=123)), Mock(starts=1, process=None)]
        database = Mock()
        database.sql.return_value = json.dumps({'connections': 2, 'nodeAConnections': 2, 'nodeBConnections': 0,
            'active': 1, 'idle': 1, 'idleInTransaction': 0, 'schemaBytes': 4096})
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.os, 'sysconf', return_value=100, create=True), \
                patch.object(MODULE.time, 'monotonic', return_value=0):
            monitor = MODULE.ResourceMonitor(enabled, wars, database, SCHEMA)
        return monitor, wars, database

    def test_resource_monitor_bounds_frequency_tracks_generations_and_reports_only_aggregates(self):
        monitor, wars, database = self.monitor()
        sample = {'identity': (123, 9000), 'rssBytes': 2000, 'hwmBytes': 3000, 'threads': 9, 'cpuSeconds': 2}
        with patch.object(MODULE, 'process_sample', return_value=sample), patch.object(MODULE.time, 'monotonic', return_value=0):
            monitor.sample()
        with patch.object(MODULE, 'process_sample') as sampler, patch.object(MODULE.time, 'monotonic', return_value=9.99):
            monitor.sample()
        sampler.assert_not_called()
        self.assertEqual(database.sql.call_count, 1)
        wars[0].starts, wars[0].process, wars[1].process = 2, Mock(pid=456), Mock(pid=789)
        database.sql.return_value = json.dumps({'connections': 2, 'nodeAConnections': 1, 'nodeBConnections': 1,
            'active': 1, 'idle': 1, 'idleInTransaction': 0, 'schemaBytes': 4096})
        samples = [dict(sample, identity=(456, 9500), cpuSeconds=0.5), dict(sample, identity=(789, 9800), cpuSeconds=0.25)]
        with patch.object(MODULE, 'process_sample', side_effect=samples), patch.object(MODULE.time, 'monotonic', return_value=10):
            monitor.sample()
        monitor.verify_complete()
        report = monitor.summary()
        self.assertTrue(report['complete'])
        self.assertEqual([(row['node'], row['generation']) for row in report['processGenerations']], [('node-a', 1), ('node-a', 2), ('node-b', 1)])
        self.assertEqual(report['processGenerations'][1]['cpuSecondsAtFirstSample'], 0.5)
        self.assertEqual(report['database']['sampleCount'], 2)
        self.assertEqual(report['database']['lastSampleElapsedSeconds'], 10)
        encoded = json.dumps(report)
        self.assertNotIn(SCHEMA, encoded)
        self.assertNotIn('identity', encoded)
        self.assertNotIn('9000', encoded)
        query = database.sql.call_args.args[0]
        self.assertTrue(query.startswith('SELECT '))
        self.assertIn("application_name IN ('" + SCHEMA + "_node-a','" + SCHEMA + "_node-b')", query)
        self.assertIn("n.nspname='" + SCHEMA + "'", query)
        self.assertNotIn('query,', query)
        monitor.samples = MODULE.MAX_RESOURCE_SAMPLES
        with patch.object(MODULE.time, 'monotonic', return_value=20), self.assertRaises(MODULE.VerificationError):
            monitor.sample()

    def test_resource_monitor_rejects_missing_db_values_cpu_regression_and_unobserved_generations(self):
        sample = {'identity': (123, 9000), 'rssBytes': 2000, 'hwmBytes': 3000, 'threads': 9, 'cpuSeconds': 2}
        for content in ('{}', '{"connections":null}', 'private-unparseable-response'):
            monitor, _, database = self.monitor()
            database.sql.return_value = content
            with patch.object(MODULE, 'process_sample', return_value=sample), self.assertRaises(MODULE.VerificationError):
                monitor.sample(force=True)
            self.assertFalse(monitor.summary()['complete'])
        monitor, _, database = self.monitor()
        database.sql.return_value = json.dumps({'connections': 0, 'nodeAConnections': 0, 'nodeBConnections': 0,
            'active': 0, 'idle': 0, 'idleInTransaction': 0, 'schemaBytes': 4096})
        with patch.object(MODULE, 'process_sample', return_value=sample), self.assertRaises(MODULE.VerificationError):
            monitor.sample(force=True)  # A running ready WAR must have its own matched DB connection.
        monitor, wars, _ = self.monitor()
        with patch.object(MODULE, 'process_sample', return_value=sample):
            monitor.sample(force=True)
        for changed in (dict(sample, identity=(123, 9999)), dict(sample, cpuSeconds=1)):
            with patch.object(MODULE, 'process_sample', return_value=changed), self.assertRaises(MODULE.VerificationError):
                monitor.sample(force=True)
        with self.assertRaises(MODULE.VerificationError):
            monitor.verify_complete()  # node-b has started but never supplied a sample.
        disabled, _, database = self.monitor(enabled=False)
        with patch.object(MODULE, 'process_sample') as sampler:
            disabled.sample(force=True)
        sampler.assert_not_called()
        database.sql.assert_not_called()

    def test_launch_failure_does_not_expose_os_details_and_closes_owned_log(self):
        options = types.SimpleNamespace(**vars(self.args), port=18099)
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration', user='reviewer_test', password='')
        war = MODULE.ScheduledWar(options, database, SCHEMA, ORIGIN, self.workspace, self.workspace, 100)
        with patch.object(MODULE.time, 'monotonic', return_value=0), \
                patch.object(MODULE.subprocess, 'Popen', side_effect=OSError('private executable details')), self.assertRaises(MODULE.VerificationError) as error:
            war.start(True)
        self.assertNotIn('private executable details', str(error.exception))
        self.assertIsNone(war.log_stream)
        self.assertIsNone(war.process)

    def test_slow_merge_preserves_exact_claim_and_only_advances_next_due(self):
        original = self.running()
        later = copy.deepcopy(original)
        later['next'] = 180.0
        later['now'] = 125.0
        MODULE.preserve_slow(original, later)
        for key, value in (('id', IDS[1]), ('token', 'other-token'), ('run', 11), ('attempts', 2),
                           ('state', 'SUCCEEDED'), ('actor', 5), ('source', 'MANUAL'), ('at', 110.0)):
            changed = copy.deepcopy(later)
            changed['request'][key] = value
            with self.subTest(key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.preserve_slow(original, changed)
        changed = copy.deepcopy(later)
        changed['commits'] = 1
        with self.assertRaises(MODULE.VerificationError):
            MODULE.preserve_slow(original, changed)

    def test_observations_preserve_provenance_and_bound_requests_without_reporting_tokens(self):
        observer = MODULE.Observations()
        observer.record('slow', self.running())
        current = self.running()
        current['request']['state'] = 'SUCCEEDED'
        observer.record('slow', current)
        for key, value in (('source', 'MANUAL'), ('actor', 5), ('attempts', 2), ('run', 11), ('at', 110.0), ('state', 'FAILED')):
            changed = copy.deepcopy(current)
            changed['request'][key] = value
            with self.subTest(key=key), self.assertRaises(MODULE.VerificationError):
                observer.record('slow', changed)
        observer.requests['fast1'] = {str(i): {} for i in range(MODULE.MAX_HISTORY)}
        with self.assertRaises(MODULE.VerificationError):
            observer.record('fast1', self.running())

    def test_idle_requires_successes_no_inflight_run_and_sufficient_real_margin(self):
        value = self.running()
        value['request']['state'] = 'SUCCEEDED'
        value.update(successes=5, runs=5, next=150.0)
        self.assertTrue(MODULE.idle({'slow': value}, 5, margin=20))
        for key, replacement in (('successes', 4), ('runs', 6), ('next', 110.0), ('next', None)):
            changed = copy.deepcopy(value)
            changed[key] = replacement
            self.assertFalse(MODULE.idle({'slow': changed}, 5, margin=20))
        value['request']['state'] = 'RUNNING'
        self.assertFalse(MODULE.idle({'slow': value}, 5))

    def test_history_proves_audit_intervals_unique_ids_and_reuse(self):
        value, observed = self.historical()
        result = MODULE.validate_history(value, observed, 3)
        self.assertEqual(result, {'scheduledRequests': 3, 'successfulRuns': 3, 'firstSavedCommits': 1, 'subsequentSavedCommits': 0})
        mutations = [lambda v: v['requests'][1].update(detail=v['requests'][0]['detail']),
            lambda v: v['requests'][0].update(actor=5), lambda v: v['requests'][1].update(at=110.0),
            lambda v: v['runs'][0].update(saved=0), lambda v: v['runs'][1].update(saved=1),
            lambda v: v['runs'][2].update(status='FAILED'), lambda v: v['runs'][2].update(finished=None),
            lambda v: v['runs'][1].update(at=101.5), lambda v: v['runs'].append(dict(v['runs'][0]))]
        for mutation in mutations:
            changed = copy.deepcopy(value)
            mutation(changed)
            with self.assertRaises(MODULE.VerificationError):
                MODULE.validate_history(changed, observed, 3)
        wrong = copy.deepcopy(observed)
        wrong[IDS[1]]['run'] = 10
        with self.assertRaises(MODULE.VerificationError):
            MODULE.validate_history(value, wrong, 3)
        with self.assertRaises(MODULE.VerificationError):
            MODULE.validate_history(value, {IDS[0]: observed[IDS[0]]}, 3)

    def test_state_and_bounded_history_are_scoped_read_only_and_fail_closed(self):
        database = Mock()
        database.sql.return_value = json.dumps(self.running())
        self.assertEqual(MODULE.state(database, SCHEMA, 7), self.running())
        query = database.sql.call_args.args[0]
        self.assertTrue(query.startswith('SELECT '))
        self.assertIn('p.id=7', query)
        database.sql.return_value = json.dumps(self.historical()[0])
        MODULE.history(database, SCHEMA, 7)
        query = database.sql.call_args.args[0]
        self.assertTrue(query.startswith('SELECT '))
        self.assertIn('LIMIT 64', query)
        self.assertNotIn('UPDATE ', query)
        database.sql.return_value = json.dumps({'requests': [{}] * 64, 'runs': []})
        with self.assertRaises(MODULE.VerificationError):
            MODULE.history(database, SCHEMA, 7)
        database.reset_mock()
        with self.assertRaises(MODULE.VerificationError):
            MODULE.state(database, 'public', 7)
        database.sql.assert_not_called()

    def test_waiter_checks_owned_process_and_deadline_without_real_sleep(self):
        war = Mock()
        war.process.poll.return_value = 1
        with patch.object(MODULE.time, 'monotonic', return_value=0), patch.object(MODULE.time, 'sleep') as sleep, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.wait_for(lambda: True, 10, [war], 'fixed timeout')
        sleep.assert_not_called()
        war.process.poll.return_value = None
        with patch.object(MODULE.time, 'monotonic', return_value=11), patch.object(MODULE.time, 'sleep') as sleep, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.wait_for(lambda: True, 10, [war], 'fixed timeout')
        sleep.assert_not_called()

    def test_run_cleans_two_wars_and_preserves_public_tables_without_time_mutation(self):
        database = Mock(user='reviewer_test')
        database.sql.return_value = 'reviewer_integration'
        wars = [Mock(starts=3, process=None), Mock(starts=2, process=None)]
        before = {'app_user': '0:' + 'a' * 32}
        report = {'checks': {}}
        with patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                patch.object(MODULE.RATE, 'public_fingerprints', side_effect=[before, before]), \
                patch.object(MODULE.SHARED, 'schema_identity', side_effect=[{'oid': 1, 'owner': 'reviewer_test'}, None]), \
                patch.object(MODULE.SHARED, 'verify_schema') as verify, patch.object(MODULE.CONCURRENT, 'ConcurrencyFixture') as fixture, \
                patch.object(MODULE, 'ScheduledWar', side_effect=wars), patch.object(MODULE, 'exercise'):
            MODULE.run(self.args, report)
        for war in wars:
            war.stop.assert_called_once_with(force=True)
        fixture.return_value.release_slow.set.assert_called_once()
        fixture.return_value.close.assert_called_once()
        self.assertEqual(verify.call_count, 2)
        self.assertFalse(report['cleanupFailed'])
        self.assertTrue(all(report['checks'][name] for name in MODULE.CHECKS))
        self.assertFalse(any('UPDATE ' in call.args[0] or 'DELETE ' in call.args[0] for call in database.sql.call_args_list))

    def test_alive_war_or_changed_schema_blocks_drop_and_work_removal(self):
        for mode in ('alive', 'changed'):
            database = Mock(user='reviewer_test')
            database.sql.return_value = 'reviewer_integration'
            wars = [Mock(starts=1, process=None), Mock(starts=1, process=None)]
            if mode == 'alive':
                wars[0].process = Mock()
                wars[0].process.poll.return_value = None
                wars[0].stop.side_effect = OSError('private failure')
            report = {'checks': {}}
            effects = [None, MODULE.VerificationError('changed')] if mode == 'changed' else [None]
            with self.subTest(mode=mode), patch.object(MODULE.HELPERS, 'Database', return_value=database), \
                    patch.object(MODULE.RATE, 'public_fingerprints', return_value={}), \
                    patch.object(MODULE.SHARED, 'schema_identity', return_value={'oid': 1, 'owner': 'reviewer_test'}), \
                    patch.object(MODULE.SHARED, 'verify_schema', side_effect=effects), patch.object(MODULE.CONCURRENT, 'ConcurrencyFixture'), \
                    patch.object(MODULE, 'ScheduledWar', side_effect=wars), patch.object(MODULE, 'exercise'), self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, report)
            wars[1].stop.assert_called_once_with(force=True)
            self.assertFalse(any('DROP SCHEMA' in call.args[0] for call in database.sql.call_args_list))
            self.assertFalse(report['checks']['ownedSchemaRemoved'])
            self.assertFalse(report['checks']['ownedWorkRemoved'])

    def test_main_preserves_sigterm_130_and_redacts_errors_and_requires_cleanup(self):
        for mode in ('private', 'interrupt', *MODULE.CHECKS, 'success'):
            installed, prior = [], Mock()
            def scenario(args, report):
                if mode == 'private':
                    raise RuntimeError('fixture-private-response-and-password')
                if mode == 'interrupt':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                report['cleanupFailed'] = False
                report['checks'].update({key: mode != key for key in MODULE.CHECKS})
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'interrupt' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertEqual(report['testCron'], '0 * * * * *')
            self.assertFalse(report['sqlTimeAcceleration'])
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.assertNotIn('fixture-private-response-and-password', str(report) + output.getvalue())
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()


if __name__ == '__main__':
    unittest.main()
