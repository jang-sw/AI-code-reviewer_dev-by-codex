"""Network-drill evidence and cleanup gates; no real WAR, database, sockets or waits."""
import copy
from contextlib import ExitStack, contextmanager, redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location('verify_review_network_recovery',
    Path(__file__).resolve().parents[1] / 'verify-review-network-recovery.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SCHEMA = 'restart_test_' + 'a' * 32
ORIGIN = 'http://127.0.0.1:18103'
CLEANUP = ('ownedWarsStopped', 'ownedProxyStopped', 'ownedDatabaseSessionsGone',
           'ownedSchemaRemoved', 'ownedWorkRemoved', 'originalTestTablesPreserved')


class NetworkRecoveryDrillTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='network-recovery-fixture-')
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).absolute()
        (self.workspace / '.local').mkdir()
        for name in ('application.war', 'java', 'psql'):
            (self.workspace / name).touch()
        self.args = types.SimpleNamespace(war=self.workspace / 'application.war', java=self.workspace / 'java',
            psql=self.workspace / 'psql', report=self.workspace / '.local/result.json', port=18102,
            blackhole_seconds=65, timeout_seconds=300, startup_timeout_seconds=45)
        context = patch.object(MODULE, 'WORKSPACE', self.workspace)
        context.start()
        self.addCleanup(context.stop)

    def argv(self, **changes):
        return [part for key, value in (vars(self.args) | changes).items()
                for part in ('--' + key.replace('_', '-'), str(value))]

    @staticmethod
    def running():
        return {'request': {'requestId': 'original-request', 'actor': 7, 'source': 'MANUAL',
                            'state': 'RUNNING', 'attempts': 1},
                'ownership': {'requestedAt': '2026-10-15T01:02:03Z', 'claim': 'original-claim', 'run': 11},
                'commits': [MODULE.HELPERS.SHA_A], 'issues': 1, 'assignees': [7], 'cursor': None,
                'runs': [{'state': 'RUNNING', 'commits': 1}],
                'progress': {'runId': 11, 'stage': 'REVIEWING', 'saved': 1,
                             'recordedAt': '2026-10-15T01:02:05Z', 'lastSavedAt': '2026-10-15T01:02:04Z'}}

    def test_preservation_rejects_changed_request_claim_run_actor_time_or_saved_work(self):
        original = self.running()
        MODULE.assert_preserved(copy.deepcopy(original), original)
        mutations = [('request', 'requestId', 'replacement'), ('request', 'actor', 8),
            ('request', 'source', 'SCHEDULED'), ('request', 'state', 'SUCCEEDED'), ('request', 'attempts', 2),
            ('ownership', 'requestedAt', 'later'), ('ownership', 'claim', 'replacement'), ('ownership', 'run', 12),
            ('root', 'commits', []), ('root', 'commits', [MODULE.HELPERS.SHA_B]),
            ('root', 'commits', [MODULE.HELPERS.SHA_A, MODULE.HELPERS.SHA_B]),
            ('root', 'issues', 0), ('root', 'issues', 2), ('root', 'cursor', MODULE.HELPERS.SHA_A),
            ('root', 'assignees', [8]), ('root', 'assignees', []),
            ('root', 'runs', [{'state': 'FAILED', 'commits': 1}]),
            ('root', 'runs', [{'state': 'RUNNING', 'commits': 0}]),
            ('root', 'runs', [{'state': 'RUNNING', 'commits': 1}, {'state': 'RUNNING', 'commits': 0}]),
            ('root', 'progress', None), ('progress', 'runId', 12), ('progress', 'saved', 0),
            ('progress', 'stage', 'FINALIZING'), ('progress', 'recordedAt', 'later'),
            ('progress', 'lastSavedAt', None)]
        for location, key, value in mutations:
            changed = copy.deepcopy(original)
            (changed if location == 'root' else changed[location])[key] = value
            with self.subTest(location=location, key=key, value=value), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_preserved(changed, original)
        self.assertEqual(original, self.running())

    def test_cli_rejects_unbounded_arguments_and_external_or_existing_reports_before_ports(self):
        variants = ({'port': 1023}, {'port': 65536}, {'blackhole_seconds': 44}, {'blackhole_seconds': 181},
            {'blackhole_seconds': 180, 'timeout_seconds': 269}, {'timeout_seconds': 601},
            {'startup_timeout_seconds': 14}, {'startup_timeout_seconds': 121},
            {'war': self.args.java}, {'report': self.workspace / 'outside.json'},
            {'report': self.workspace / '.local/../outside.json'})
        for changed in variants:
            with self.subTest(changed=changed), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**changed))
            port.assert_not_called()
        self.args.report.write_text('retained prior evidence', encoding='utf-8')
        with patch.object(MODULE.LINUX, 'unused_port') as port, self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv())
        port.assert_not_called()
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'retained prior evidence')

    def test_cli_accepts_exact_bounds_and_checks_only_its_explicit_war_port(self):
        for values in ({'port': 1024, 'blackhole_seconds': 45, 'timeout_seconds': 135, 'startup_timeout_seconds': 15},
                       {'port': 65535, 'blackhole_seconds': 180, 'timeout_seconds': 600, 'startup_timeout_seconds': 120}):
            with self.subTest(values=values), patch.object(MODULE.LINUX, 'unused_port') as port:
                args = MODULE.arguments(self.argv(**values))
            port.assert_called_once_with(values['port'])
            self.assertEqual(args.java, self.args.java)
            self.assertEqual(args.psql, self.args.psql)
            self.assertEqual(args.report, self.args.report)
        with patch.object(MODULE.LINUX, 'unused_port', side_effect=MODULE.LINUX.SafetyError('Port occupied')), \
                self.assertRaises(MODULE.LINUX.SafetyError):
            MODULE.arguments(self.argv())

    def test_extended_cli_is_explicit_linux_only_and_preserves_the_default_bounds(self):
        with patch.object(MODULE.LINUX, 'unused_port'):
            self.assertFalse(MODULE.arguments(self.argv()).extended_outage)
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.LINUX, 'unused_port'):
            for duration, budget in ((600, 720), (1200, 1320), (1200, 1800)):
                args = MODULE.arguments(self.argv(blackhole_seconds=duration, timeout_seconds=budget) + ['--extended-outage'])
                self.assertTrue(args.extended_outage)
                self.assertEqual(duration, args.blackhole_seconds)
        for duration, budget, platform in ((599, 900, 'linux'), (1201, 1500, 'linux'),
                (600, 719, 'linux'), (1200, 1801, 'linux'), (1200, 1500, 'win32'), (65, 300, 'linux')):
            with self.subTest(duration=duration, budget=budget, platform=platform), \
                    patch.object(MODULE.sys, 'platform', platform), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(blackhole_seconds=duration, timeout_seconds=budget) + ['--extended-outage'])
            port.assert_not_called()
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.LINUX, 'unused_port') as port, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv(blackhole_seconds=1200, timeout_seconds=1500))
        port.assert_not_called()

    @contextmanager
    def resource_fixture(self, soak_outage=False):
        clock = [100.0]
        war = types.SimpleNamespace(process=types.SimpleNamespace(pid=987654321))
        reader = Mock(return_value={'identity': (987654321, 456789123), 'rssBytes': 4096,
            'hwmBytes': 8192, 'threads': 12, 'cpuSeconds': 1.0})
        proxy = Mock(metrics=Mock(return_value={'active': 0, 'peakActive': 3, 'failed': False, 'mode': 'BLACKHOLE'}))
        with patch.object(MODULE.sys, 'platform', 'linux'), \
                patch.object(MODULE.os, 'sysconf', return_value=100, create=True), \
                patch.object(MODULE, 'module', return_value=types.SimpleNamespace(process_sample=reader)) as loader, \
                patch.object(MODULE.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(MODULE, 'owned_sessions', return_value=0) as sessions:
            monitor = MODULE.OutageResourceMonitor(war, Mock(), SCHEMA, proxy, soak_outage=soak_outage)
            yield types.SimpleNamespace(clock=clock, war=war, reader=reader, proxy=proxy,
                loader=loader, sessions=sessions, monitor=monitor)

    def test_outage_resources_use_only_owned_reader_and_allow_zero_established_db_connections(self):
        with self.resource_fixture() as fixture:
            fixture.monitor.sample(force=True)
            fixture.clock[0] = 105
            fixture.monitor.sample()
            self.assertEqual(1, fixture.reader.call_count)
            fixture.clock[0] = 110
            fixture.sessions.return_value = 2
            fixture.proxy.metrics.return_value['active'] = 2
            fixture.reader.return_value['cpuSeconds'] = 2.5
            fixture.monitor.sample()
            row = fixture.monitor.evidence
            self.assertEqual(2, row['samples'])
            self.assertEqual(10, row['observedSeconds'])
            self.assertEqual({'peakOwnedConnections': 2, 'lastOwnedConnections': 2}, row['database'])
            self.assertEqual({'peakActiveConnections': 2, 'lastActiveConnections': 2}, row['proxy'])
            self.assertEqual(2.5, row['process']['cpuSecondsAtLastSample'])
            fixture.reader.assert_called_with(fixture.war.process, 100)
            fixture.loader.assert_called_once_with('outage_process_resource_helpers', 'verify-review-schedule.py')
            self.assertNotIn('987654321', json.dumps(row))
            self.assertNotIn('456789123', json.dumps(row))
            self.assertFalse(row['complete'])

    def test_resource_monitor_rejects_wrong_identity_cpu_regression_and_unavailable_process_data(self):
        mutations = ({'identity': (987654322, 456789123)}, {'identity': (987654321, 456789124)},
            {'cpuSeconds': .5}, {'cpuSeconds': True}, {'cpuSeconds': float('nan')},
            {'cpuSeconds': float('inf')}, {'rssBytes': 0}, {'hwmBytes': 1024}, {'threads': True})
        for changes in mutations:
            with self.subTest(changes=changes), self.resource_fixture() as fixture:
                fixture.monitor.sample(force=True)
                fixture.clock[0] = 110
                fixture.reader.return_value.update(changes)
                with self.assertRaises(MODULE.VerificationError):
                    fixture.monitor.sample()
                self.assertEqual(1, fixture.monitor.evidence['samples'])
                self.assertFalse(fixture.monitor.evidence['complete'])
        with self.resource_fixture() as fixture:
            fixture.reader.side_effect = RuntimeError('private process observation detail')
            with self.assertRaisesRegex(MODULE.VerificationError, '^Owned outage process resource observation is unavailable$'):
                fixture.monitor.sample()
            self.assertEqual(0, fixture.monitor.evidence['samples'])

    def test_resource_monitor_rejects_unbounded_connections_gaps_and_sample_counts(self):
        for changes, connection in (({'active': 33, 'peakActive': 33}, 0), ({'active': 2, 'peakActive': 1}, 0),
                ({'active': True}, 0), ({'failed': True}, 0), ({'mode': 'CLOSED'}, 0), ({}, -1), ({}, True)):
            with self.subTest(changes=changes, connection=connection), self.resource_fixture() as fixture:
                fixture.proxy.metrics.return_value.update(changes)
                fixture.sessions.return_value = connection
                with self.assertRaises(MODULE.VerificationError):
                    fixture.monitor.sample()
        with self.resource_fixture() as fixture:
            fixture.monitor.sample()
            fixture.clock[0] = 120.001
            with self.assertRaises(MODULE.VerificationError):
                fixture.monitor.sample()
        with self.resource_fixture() as fixture:
            fixture.monitor.evidence['samples'] = MODULE.MAX_RESOURCE_SAMPLES
            with self.assertRaises(MODULE.VerificationError):
                fixture.monitor.sample()
            fixture.reader.assert_not_called()

    def test_resource_monitor_rejects_clock_reversal_even_inside_the_sampling_interval(self):
        with self.resource_fixture() as fixture:
            fixture.monitor.sample()
            fixture.clock[0] = 99.999
            with self.assertRaisesRegex(MODULE.VerificationError, 'clock regressed'):
                fixture.monitor.sample()
            self.assertEqual(1, fixture.monitor.evidence['samples'])
            self.assertEqual(1, fixture.reader.call_count)

    def test_resource_monitor_requires_full_fault_coverage_and_keeps_failure_incomplete(self):
        for duration, complete in ((590, False), (600, True)):
            with self.subTest(duration=duration), self.resource_fixture() as fixture:
                for elapsed in range(0, duration + 1, 10):
                    fixture.clock[0] = 100 + elapsed
                    fixture.monitor.sample()
                if complete:
                    fixture.monitor.finish(600)
                    self.assertTrue(fixture.monitor.evidence['complete'])
                    self.assertEqual(600, fixture.monitor.evidence['observedSeconds'])
                else:
                    with self.assertRaises(MODULE.VerificationError):
                        fixture.monitor.finish(600)
                    self.assertFalse(fixture.monitor.evidence['complete'])

    def test_resource_monitor_rejects_nonlinux_or_unowned_schema_before_loading_reader(self):
        for platform, schema in (('win32', SCHEMA), ('linux', 'public')):
            with self.subTest(platform=platform, schema=schema), patch.object(MODULE.sys, 'platform', platform), \
                    patch.object(MODULE, 'module') as loader, self.assertRaises(MODULE.VerificationError):
                MODULE.OutageResourceMonitor(Mock(), Mock(), schema, Mock())
            loader.assert_not_called()

    @staticmethod
    def extended_report():
        return {'extendedOutage': True, 'blackholeSecondsRequested': 1200,
            'blackholeSecondsObserved': 1200.5, 'workerFailureAfterSeconds': 30.5,
            'preservedStateObservations': 240, 'warStarts': 1, 'attempts': 2,
            'checks': dict.fromkeys((*CLEANUP, *MODULE.EXTENDED_BEHAVIOR), True),
            'resources': {'enabled': True, 'complete': True, 'sampleIntervalSeconds': 10,
                'maximumAllowedGapSeconds': 20, 'samples': 120, 'observedSeconds': 1200.1,
                'firstSampleElapsedSeconds': 0, 'lastSampleElapsedSeconds': 1200.1,
                'maximumObservedGapSeconds': 10.2, 'heapLimitMiBPerWar': 384,
                'process': {'peakRssBytes': 4096, 'peakHwmBytes': 8192, 'peakThreads': 12,
                    'cpuSecondsAtFirstSample': 1.0, 'cpuSecondsAtLastSample': 2.0},
                'database': {'peakOwnedConnections': 3, 'lastOwnedConnections': 0},
                'proxy': {'peakActiveConnections': 4, 'lastActiveConnections': 0}}}

    def test_extended_evidence_requires_actual_duration_recovery_and_complete_resource_observation(self):
        args = types.SimpleNamespace(blackhole_seconds=1200, timeout_seconds=1500)
        valid = self.extended_report()
        MODULE.assert_extended_evidence(args, valid)
        mutations = [('root', 'extendedOutage', 1), ('root', 'blackholeSecondsRequested', True),
            ('root', 'blackholeSecondsObserved', 1199.9), ('root', 'workerFailureAfterSeconds', None),
            ('root', 'preservedStateObservations', 119), ('root', 'warStarts', 2), ('root', 'attempts', True),
            ('resources', 'complete', 1), ('resources', 'enabled', False), ('resources', 'samples', 161),
            ('resources', 'samples', 59), ('resources', 'observedSeconds', 1199.9),
            ('resources', 'maximumObservedGapSeconds', 20.001), ('resources', 'lastSampleElapsedSeconds', 1400),
            ('process', 'cpuSecondsAtLastSample', .5), ('process', 'peakRssBytes', 0),
            ('process', 'peakThreads', True), ('database', 'lastOwnedConnections', -1),
            ('database', 'peakOwnedConnections', True), ('proxy', 'peakActiveConnections', 33)]
        for section, key, value in mutations:
            changed = copy.deepcopy(valid)
            target = changed if section == 'root' else changed['resources'] if section == 'resources' else changed['resources'][section]
            target[key] = value
            with self.subTest(section=section, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(args, changed)
        for key in MODULE.EXTENDED_BEHAVIOR:
            changed = copy.deepcopy(valid)
            changed['checks'][key] = False
            with self.subTest(behavior=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(args, changed)

    def test_extended_main_does_not_accept_old_cleanup_only_or_short_resource_evidence(self):
        args = types.SimpleNamespace(**(vars(self.args) | {'extended_outage': True,
            'blackhole_seconds': 1200, 'timeout_seconds': 1500}))
        for mode in ('success', 'cleanup-only', 'incomplete-resource', 'short-outage'):
            def scenario(_args, report):
                report['cleanupFailed'] = False
                if mode == 'cleanup-only':
                    report['checks'].update(dict.fromkeys(CLEANUP, True))
                    return
                report.update(self.extended_report())
                if mode == 'incomplete-resource': report['resources']['complete'] = False
                elif mode == 'short-outage': report['blackholeSecondsObserved'] = 65
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal'), \
                    patch.object(MODULE.signal, 'signal'), redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(args.report.read_text(encoding='utf-8'))
            self.assertEqual(0 if mode == 'success' else 1, code)
            self.assertEqual('PASS' if mode == 'success' else 'FAIL', report['result'])
            args.report.unlink()

    def test_soak_cli_requires_explicit_linux_profile_and_bounded_time_before_port_checks(self):
        for duration, budget in ((1800, 1920), (3600, 3720), (3600, 4200)):
            with self.subTest(duration=duration, budget=budget), patch.object(MODULE.sys, 'platform', 'linux'), \
                    patch.object(MODULE.LINUX, 'unused_port') as port:
                args = MODULE.arguments(self.argv(blackhole_seconds=duration, timeout_seconds=budget) + ['--soak-outage'])
            self.assertTrue(args.soak_outage)
            self.assertTrue(args.extended_outage)
            self.assertEqual(duration, args.blackhole_seconds)
            port.assert_called_once_with(self.args.port)
        for duration, budget, platform in ((1799, 2100, 'linux'), (3601, 4000, 'linux'),
                (1800, 1919, 'linux'), (3600, 4201, 'linux'), (3600, 3900, 'win32'),
                (1200, 1500, 'linux'), (65, 300, 'linux')):
            with self.subTest(duration=duration, budget=budget, platform=platform), \
                    patch.object(MODULE.sys, 'platform', platform), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(blackhole_seconds=duration, timeout_seconds=budget) + ['--soak-outage'])
            port.assert_not_called()
        for options in ([], ['--extended-outage']):
            with self.subTest(options=options), patch.object(MODULE.sys, 'platform', 'linux'), \
                    patch.object(MODULE.LINUX, 'unused_port') as port, self.assertRaises(MODULE.VerificationError):
                MODULE.arguments(self.argv(blackhole_seconds=3600, timeout_seconds=3900) + options)
            port.assert_not_called()
        with patch.object(MODULE.LINUX, 'unused_port') as port, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            MODULE.arguments(self.argv(blackhole_seconds=3600, timeout_seconds=3900) + ['--extended-outage', '--soak-outage'])
        port.assert_not_called()

    def test_soak_cli_preserves_existing_evidence_before_opening_any_port(self):
        self.args.report.write_text('retained prior soak evidence', encoding='utf-8')
        with patch.object(MODULE.sys, 'platform', 'linux'), patch.object(MODULE.LINUX, 'unused_port') as port, \
                self.assertRaises(MODULE.VerificationError):
            MODULE.arguments(self.argv(blackhole_seconds=3600, timeout_seconds=3900) + ['--soak-outage'])
        port.assert_not_called()
        self.assertEqual('retained prior soak evidence', self.args.report.read_text(encoding='utf-8'))

    def test_soak_monitor_covers_the_actual_hour_with_explicit_sample_budget(self):
        with self.resource_fixture(soak_outage=True) as fixture:
            for elapsed in range(0, 3601, 10):
                fixture.clock[0] = 100 + elapsed
                fixture.monitor.sample()
            self.assertFalse(fixture.monitor.evidence['complete'])
            fixture.monitor.finish(3600)
            row = fixture.monitor.evidence
            self.assertTrue(row['complete'])
            self.assertEqual(3600, row['observedSeconds'])
            self.assertEqual(420, row['maximumSamples'])
            self.assertEqual(362, row['samples'])
            self.assertEqual(10, row['sampleIntervalSeconds'])
            self.assertEqual(20, row['maximumAllowedGapSeconds'])
            self.assertEqual({'peakOwnedConnections': 0, 'lastOwnedConnections': 0}, row['database'])

    def test_soak_monitor_rejects_profile_crossing_exhaustion_and_short_observation(self):
        for minimum in (1200, 1799, 3601, True):
            with self.subTest(minimum=minimum), self.resource_fixture(soak_outage=True) as fixture:
                fixture.monitor.sample()
                with self.assertRaises(MODULE.VerificationError):
                    fixture.monitor.finish(minimum)
                self.assertFalse(fixture.monitor.evidence['complete'])
        with self.resource_fixture(soak_outage=True) as fixture:
            fixture.monitor.sample()
            fixture.clock[0] = 110
            with self.assertRaises(MODULE.VerificationError):
                fixture.monitor.finish(3600)
            self.assertFalse(fixture.monitor.evidence['complete'])
        with self.resource_fixture(soak_outage=True) as fixture:
            fixture.monitor.evidence['samples'] = 420
            with self.assertRaises(MODULE.VerificationError):
                fixture.monitor.sample()
            fixture.reader.assert_not_called()
        with self.resource_fixture() as fixture:
            self.assertEqual(160, fixture.monitor.evidence['maximumSamples'])
            with self.assertRaises(MODULE.VerificationError):
                fixture.monitor.finish(1800)

    def test_soak_monitor_still_rejects_missing_process_identity_and_unsafe_proxy_state(self):
        for mutation in ('identity', 'clock', 'cpu', 'proxy'):
            with self.subTest(mutation=mutation), self.resource_fixture(soak_outage=True) as fixture:
                fixture.monitor.sample()
                fixture.clock[0] = 110
                if mutation == 'identity': fixture.reader.return_value['identity'] = (987654321, 456789124)
                elif mutation == 'clock': fixture.clock[0] = 99
                elif mutation == 'cpu': fixture.reader.return_value['cpuSeconds'] = .5
                else: fixture.proxy.metrics.return_value['peakActive'] = 33
                with self.assertRaises(MODULE.VerificationError):
                    fixture.monitor.sample()
                self.assertEqual(1, fixture.monitor.evidence['samples'])
                self.assertFalse(fixture.monitor.evidence['complete'])

    def test_soak_monitor_rejects_nonboolean_profile_before_loading_process_reader(self):
        for value in (1, 'true', None):
            with self.subTest(value=value), patch.object(MODULE.sys, 'platform', 'linux'), \
                    patch.object(MODULE, 'module') as loader, self.assertRaises(MODULE.VerificationError):
                MODULE.OutageResourceMonitor(Mock(), Mock(), SCHEMA, Mock(), soak_outage=value)
            loader.assert_not_called()

    @staticmethod
    def soak_report():
        report = NetworkRecoveryDrillTest.extended_report()
        report.update(soakOutage=True, outageMode='soak', blackholeSecondsRequested=3600,
            blackholeSecondsObserved=3600.5, preservedStateObservations=720)
        report['resources'].update(maximumSamples=420, samples=361, observedSeconds=3600.1,
                                   lastSampleElapsedSeconds=3600.1)
        return report

    def test_soak_evidence_requires_hour_duration_explicit_profile_complete_resources_and_all_behavior(self):
        args = types.SimpleNamespace(blackhole_seconds=3600, timeout_seconds=3900, soak_outage=True, extended_outage=True)
        MODULE.assert_extended_evidence(args, self.soak_report())
        mutations = [('root', 'soakOutage', None), ('root', 'soakOutage', 1), ('root', 'soakOutage', False),
            ('root', 'outageMode', None), ('root', 'outageMode', 'extended'),
            ('root', 'blackholeSecondsObserved', 3599.9), ('root', 'preservedStateObservations', 359),
            ('root', 'attempts', 3), ('root', 'warStarts', 2),
            ('resources', 'maximumSamples', 160), ('resources', 'maximumSamples', None),
            ('resources', 'maximumSamples', True), ('resources', 'samples', 421), ('resources', 'samples', 179),
            ('resources', 'observedSeconds', 3599.9), ('resources', 'complete', False),
            ('resources', 'maximumObservedGapSeconds', 20.001), ('resources', 'sampleIntervalSeconds', 30),
            ('resources', 'lastSampleElapsedSeconds', 3900), ('process', 'cpuSecondsAtLastSample', .5),
            ('database', 'lastOwnedConnections', -1), ('proxy', 'peakActiveConnections', 33)]
        for section, key, value in mutations:
            report = self.soak_report()
            target = report if section == 'root' else report['resources'] if section == 'resources' else report['resources'][section]
            target[key] = value
            with self.subTest(section=section, key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(args, report)
        for key in MODULE.EXTENDED_BEHAVIOR:
            report = self.soak_report()
            report['checks'][key] = False
            with self.subTest(behavior=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(args, report)

    def test_evidence_cannot_select_soak_budget_without_matching_explicit_profile(self):
        for values in ({'blackhole_seconds': 3600, 'timeout_seconds': 3900},
                {'blackhole_seconds': 1200, 'timeout_seconds': 1500, 'soak_outage': True},
                {'blackhole_seconds': 3600, 'timeout_seconds': 3719, 'soak_outage': True},
                {'blackhole_seconds': 3600, 'timeout_seconds': 4201, 'soak_outage': True},
                {'blackhole_seconds': 3600, 'timeout_seconds': 3900, 'soak_outage': 1}):
            with self.subTest(values=values), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(types.SimpleNamespace(**values), self.soak_report())
        legacy_args = types.SimpleNamespace(blackhole_seconds=1200, timeout_seconds=1500)
        MODULE.assert_extended_evidence(legacy_args, self.extended_report())
        for key, value in (('soakOutage', True), ('soakOutage', 1), ('outageMode', 'soak')):
            report = self.extended_report()
            report[key] = value
            with self.subTest(key=key), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(legacy_args, report)
        for value in (420, True, None):
            report = self.extended_report()
            report['resources']['maximumSamples'] = value
            with self.subTest(maximumSamples=value), self.assertRaises(MODULE.VerificationError):
                MODULE.assert_extended_evidence(legacy_args, report)

    def test_soak_main_rejects_inconsistent_or_truthy_mode_flags_before_starting_the_drill(self):
        for extended, soak in ((False, True), (True, 1), (1, False)):
            args = types.SimpleNamespace(**(vars(self.args) | {'extended_outage': extended, 'soak_outage': soak,
                'blackhole_seconds': 3600, 'timeout_seconds': 3900}))
            with self.subTest(extended=extended, soak=soak), patch.object(MODULE, 'arguments', return_value=args), \
                    patch.object(MODULE, 'run') as run, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(1, MODULE.main([]))
            run.assert_not_called()
            self.assertEqual('FAIL', json.loads(args.report.read_text(encoding='utf-8'))['result'])
            args.report.unlink()

    def test_soak_main_does_not_accept_cleanup_only_short_or_incomplete_fault_evidence(self):
        args = types.SimpleNamespace(**(vars(self.args) | {'extended_outage': True, 'soak_outage': True,
            'blackhole_seconds': 3600, 'timeout_seconds': 3900}))
        for mode in ('success', 'cleanup-only', 'short-outage', 'incomplete-resource', 'wrong-profile', 'missing-behavior'):
            def scenario(_args, report):
                report['cleanupFailed'] = False
                if mode == 'cleanup-only':
                    report['checks'].update(dict.fromkeys(CLEANUP, True))
                    return
                report.update(self.soak_report())
                if mode == 'short-outage': report['blackholeSecondsObserved'] = 1200.5
                elif mode == 'incomplete-resource': report['resources']['complete'] = False
                elif mode == 'wrong-profile': report['outageMode'] = 'extended'
                elif mode == 'missing-behavior': report['checks'][MODULE.EXTENDED_BEHAVIOR[0]] = False
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'getsignal'), \
                    patch.object(MODULE.signal, 'signal'), redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(args.report.read_text(encoding='utf-8'))
            self.assertEqual(0 if mode == 'success' else 1, code)
            self.assertEqual('PASS' if mode == 'success' else 'FAIL', report['result'])
            self.assertTrue(report['soakOutage'])
            self.assertNotIn('synthetic-private-password', output.getvalue() + str(report))
            args.report.unlink()

    def test_cli_missing_paths_and_nonfiles_are_rejected_without_opening_ports(self):
        for name, value in (('war', self.workspace / 'missing.war'), ('java', self.workspace),
                            ('psql', self.workspace / 'missing-psql')):
            with self.subTest(name=name), patch.object(MODULE.LINUX, 'unused_port') as port, \
                    self.assertRaises((MODULE.VerificationError, MODULE.LINUX.SafetyError)):
                MODULE.arguments(self.argv(**{name: value}))
            port.assert_not_called()

    def test_observer_database_rejects_remote_wrong_database_and_url_options_before_any_process(self):
        bad_urls = ('jdbc:postgresql://remote.example:55439/reviewer_integration',
                    'jdbc:postgresql://127.0.0.1:55439/production',
                    'jdbc:postgresql://127.0.0.1:55439/reviewer_integration?currentSchema=public',
                    'jdbc:postgresql://127.0.0.1:0/reviewer_integration',
                    'jdbc:postgresql://127.0.0.1:65536/reviewer_integration')
        with patch.object(MODULE.HELPERS.subprocess, 'run') as process:
            for value in bad_urls:
                with self.subTest(url=value), self.assertRaises(MODULE.VerificationError):
                    MODULE.HELPERS.Database(self.args.psql, value, self.workspace)
        process.assert_not_called()

    @contextmanager
    def run_fixture(self):
        database = Mock(user='reviewer_test', password='synthetic-private-password',
                        url='jdbc:postgresql://127.0.0.1:55439/reviewer_integration')
        dropped = []
        def sql(text):
            if text.startswith('DROP SCHEMA '):
                dropped.append(text)
            return 'reviewer_integration' if text == 'SELECT current_database();' else ''
        database.sql.side_effect = sql
        fixture = Mock(start=Mock(return_value=ORIGIN))
        war = Mock(starts=1, process=None)
        proxy = Mock(port=55440)
        proxy.start.return_value = proxy
        proxy.metrics.return_value = {'mode': 'CLOSED', 'active': 0, 'failed': False}
        report = {'checks': {}}
        original = {'app_user': 'private-fingerprint-fixture', 'review_issue': 'retained'}
        def wait(check, *_args):
            MODULE.require(check(), 'Synthetic owned sessions did not close')
        with ExitStack() as stack:
            stack.enter_context(patch.object(MODULE.HELPERS, 'Database', return_value=database))
            stack.enter_context(patch.object(MODULE.HELPERS, 'Fixture', return_value=fixture))
            war_factory = stack.enter_context(patch.object(MODULE.HELPERS, 'OwnedWar', return_value=war))
            proxy_factory = stack.enter_context(patch.object(MODULE.PROXY, 'OwnedTcpProxy', return_value=proxy))
            fingerprints = stack.enter_context(patch.object(MODULE.RATE, 'public_fingerprints', return_value=original))
            stack.enter_context(patch.object(MODULE, 'server_identity', return_value='private-server-identity'))
            stack.enter_context(patch.object(MODULE.SHARED, 'schema_identity',
                side_effect=lambda *_: None if dropped else {'oid': 123, 'owner': 'reviewer_test'}))
            verify = stack.enter_context(patch.object(MODULE.SHARED, 'verify_schema'))
            sessions = stack.enter_context(patch.object(MODULE, 'owned_sessions', return_value=0))
            stack.enter_context(patch.object(MODULE.HELPERS, 'bounded_wait', side_effect=wait))
            exercise = stack.enter_context(patch.object(MODULE, 'exercise'))
            yield types.SimpleNamespace(database=database, fixture=fixture, war=war, proxy=proxy, report=report,
                verify=verify, fingerprints=fingerprints, sessions=sessions, dropped=dropped,
                exercise=exercise, war_factory=war_factory, proxy_factory=proxy_factory)

    def test_normal_cleanup_removes_only_verified_schema_and_owned_work_with_original_tables_preserved(self):
        with self.run_fixture() as fixture:
            MODULE.run(self.args, fixture.report)
            fixture.war.stop.assert_called_once_with(force=True)
            fixture.proxy.close.assert_called_once_with()
            fixture.fixture.close.assert_called_once_with()
            fixture.fixture.release_first_b.set.assert_called_once_with()
            fixture.fixture.release_second_b.set.assert_called_once_with()
            self.assertEqual(fixture.verify.call_count, 2)
            self.assertEqual(len(fixture.dropped), 1)
            schema = fixture.war_factory.call_args.args[2]
            self.assertRegex(schema, r'^restart_test_[0-9a-f]{32}$')
            self.assertEqual(fixture.dropped, ['DROP SCHEMA ' + schema + ' CASCADE;'])
            self.assertEqual(fixture.proxy_factory.call_args.args, (55439,))
            routed = fixture.war_factory.call_args.args[1]
            self.assertEqual(routed.url, 'jdbc:postgresql://127.0.0.1:55440/reviewer_integration')
            self.assertEqual(routed.sql, fixture.database.sql)
            self.assertFalse(any(call.args[0].startswith(('UPDATE ', 'DELETE ', 'ALTER '))
                                 for call in fixture.database.sql.call_args_list))
            self.assertFalse(fixture.report['cleanupFailed'])
            self.assertTrue(all(fixture.report['checks'][key] is True for key in CLEANUP))
            self.assertNotIn('private-fingerprint', json.dumps(fixture.report))
            self.assertNotIn('private-server-identity', json.dumps(fixture.report))
            self.assertNotIn('synthetic-private-password', json.dumps(fixture.report))

    def test_war_database_port_collision_is_rejected_before_proxy_or_war_start(self):
        with self.run_fixture() as fixture:
            fixture.database.url = 'jdbc:postgresql://127.0.0.1:18102/reviewer_integration'
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            fixture.proxy_factory.assert_not_called()
            fixture.war_factory.assert_not_called()
            fixture.exercise.assert_not_called()
            self.assertTrue(fixture.report['checks']['ownedSchemaRemoved'])

    def test_live_war_or_unclean_proxy_or_remaining_sessions_prevents_schema_and_work_removal(self):
        for mode in ('war', 'proxy-exception', 'proxy-active', 'sessions'):
            with self.subTest(mode=mode), self.run_fixture() as fixture:
                if mode == 'war':
                    fixture.war.process = Mock()
                    fixture.war.process.poll.return_value = None
                    fixture.war.stop.side_effect = RuntimeError('private stop failure')
                elif mode == 'proxy-exception':
                    fixture.proxy.close.side_effect = RuntimeError('private proxy failure')
                elif mode == 'proxy-active':
                    fixture.proxy.metrics.return_value = {'mode': 'CLOSED', 'active': 1, 'failed': False}
                else:
                    fixture.sessions.return_value = 1
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.run(self.args, fixture.report)
                self.assertTrue(fixture.report['cleanupFailed'])
                self.assertFalse(fixture.report['checks']['ownedSchemaRemoved'])
                self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])
                self.assertEqual(fixture.dropped, [])
                fixture.fixture.release_first_b.set.assert_called_once_with()
                fixture.fixture.release_second_b.set.assert_called_once_with()

    def test_changed_schema_identity_never_drops_replacement_schema(self):
        with self.run_fixture() as fixture:
            fixture.verify.side_effect = [None, MODULE.VerificationError('Schema identity changed')]
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            self.assertEqual(fixture.dropped, [])
            self.assertTrue(fixture.report['cleanupFailed'])
            self.assertFalse(fixture.report['checks']['ownedSchemaRemoved'])
            self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])

    def test_changed_original_table_fingerprint_cannot_be_reported_as_preserved(self):
        with self.run_fixture() as fixture:
            fixture.fingerprints.side_effect = [{'app_user': 'before'}, {'app_user': 'after'}]
            with self.assertRaises(MODULE.VerificationError):
                MODULE.run(self.args, fixture.report)
            self.assertTrue(fixture.report['checks']['ownedSchemaRemoved'])
            self.assertTrue(fixture.report['cleanupFailed'])
            self.assertIsNot(fixture.report['checks'].get('originalTestTablesPreserved'), True)
            self.assertFalse(fixture.report['checks']['ownedWorkRemoved'])

    def test_interruption_still_releases_fixture_stops_owned_resources_and_preserves_exception(self):
        with self.run_fixture() as fixture:
            interruption = KeyboardInterrupt()
            fixture.exercise.side_effect = interruption
            with self.assertRaises(KeyboardInterrupt) as caught:
                MODULE.run(self.args, fixture.report)
            self.assertIs(caught.exception, interruption)
            fixture.war.stop.assert_called_once_with(force=True)
            fixture.proxy.close.assert_called_once_with()
            fixture.fixture.release_first_b.set.assert_called_once_with()
            fixture.fixture.release_second_b.set.assert_called_once_with()
            self.assertTrue(all(fixture.report['checks'][key] is True for key in CLEANUP))

    def test_owned_war_uses_proxy_only_for_its_schema_and_discards_inherited_secrets(self):
        database = types.SimpleNamespace(url='jdbc:postgresql://127.0.0.1:55440/reviewer_integration',
            user='reviewer_test', password='synthetic-private-password', sql=Mock(return_value='1'))
        war = MODULE.HELPERS.OwnedWar(self.args, database, SCHEMA, ORIGIN, self.workspace, self.workspace, 100)
        with patch.dict(MODULE.os.environ, {'AI_API_KEY': 'inherited-private-key', 'JAVA_TOOL_OPTIONS': 'private-agent',
                                         'SPRING_CONFIG_LOCATION': 'private-path', 'DB_URL': 'remote-private-url'}), \
                patch.object(MODULE.HELPERS.time, 'monotonic', return_value=0), \
                patch.object(MODULE.HELPERS.subprocess, 'Popen') as process, \
                patch.object(MODULE.HELPERS, 'bounded_wait'), \
                patch.object(MODULE.HELPERS.urllib.request, 'build_opener'):
            war.start(True)
        self.addCleanup(war.log_stream.close)
        environment = process.call_args.kwargs['env']
        self.assertEqual(environment['DB_URL'], database.url + '?currentSchema=' + SCHEMA + '&ApplicationName=' + SCHEMA)
        self.assertEqual(environment['REVIEW_ENABLED'], 'false')
        self.assertEqual(environment['REVIEW_WORKER_ENABLED'], 'true')
        self.assertEqual(environment['AI_API_KEY'], '')
        self.assertEqual(environment['AI_BASE_URL'], ORIGIN)
        self.assertEqual(environment['JDBC_SOCKET_TIMEOUT_SECONDS'], '10')
        self.assertEqual(environment['SERVER_ADDRESS'], '127.0.0.1')
        self.assertNotIn('JAVA_TOOL_OPTIONS', environment)
        self.assertNotIn('SPRING_CONFIG_LOCATION', environment)
        self.assertIn('--spring.flyway.default-schema=' + SCHEMA, process.call_args.args[0])

    def test_main_requires_every_cleanup_flag_is_true_and_redacts_failures(self):
        for mode in ('private', 'sigterm', 'cleanupFailed', *CLEANUP, 'success'):
            installed, prior = [], Mock()
            def scenario(args, report):
                if mode == 'private':
                    raise RuntimeError('private SQL credential response fixture')
                if mode == 'sigterm':
                    installed[0][1](MODULE.signal.SIGTERM, None)
                report['cleanupFailed'] = mode == 'cleanupFailed'
                report['checks'].update({key: mode != key for key in CLEANUP})
            output = io.StringIO()
            with self.subTest(mode=mode), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), \
                    patch.object(MODULE.signal, 'getsignal', return_value=prior), \
                    patch.object(MODULE.signal, 'signal', side_effect=lambda *args: installed.append(args)), \
                    redirect_stdout(output), redirect_stderr(output):
                code = MODULE.main([])
            report = json.loads(self.args.report.read_text(encoding='utf-8'))
            self.assertEqual(code, 0 if mode == 'success' else 130 if mode == 'sigterm' else 1)
            self.assertEqual(report['result'], 'PASS' if mode == 'success' else 'FAIL')
            self.assertFalse(report['externalServicesUsed'])
            self.assertFalse(report['paidAiUsed'])
            self.assertNotIn('private SQL credential response fixture', str(report) + output.getvalue())
            if mode in ('private', 'sigterm'):
                self.assertEqual(report['failure'], 'Private error details withheld')
            self.assertEqual(installed[-1], (MODULE.signal.SIGTERM, prior))
            self.args.report.unlink()

    def test_main_preserves_an_evidence_file_created_after_argument_validation(self):
        def scenario(args, report):
            report['cleanupFailed'] = False
            report['checks'].update({key: True for key in CLEANUP})
            args.report.write_text('retained concurrent evidence', encoding='utf-8')
        output = io.StringIO()
        with patch.object(MODULE, 'arguments', return_value=self.args), patch.object(MODULE, 'run', side_effect=scenario), \
                patch.object(MODULE.signal, 'signal'), patch.object(MODULE.signal, 'getsignal'), \
                redirect_stdout(output), redirect_stderr(output):
            self.assertEqual(MODULE.main([]), 1)
        self.assertEqual(self.args.report.read_text(encoding='utf-8'), 'retained concurrent evidence')
        self.assertIn('FAIL: fresh network recovery report could not be written', output.getvalue())
        self.assertNotIn('PASS:', output.getvalue())

    def test_main_does_not_treat_missing_or_truthy_cleanup_values_as_proof(self):
        for value in (None, 1, 'true'):
            def scenario(args, report):
                report['cleanupFailed'] = False
                report['checks'].update({key: True for key in CLEANUP})
                if value is None:
                    del report['checks']['ownedSchemaRemoved']
                else:
                    report['checks']['ownedSchemaRemoved'] = value
            output = io.StringIO()
            with self.subTest(value=value), patch.object(MODULE, 'arguments', return_value=self.args), \
                    patch.object(MODULE, 'run', side_effect=scenario), patch.object(MODULE.signal, 'signal'), \
                    patch.object(MODULE.signal, 'getsignal'), redirect_stdout(output), redirect_stderr(output):
                self.assertEqual(MODULE.main([]), 1)
            self.assertEqual(json.loads(self.args.report.read_text(encoding='utf-8'))['result'], 'FAIL')
            self.assertNotIn('PASS:', output.getvalue())
            self.args.report.unlink()


if __name__ == '__main__':
    unittest.main()
