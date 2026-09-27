"""Only temporary files are inspected; no PostgreSQL binary, DB or process is controlled."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'test-cluster-safety.ps1'
POWERSHELL = shutil.which('pwsh') or shutil.which('powershell')


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(POWERSHELL, 'PowerShell is unavailable; isolated path/PID fixtures not executed')
class TestClusterSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reviewer-cluster-fixture-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.workspace = self.base / 'workspace'
        self.cluster = self.workspace / '.local' / 'pg-validation'
        self.cluster.mkdir(parents=True)
        self.marker = self.cluster / 'reviewer-test-cluster'
        self.version = self.cluster / 'PG_VERSION'
        self.pid_file = self.cluster / 'postmaster.pid'
        self.marker.write_text('Isolated local AI Reviewer tests only.\n', encoding='utf-8')
        self.version.write_text('17\n', encoding='utf-8')
        self.write_pid()

    def write_pid(self, **changes):
        fields = {'pid': '12345', 'directory': str(self.cluster), 'started': '1800000000',
                  'port': '55439', 'socket': '', 'listen': '127.0.0.1'}
        fields.update(changes)
        self.pid_file.write_text('\n'.join(fields.values()) + '\n0 0\nready\n', encoding='utf-8')

    def invoke(self, body):
        script = self.base / 'fixture.ps1'
        script.write_text("$ErrorActionPreference = 'Stop'\ntry {\n. " + literal(HELPER) +
                          '\n' + body + '\n} catch {\n' +
                          '[Console]::Error.WriteLine($_.Exception.Message)\nexit 9\n}\n',
                          encoding='utf-8-sig')
        return subprocess.run([POWERSHELL, '-NoLogo', '-NoProfile', '-NonInteractive', '-File', str(script)],
                              capture_output=True, text=True, timeout=20, check=False)

    def arguments(self, workspace=None, cluster=None):
        return '-Workspace ' + literal(workspace or self.workspace) + ' -Cluster ' + literal(cluster or self.cluster)

    def assert_rejected(self, body, message='Test postmaster identity validation failed.'):
        result = self.invoke(body)
        self.assertEqual(result.returncode, 9, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), '')
        self.assertEqual(result.stderr.strip(), message)
        self.assertNotIn(str(self.base), result.stderr)
        self.assertNotIn('fixture-private-value', result.stderr)

    def assert_path_rejected(self, workspace=None, cluster=None, require_marker=True):
        body = 'Assert-TestClusterPath ' + self.arguments(workspace, cluster)
        if require_marker:
            body += ' -RequireMarker'
        self.assert_rejected(body, 'Test cluster path or ownership marker validation failed.')

    def make_link(self, link, target, directory=False):
        try:
            link.symlink_to(target, target_is_directory=directory)
        except OSError:
            if os.name != 'nt' or not directory:
                self.skipTest('Creating this symbolic link requires unavailable platform privileges')
            result = self.invoke('New-Item -ItemType Junction -Path ' + literal(link) +
                                 ' -Target ' + literal(target) + ' | Out-Null')
            if result.returncode != 0:
                self.skipTest('Temporary directory reparse points are unavailable')

    def test_valid_identity_is_typed_and_assertions_are_silent(self):
        body = ('Assert-TestClusterPath ' + self.arguments() + ' -RequireMarker\n' +
                'Assert-TestPostmasterIdentity ' + self.arguments() +
                ' -Port 55439 -ExpectedPid 12345 -ExpectedStartedAt 1800000000\n' +
                '$result = Get-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439\n' +
                "if ($result.Pid -isnot [int] -or $result.StartedAt -isnot [long] -or $result.Port -isnot [int]) { throw 'Wrong types.' }\n" +
                '$result | ConvertTo-Json -Compress')
        result = self.invoke(body)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {'Pid': 12345, 'StartedAt': 1800000000, 'Port': 55439})

    def test_missing_cluster_is_allowed_only_before_initialization(self):
        empty_workspace = self.base / 'empty-workspace'
        empty_workspace.mkdir()
        absent = empty_workspace / '.local' / 'pg-validation'
        result = self.invoke('Assert-TestClusterPath ' + self.arguments(empty_workspace, absent))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '')
        self.assert_path_rejected(empty_workspace, absent)
        self.assertFalse(absent.exists())

    def test_foreign_relative_unc_parent_and_prefix_sibling_paths_are_rejected(self):
        invalid = [self.base / 'foreign', str(self.cluster) + '-other', 'relative/.local/pg-validation',
                   str(self.workspace / '.local') + '/other/../pg-validation',
                   r'\\server\share\.local\pg-validation', '//server/share/.local/pg-validation']
        for value in invalid:
            with self.subTest(cluster=value):
                self.assert_path_rejected(cluster=value, require_marker=False)
        self.assert_path_rejected(workspace='relative', require_marker=False)

    def test_marker_version_missing_wrong_oversized_or_non_file_are_rejected(self):
        for evidence, valid in ((self.marker, 'Isolated local AI Reviewer tests only.\n'), (self.version, '17\n')):
            for content in ('fixture-private-value', 'x' * 16385):
                with self.subTest(file=evidence.name, kind=len(content)):
                    evidence.write_text(content, encoding='utf-8')
                    self.assert_path_rejected()
            evidence.unlink()
            self.assert_path_rejected()
            evidence.mkdir()
            self.assert_path_rejected()
            evidence.rmdir()
            evidence.write_text(valid, encoding='utf-8')

    def test_invalid_pid_start_port_directory_and_listen_are_rejected(self):
        cases = [('pid', '0'), ('pid', '-1'), ('pid', '2147483648'), ('pid', 'not-a-number'),
                 ('started', '0'), ('started', '-1'), ('started', '9223372036854775808'),
                 ('port', '55440'), ('port', 'fixture-private-value'),
                 ('directory', str(self.base / 'foreign')), ('directory', '../pg-validation'),
                 ('listen', '0.0.0.0'), ('listen', 'localhost'), ('listen', '127.0.0.1,::1')]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                self.write_pid(**{field: value})
                self.assert_rejected('Get-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439')
        self.write_pid()
        for port in (0, 65536):
            self.assert_rejected('Get-TestPostmasterIdentity ' + self.arguments() + ' -Port ' + str(port))

    def test_pid_file_missing_truncated_oversized_and_invalid_utf8_are_rejected(self):
        for content in (b'12345\n', b'x' * 16385, b'\xff\xfe\xff'):
            self.pid_file.write_bytes(content)
            self.assert_rejected('Get-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439')
        self.pid_file.unlink()
        self.assert_rejected('Get-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439')

    def test_expected_pid_and_start_must_both_match(self):
        for expected_pid, expected_start in ((12346, 1800000000), (12345, 1800000001), (0, 1800000000), (12345, 0)):
            self.assert_rejected('Assert-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439' +
                                 f' -ExpectedPid {expected_pid} -ExpectedStartedAt {expected_start}',
                                 'Test postmaster ownership changed; refusing cluster control.')

    def test_workspace_ancestor_reparse_point_is_rejected_before_marker_access(self):
        linked_workspace = self.base / 'linked-workspace'
        self.make_link(linked_workspace, self.workspace, directory=True)
        self.assert_path_rejected(linked_workspace, linked_workspace / '.local' / 'pg-validation', require_marker=False)

    def test_cluster_leaf_reparse_point_is_rejected(self):
        saved = self.base / 'saved-cluster'
        self.cluster.rename(saved)
        self.make_link(self.cluster, saved, directory=True)
        self.assert_path_rejected(require_marker=False)

    def test_evidence_file_links_are_rejected(self):
        for evidence in (self.marker, self.version, self.pid_file):
            with self.subTest(evidence=evidence.name):
                saved = self.base / ('saved-' + evidence.name)
                evidence.rename(saved)
                self.make_link(evidence, saved)
                self.assert_rejected('Get-TestPostmasterIdentity ' + self.arguments() + ' -Port 55439')
                evidence.unlink()
                saved.rename(evidence)


if __name__ == '__main__':
    unittest.main()
