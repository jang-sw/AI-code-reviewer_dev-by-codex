import importlib.util
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCANNER = Path(__file__).resolve().parents[1] / "check-secrets.py"
SPEC = importlib.util.spec_from_file_location("check_secrets", SCANNER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SecretCheckTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "--quiet")

    def git(self, *args):
        return subprocess.run(
            ["git", "-C", str(self.repo), *args], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

    def write(self, name, content, add=True):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        if add:
            self.git("add", "--", name)
        return path

    def scan(self, mode="--tracked"):
        return subprocess.run(
            [sys.executable, str(SCANNER), mode, "--repo", str(self.repo)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False,
        )

    def test_long_openai_key_variants_are_detected_without_exposing_value(self):
        for suffix in ("", "proj-", "svcacct-"):
            with self.subTest(suffix=suffix):
                secret = "sk-" + suffix + "A" * 48
                self.write("settings.txt", "first line\nOPENAI_API_KEY=" + secret + "\n")
                for mode in ("--tracked", "--staged"):
                    result = self.scan(mode)
                    self.assertEqual(1, result.returncode)
                    self.assertEqual('"settings.txt":2:openai-api-key\n', result.stdout)
                    self.assertNotIn(secret, result.stdout + result.stderr)
                    self.assertNotIn("A" * 16, result.stdout + result.stderr)
                    self.assertEqual("", result.stderr)

    def test_entire_index_still_detects_secret_after_working_file_is_cleaned(self):
        secret = "sk-" + "proj-" + "B" * 48
        self.write("staged.txt", secret)
        self.write("staged.txt", "clean working file\n", add=False)
        self.write("another.txt", "safe file")
        self.assertEqual(0, self.scan("--tracked").returncode)
        staged = self.scan("--staged")
        self.assertEqual(1, staged.returncode)
        self.assertIn('"staged.txt":1:openai-api-key', staged.stdout)
        self.assertNotIn(secret, staged.stdout + staged.stderr)

    def test_private_key_header_variants(self):
        for kind in ("", "RSA ", "EC ", "DSA ", "OPENSSH ", "ENCRYPTED ", "PGP "):
            with self.subTest(kind=kind):
                suffix = " BLOCK" if kind == "PGP " else ""
                header = "-----BEGIN " + kind + "PRIVATE KEY" + suffix + "-----"
                self.write("key.txt", header + "\nredacted material")
                result = self.scan()
                self.assertEqual(1, result.returncode)
                self.assertEqual('"key.txt":1:private-key-header\n', result.stdout)
                self.assertNotIn(header, result.stdout + result.stderr)

    def test_placeholder_and_public_key_pass(self):
        self.write("settings.example", "OPENAI_API_KEY=replace-me\nsk-proj-your-key-here\n")
        self.write("public.pem", "-----BEGIN PUBLIC KEY-----\npublic test material")
        for mode in ("--tracked", "--staged"):
            result = self.scan(mode)
            self.assertEqual(0, result.returncode)
            self.assertEqual("", result.stdout + result.stderr)

    def test_minimum_payload_length_excludes_prefix_from_count(self):
        for prefix in ("", "proj-", "svcacct-"):
            with self.subTest(prefix=prefix):
                self.write("boundary.txt", "sk-" + prefix + "J" * 31)
                self.assertEqual(0, self.scan().returncode)
                self.write("boundary.txt", "sk-" + prefix + "J" * 32)
                self.assertEqual(1, self.scan().returncode)

    def test_environment_file_names_are_denied_but_example_is_allowed(self):
        self.write("config/.env.example", "OPENAI_API_KEY=replace-me\n")
        self.assertEqual(0, self.scan().returncode)
        for name in (".env", ".env.local", "config/.env.production", "config/.ENV"):
            with self.subTest(name=name):
                self.write(name, "nonsecret test value")
                for mode in ("--tracked", "--staged"):
                    result = self.scan(mode)
                    self.assertEqual(1, result.returncode)
                    self.assertIn("tracked-environment-file", result.stdout)
                    self.assertNotIn("nonsecret test value", result.stdout + result.stderr)
                self.git("rm", "--force", "--", name)

    def test_example_content_is_checked_not_blanket_allowed(self):
        self.write(".env.example", "sk-" + "C" * 48)
        result = self.scan()
        self.assertEqual(1, result.returncode)
        self.assertIn("openai-api-key", result.stdout)

    def test_untracked_and_ignored_files_are_never_scanned(self):
        self.write("safe.txt", "safe")
        self.write(".gitignore", "ignored/\n")
        self.write(".env", "sk-" + "D" * 48, add=False)
        self.write("private.txt", "sk-" + "E" * 48, add=False)
        self.write("ignored/private.txt", "sk-" + "F" * 48, add=False)
        for mode in ("--tracked", "--staged"):
            self.assertEqual(0, self.scan(mode).returncode)

    def test_working_tree_change_is_detected_before_git_add(self):
        self.write("tracked.txt", "safe")
        self.write("tracked.txt", "sk-" + "G" * 48, add=False)
        self.assertEqual(1, self.scan("--tracked").returncode)
        self.assertEqual(0, self.scan("--staged").returncode)

    def test_missing_working_file_does_not_hide_index_content(self):
        self.write("tracked.txt", "sk-" + "H" * 48).unlink()
        self.assertEqual(0, self.scan("--tracked").returncode)
        self.assertEqual(1, self.scan("--staged").returncode)

    def test_file_size_limit_fails_closed_without_printing_contents(self):
        self.write("large.txt", "Z" * (16 * 1024 * 1024 + 1))
        for mode in ("--tracked", "--staged"):
            result = self.scan(mode)
            self.assertEqual(2, result.returncode)
            self.assertEqual('"large.txt":0:file-size-limit\n', result.stdout)
            self.assertNotIn("Z" * 16, result.stdout + result.stderr)

    def test_index_symlink_is_rejected_without_following_its_target(self):
        self.write("outside.txt", "sk-" + "I" * 48, add=False)
        blob = subprocess.run(
            ["git", "-C", str(self.repo), "hash-object", "-w", "--stdin"],
            input=b"outside.txt", stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
        ).stdout.decode("ascii").strip()
        self.git("update-index", "--add", "--cacheinfo", "120000," + blob + ",link")
        for mode in ("--tracked", "--staged"):
            result = self.scan(mode)
            self.assertEqual(2, result.returncode)
            self.assertEqual('"link":0:unsupported-file-mode\n', result.stdout)
            self.assertNotIn("I" * 16, result.stdout + result.stderr)

    def test_invalid_repository_is_an_incomplete_scan(self):
        self.git("rev-parse", "--git-dir")
        with tempfile.TemporaryDirectory() as other:
            result = subprocess.run(
                [sys.executable, str(SCANNER), "--staged", "--repo", other],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False,
            )
            self.assertEqual(2, result.returncode)
            self.assertEqual('".":0:git-read-error\n', result.stdout)
            self.assertEqual("", result.stderr)

    def test_parent_junction_is_rejected_using_lstat_without_pathlib_junction_api(self):
        self.write("tracked/data.txt", "safe")
        original_lstat = Path.lstat
        parent = self.repo / "tracked"

        def metadata(target, *args, **kwargs):
            if target == parent:
                return SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_file_attributes=0x400)
            return original_lstat(target, *args, **kwargs)

        with patch.object(Path, "lstat", metadata), patch.object(Path, "is_junction", None, create=True), \
                patch.object(Path, "open", side_effect=AssertionError("must not open junction target")) as opened:
            with self.assertRaises(MODULE.ScanError) as caught:
                MODULE.working_content(self.repo, "tracked/data.txt")
            self.assertEqual("unsupported-file-link", caught.exception.category)
            self.assertEqual("tracked/data.txt", caught.exception.path)
            opened.assert_not_called()

    def test_windows_missing_reparse_metadata_fails_closed(self):
        with patch.object(MODULE.os, "name", "nt"):
            with self.assertRaises(MODULE.ScanError) as caught:
                MODULE.reject_file_link(SimpleNamespace(st_mode=stat.S_IFREG | 0o644), "tracked.txt")
        self.assertEqual("file-metadata-error", caught.exception.category)

    def test_reparse_file_is_rejected_even_when_not_a_symbolic_link(self):
        metadata = SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_file_attributes=0x400)
        with self.assertRaises(MODULE.ScanError) as caught:
            MODULE.reject_file_link(metadata, "tracked.txt")
        self.assertEqual("unsupported-file-link", caught.exception.category)

    def test_scanner_and_its_regression_fixtures_do_not_contain_literal_secrets(self):
        self.write("check-secrets.py", SCANNER.read_text(encoding="utf-8"))
        self.write("test_check_secrets.py", Path(__file__).read_text(encoding="utf-8"))
        for mode in ("--tracked", "--staged"):
            result = self.scan(mode)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertEqual("", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
