"""Offline artifact verifier regression tests; no root, database or service required."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name("verify-artifact.sh").resolve()
BASH = os.environ.get("BASH_BIN") or shutil.which("bash")


def bash_path(path):
    resolved = Path(path).resolve()
    value = resolved.as_posix()
    if os.name == "nt" and resolved.drive:
        return "/" + value[0].lower() + value[2:]
    return value


@unittest.skipUnless(BASH, "Bash is required; this is not a service deployment test")
class ArtifactVerificationTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="reviewer-artifact-test-")
        self.addCleanup(self.scratch.cleanup)
        self.directory = Path(self.scratch.name)
        self.artifact = self.directory / "release with spaces.war"

    def fixture(self, *, missing=None, version="4.0.8", launcher="org.springframework.boot.loader.launch.WarLauncher"):
        entries = {
            "META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\r\nMain-Class: {launcher}\r\nSpring-Boot-Version: {version}\r\n",
            "org/springframework/boot/loader/launch/WarLauncher.class": "fixture only",
            "WEB-INF/classes/com/aicreviewer/AiCodeReviewerApplication.class": "fixture only",
            "WEB-INF/jsp/login.jsp": "fixture only",
        }
        with zipfile.ZipFile(self.artifact, "w") as archive:
            for name, value in entries.items():
                if name != missing:
                    archive.writestr(name, value)
        return hashlib.sha256(self.artifact.read_bytes()).hexdigest()

    def run_verifier(self, digest, path=None):
        return subprocess.run(
            [BASH, bash_path(SCRIPT), path or bash_path(self.artifact), digest],
            cwd=self.directory, capture_output=True, text=True, timeout=10, check=False,
        )

    def test_valid_archive_with_spaces_is_read_only(self):
        digest = self.fixture()
        original = self.artifact.read_bytes()
        result = self.run_verifier(digest)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("does not start or validate the service", result.stdout)
        self.assertEqual(original, self.artifact.read_bytes())
        self.assertEqual([self.artifact], list(self.directory.iterdir()))

    def test_changed_bytes_are_rejected_before_archive_is_trusted(self):
        digest = self.fixture()
        self.artifact.write_bytes(self.artifact.read_bytes() + b"changed")
        result = self.run_verifier(digest)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)

    def test_missing_application_or_jsp_is_rejected(self):
        for missing in ["WEB-INF/classes/com/aicreviewer/AiCodeReviewerApplication.class", "WEB-INF/jsp/login.jsp"]:
            with self.subTest(missing=missing):
                result = self.run_verifier(self.fixture(missing=missing))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("content is missing", result.stderr)

    def test_other_launcher_and_framework_version_are_rejected(self):
        for change in [dict(launcher="other.Launcher"), dict(version="0.0.0")]:
            with self.subTest(change=change):
                result = self.run_verifier(self.fixture(**change))
                self.assertNotEqual(0, result.returncode)
                self.assertIn("deployment contract", result.stderr)

    def test_relative_path_and_invalid_digest_are_rejected(self):
        digest = self.fixture()
        result = self.run_verifier(digest, self.artifact.name)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("absolute path", result.stderr)
        result = self.run_verifier("not-a-digest")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA-256 digest", result.stderr)


if __name__ == "__main__":
    unittest.main()
