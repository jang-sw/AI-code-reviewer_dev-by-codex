"""Local standard-library candidate packaging fixtures; no build, service, database or network."""

from contextlib import redirect_stderr, redirect_stdout
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib


SCRIPT = Path(__file__).absolute().parents[1] / "package-candidate.py"
SPEC = importlib.util.spec_from_file_location("package_candidate", SCRIPT)
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class CandidatePackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reviewer-candidate-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).absolute()
        self.repo = self.directory / "repository"
        self.repo.mkdir()
        for name in PACKAGE.ALLOWLIST:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            content = "# Synthetic fixture\n"
            if name.endswith("reviewer.env.example"):
                content += "".join(f"{key}={value}\n" for key, value in PACKAGE.ENVIRONMENT_DEFAULTS.items())
            path.write_text(content, encoding="utf-8", newline="\n")
        self.war = self.directory / "already built.war"
        self.write_war()
        self.output = self.directory / "new candidate"

    def write_war(self, *, changes=None, missing=None, extras=(), raw_manifest=None, manifest_compression=zipfile.ZIP_STORED):
        attributes = {
            "Manifest-Version": "1.0",
            "Main-Class": "org.springframework.boot.loader.launch.WarLauncher",
            "Start-Class": "com.aicreviewer.AiCodeReviewerApplication",
            "Spring-Boot-Version": "4.0.8",
            "Implementation-Version": "0.1.0-SNAPSHOT",
        }
        attributes.update(changes or {})
        manifest = raw_manifest if raw_manifest is not None else "".join(f"{key}: {value}\r\n" for key, value in attributes.items())
        with zipfile.ZipFile(self.war, "w") as archive:
            for name in PACKAGE.REQUIRED_WAR_ENTRIES:
                if name != missing:
                    archive.writestr(name, manifest if name.endswith("MANIFEST.MF") else "synthetic fixture only",
                                     compress_type=manifest_compression if name.endswith("MANIFEST.MF") else zipfile.ZIP_STORED)
            for name, value in extras:
                archive.writestr(name, value)
        return hashlib.sha256(self.war.read_bytes()).hexdigest()

    def package(self, **overrides):
        arguments = dict(repository=self.repo, war=self.war,
                         expected=hashlib.sha256(self.war.read_bytes()).hexdigest(), revision="a" * 40,
                         output=self.output, epoch=0)
        arguments.update(overrides)
        return PACKAGE.package_candidate(**arguments)

    def contents(self, output=None):
        with tarfile.open((output or self.output) / PACKAGE.ARCHIVE_NAME, "r:gz") as archive:
            self.assertTrue(all(member.isfile() for member in archive.getmembers()))
            return {member.name: archive.extractfile(member).read() for member in archive.getmembers()}

    def test_exact_allowlist_checksums_metadata_and_fixed_tar_headers(self):
        archive_hash = self.package()
        contents = self.contents()
        expected_paths = set(PACKAGE.ALLOWLIST) | {"ai-code-reviewer.war", "CANDIDATE.json", "SHA256SUMS"}
        self.assertEqual(expected_paths, set(contents))
        self.assertEqual(self.war.read_bytes(), contents["ai-code-reviewer.war"])
        metadata = json.loads(contents["CANDIDATE.json"])
        self.assertEqual("candidate", metadata["packageKind"])
        self.assertFalse(metadata["releaseApproved"])
        self.assertEqual("0.1.0-SNAPSHOT", metadata["version"])
        self.assertEqual("operator-provided; not verified against WAR", metadata["sourceRevisionAttestation"])
        self.assertEqual("a" * 40, metadata["sourceRevision"])
        self.assertNotIn(str(self.directory), contents["CANDIDATE.json"].decode())
        for entry in metadata["files"]:
            self.assertEqual(len(contents[entry["path"]]), entry["size"])
            self.assertEqual(hashlib.sha256(contents[entry["path"]]).hexdigest(), entry["sha256"])
        seen = set()
        for line in contents["SHA256SUMS"].decode("ascii").splitlines():
            digest, name = line.split("  ", 1)
            self.assertEqual(hashlib.sha256(contents[name]).hexdigest(), digest)
            seen.add(name)
        self.assertEqual(expected_paths - {"SHA256SUMS"}, seen)
        archive_path = self.output / PACKAGE.ARCHIVE_NAME
        self.assertEqual(hashlib.sha256(archive_path.read_bytes()).hexdigest(), archive_hash)
        self.assertEqual(f"{archive_hash}  {PACKAGE.ARCHIVE_NAME}\n",
                         (self.output / (PACKAGE.ARCHIVE_NAME + ".sha256")).read_text())
        self.assertEqual(0, int.from_bytes(archive_path.read_bytes()[4:8], "little"))
        with tarfile.open(archive_path, "r:gz") as archive:
            self.assertEqual(sorted(expected_paths), archive.getnames())
            for member in archive.getmembers():
                self.assertEqual((0, 0, "", "", 0), (member.uid, member.gid, member.uname, member.gname, member.mtime))
                self.assertEqual(0o755 if member.name.endswith(".sh") else 0o644, member.mode)

    def test_same_inputs_produce_identical_bytes_despite_source_metadata_and_crlf(self):
        self.package(epoch=12345)
        other = self.directory / "another candidate"
        for name in PACKAGE.ALLOWLIST:
            path = self.repo / name
            data = path.read_bytes().replace(b"\n", b"\r\n")
            path.write_bytes(data)
            os.utime(path, (99999999, 99999999))
        self.package(output=other, epoch=12345)
        for name in (PACKAGE.ARCHIVE_NAME, PACKAGE.ARCHIVE_NAME + ".sha256"):
            self.assertEqual((self.output / name).read_bytes(), (other / name).read_bytes())

    def test_unlisted_files_and_real_environment_files_are_never_read(self):
        for name in (".env", "reviewer.env", "database.dump", "private-key.pem", "runtime.log"):
            (self.repo / name).write_text("DO NOT INCLUDE", encoding="utf-8")
        with patch.object(PACKAGE, "read_regular", wraps=PACKAGE.read_regular) as reader:
            self.package()
        opened = {Path(call.args[0]) for call in reader.call_args_list}
        self.assertTrue(all(self.repo / name not in opened for name in (".env", "reviewer.env", "database.dump", "private-key.pem", "runtime.log")))
        self.assertNotIn(b"DO NOT INCLUDE", gzip.decompress((self.output / PACKAGE.ARCHIVE_NAME).read_bytes()))

    def test_tampered_war_fails_hash_before_output_creation(self):
        expected = hashlib.sha256(self.war.read_bytes()).hexdigest()
        self.war.write_bytes(self.war.read_bytes() + b"changed")
        with self.assertRaisesRegex(PACKAGE.PackageError, "war-sha256-mismatch"):
            self.package(expected=expected)
        self.assertFalse(self.output.exists())

    def test_each_required_war_entry_is_mandatory(self):
        for name in PACKAGE.REQUIRED_WAR_ENTRIES:
            with self.subTest(name=name):
                self.write_war(missing=name)
                with self.assertRaisesRegex(PACKAGE.PackageError, "required-war-content-missing"):
                    self.package()
                self.assertFalse(self.output.exists())

    def test_all_manifest_contract_values_are_checked(self):
        for key in ("Main-Class", "Start-Class", "Spring-Boot-Version", "Implementation-Version"):
            with self.subTest(key=key):
                self.write_war(changes={key: "other-value"})
                with self.assertRaisesRegex(PACKAGE.PackageError, "war-manifest-contract-mismatch"):
                    self.package()
        self.assertFalse(self.output.exists())

    def test_manifest_main_attributes_support_folding_but_reject_duplicate_keys_and_entry_sections(self):
        good = ("Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.\r\n"
                " launch.WarLauncher\r\nStart-Class: com.aicreviewer.AiCodeReviewerApplication\r\n"
                "Spring-Boot-Version: 4.0.8\r\nImplementation-Version: 0.1.0-SNAPSHOT\r\n")
        self.write_war(raw_manifest=good)
        self.package()
        for manifest in (good + "implementation-version: other\n",
                         "Manifest-Version: 1.0\n\nName: other\n" + good,
                         " continuation-without-attribute\n" + good):
            with self.subTest(manifest_kind=manifest[:20]):
                self.write_war(raw_manifest=manifest)
                with self.assertRaises(PACKAGE.PackageError):
                    self.package(output=self.directory / "manifest failure")

    def test_oversized_manifest_is_rejected_without_decompressing_unbounded_data(self):
        self.write_war(raw_manifest="X: " + "A" * (64 * 1024))
        with self.assertRaisesRegex(PACKAGE.PackageError, "manifest-size-limit"):
            self.package()

    def test_deflated_manifest_is_supported_but_other_compression_is_rejected(self):
        self.write_war(manifest_compression=zipfile.ZIP_DEFLATED)
        self.package()
        for method in (zipfile.ZIP_BZIP2, zipfile.ZIP_LZMA):
            with self.subTest(method=method):
                self.write_war(manifest_compression=method)
                with self.assertRaisesRegex(PACKAGE.PackageError, "unsupported-war-manifest-compression"):
                    self.package(output=self.directory / f"unsupported-{method}")

    def test_forged_small_manifest_size_cannot_hide_large_actual_inflation(self):
        with zipfile.ZipFile(self.war) as archive:
            prefix = archive.read("META-INF/MANIFEST.MF")
        self.write_war(raw_manifest=prefix.decode() + "\n" + "A" * (1024 * 1024),
                       manifest_compression=zipfile.ZIP_DEFLATED)
        data = bytearray(self.war.read_bytes())
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            info = archive.getinfo("META-INF/MANIFEST.MF")
        central = data.index(b"PK\x01\x02")  # Fixture deliberately writes the manifest first.
        for offset in (info.header_offset + 22, central + 24):
            struct.pack_into("<I", data, offset, len(prefix))
        for offset in (info.header_offset + 14, central + 16):
            struct.pack_into("<I", data, offset, zlib.crc32(prefix))
        self.war.write_bytes(data)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertLess(archive.getinfo("META-INF/MANIFEST.MF").file_size, PACKAGE.MAX_MANIFEST_BYTES)
        with self.assertRaisesRegex(PACKAGE.PackageError, "war-manifest-size-limit"):
            self.package()
        self.assertFalse(self.output.exists())

    def test_corrupt_deflate_has_fixed_error_and_no_cli_traceback(self):
        self.write_war(manifest_compression=zipfile.ZIP_DEFLATED)
        data = bytearray(self.war.read_bytes())
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            info = archive.getinfo("META-INF/MANIFEST.MF")
        header = struct.unpack("<4s5H3I2H", data[info.header_offset:info.header_offset + 30])
        start = info.header_offset + 30 + header[9] + header[10]
        data[start] = 0x07  # Reserved DEFLATE block type; zlib must reject it.
        self.war.write_bytes(data)
        with self.assertRaisesRegex(PACKAGE.PackageError, "invalid-war-archive"):
            self.package()
        error = io.StringIO()
        with redirect_stderr(error), redirect_stdout(io.StringIO()):
            code = PACKAGE.main(["--war", str(self.war), "--expected-war-sha256", hashlib.sha256(data).hexdigest(),
                                 "--source-revision", "a" * 40, "--output-dir", str(self.output)])
        self.assertEqual(1, code)
        self.assertEqual("Candidate packaging failed: invalid-war-archive\n", error.getvalue())
        self.assertNotIn("Traceback", error.getvalue())

    def test_eof_from_zip_reader_is_sanitized(self):
        with patch.object(PACKAGE.zipfile.ZipFile, "open", side_effect=EOFError("private fixture marker")):
            with self.assertRaisesRegex(PACKAGE.PackageError, "invalid-war-archive") as raised:
                self.package()
        self.assertNotIn("private fixture marker", str(raised.exception))

    def test_duplicate_traversal_absolute_backslash_and_zip_links_are_rejected(self):
        bad_names = ["META-INF/MANIFEST.MF", "../outside", "/outside", "a/../../outside", "a\\outside", "C:/outside"]
        link = zipfile.ZipInfo("linked-entry")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        bad_names.append(link)
        for index, name in enumerate(bad_names):
            with self.subTest(name=str(name)):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    self.write_war(extras=[(name, "fixture")])
                if name == "a\\outside":
                    # Windows ZipInfo normalizes input separators when writing; put the
                    # adversarial name in both ZIP headers without changing their sizes.
                    self.war.write_bytes(self.war.read_bytes().replace(b"a/outside", b"a\\outside"))
                output = self.directory / f"unsafe-case-{index}"
                with self.assertRaisesRegex(PACKAGE.PackageError, "unsafe-or-duplicate-war-entry"):
                    self.package(output=output)
                self.assertFalse(output.exists())

    def test_bad_zip_and_size_limits_fail_closed(self):
        self.war.write_bytes(b"not a zip archive")
        with self.assertRaisesRegex(PACKAGE.PackageError, "invalid-war-archive"):
            self.package()
        self.write_war()
        with patch.object(PACKAGE, "MAX_WAR_BYTES", 1):
            with self.assertRaisesRegex(PACKAGE.PackageError, "input-size-limit"):
                self.package()
        with patch.object(PACKAGE, "MAX_TEXT_BYTES", 1):
            with self.assertRaisesRegex(PACKAGE.PackageError, "input-size-limit"):
                self.package()
        with patch.object(PACKAGE, "MAX_ZIP_ENTRIES", 1):
            with self.assertRaisesRegex(PACKAGE.PackageError, "entry-count-limit"):
                self.package()

    def test_environment_requires_exact_unfilled_defaults_and_disabled_workers(self):
        path = self.repo / "deploy/linux/reviewer.env.example"
        original = path.read_text()
        for change in (original.replace("DB_PASSWORD=\n", "DB_PASSWORD=not-a-placeholder\n"),
                       original.replace("GIT_TOKEN=\n", "GIT_TOKEN=local-token\n"),
                       original.replace("127.0.0.1:5432/ai_reviewer", "user:secret@127.0.0.1:5432/ai_reviewer"),
                       original.replace("REVIEW_WORKER_ENABLED=false", "REVIEW_WORKER_ENABLED=true"),
                       original + "UNEXPECTED_PASSWORD=\n", original + "DB_PASSWORD=\n",
                       original.replace("OPENAI_API_KEY=\n", "")):
            path.write_text(change, encoding="utf-8")
            with self.assertRaisesRegex(PACKAGE.PackageError, "environment-template-must-be-unfilled-defaults"):
                self.package()
            self.assertFalse(self.output.exists())

    def test_known_secret_text_patterns_are_rejected_without_value_in_exception(self):
        path = self.repo / "docs/OPERATIONS.md"
        for value in ("sk-" + "proj-" + "A" * 48, "-----BEGIN " + "PRIVATE KEY-----"):
            path.write_text(value, encoding="utf-8")
            with self.assertRaises(PACKAGE.PackageError) as raised:
                self.package()
            self.assertEqual("possible-secret-in-allowed-text", str(raised.exception))
            self.assertNotIn(value, str(raised.exception))

    def test_missing_allowed_document_fails_before_output_creation(self):
        (self.repo / PACKAGE.ALLOWLIST[-1]).unlink()
        with self.assertRaisesRegex(PACKAGE.PackageError, "required-input-or-parent-missing"):
            self.package()
        self.assertFalse(self.output.exists())

    def test_existing_output_directory_and_file_are_preserved(self):
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_bytes(b"preserve existing output")
        with self.assertRaisesRegex(PACKAGE.PackageError, "output-already-exists"):
            self.package()
        self.assertEqual(b"preserve existing output", sentinel.read_bytes())
        other = self.directory / "existing-file"
        other.write_bytes(b"preserve file")
        with self.assertRaisesRegex(PACKAGE.PackageError, "output-already-exists"):
            self.package(output=other)
        self.assertEqual(b"preserve file", other.read_bytes())

    def test_relative_parent_traversal_paths_missing_parent_and_invalid_claims_are_rejected(self):
        cases = [dict(war=Path("relative.war")), dict(output=Path("relative-output")),
                 dict(output=self.directory / "a" / ".." / "escape"),
                 dict(output=self.directory / "missing-parent" / "new"),
                 dict(expected="x" * 64), dict(revision="b" * 39), dict(epoch=-1), dict(epoch=2**32)]
        for change in cases:
            with self.subTest(change=list(change)):
                with self.assertRaises(PACKAGE.PackageError):
                    self.package(**change)
        self.assertFalse(self.output.exists())

    def symlink(self, target, path, directory=False):
        try:
            path.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError):
            self.skipTest("Creating filesystem symlinks is unavailable; synthetic reparse test remains required")

    def test_war_file_symlink_is_rejected(self):
        link = self.directory / "linked.war"
        self.symlink(self.war, link)
        with self.assertRaisesRegex(PACKAGE.PackageError, "links-and-reparse"):
            self.package(war=link)

    def test_input_parent_and_output_parent_symlinks_are_rejected(self):
        link = self.directory / "linked-repository"
        self.symlink(self.repo, link, directory=True)
        with self.assertRaisesRegex(PACKAGE.PackageError, "links-and-reparse"):
            self.package(repository=link)
        with self.assertRaisesRegex(PACKAGE.PackageError, "links-and-reparse"):
            self.package(output=link / "new-output")

    def test_all_windows_reparse_points_are_rejected_without_python312_junction_api(self):
        metadata = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_file_attributes=0x400)
        with self.assertRaisesRegex(PACKAGE.PackageError, "links-and-reparse"):
            PACKAGE.reject_link(metadata)
        with patch.object(PACKAGE.os, "name", "nt"):
            with self.assertRaisesRegex(PACKAGE.PackageError, "file-metadata-unavailable"):
                PACKAGE.reject_link(SimpleNamespace(st_mode=stat.S_IFDIR))

    def test_failure_removes_only_owned_partial_output_and_preserves_other_directory(self):
        other = self.directory / "another-output"
        other.mkdir()
        (other / "keep").write_bytes(b"untouched")

        def fail(stream, files, epoch):
            stream.write(b"partial")
            raise OSError("synthetic write failure")

        with patch.object(PACKAGE, "write_archive", side_effect=fail):
            with self.assertRaises(OSError):
                self.package()
        self.assertFalse(self.output.exists())
        self.assertEqual(b"untouched", (other / "keep").read_bytes())

    def test_cleanup_preserves_unexpected_file_and_never_recursively_deletes(self):
        def fail(stream, files, epoch):
            (self.output / "not-created-by-packager").write_bytes(b"preserve")
            raise OSError("synthetic write failure")

        with patch.object(PACKAGE, "write_archive", side_effect=fail):
            with self.assertRaises(OSError):
                self.package()
        self.assertEqual(b"preserve", (self.output / "not-created-by-packager").read_bytes())
        self.assertFalse((self.output / PACKAGE.ARCHIVE_NAME).exists())

    def test_sidecar_injected_after_archive_is_not_deleted_when_exclusive_create_fails(self):
        original_writer = PACKAGE.write_archive

        def inject(stream, files, epoch):
            original_writer(stream, files, epoch)
            (self.output / (PACKAGE.ARCHIVE_NAME + ".sha256")).write_bytes(b"created by someone else")

        with patch.object(PACKAGE, "write_archive", side_effect=inject):
            with self.assertRaises(OSError):
                self.package()
        self.assertEqual(b"created by someone else", (self.output / (PACKAGE.ARCHIVE_NAME + ".sha256")).read_bytes())
        self.assertFalse((self.output / PACKAGE.ARCHIVE_NAME).exists())

    def test_cli_errors_never_print_supplied_paths_or_secret_values(self):
        marker = "private-marker-do-not-print"
        error = io.StringIO()
        with redirect_stderr(error), self.assertRaises(SystemExit):
            PACKAGE.main(["--source-date-epoch", marker])
        self.assertNotIn(marker, error.getvalue())
        args = ["--war", str(self.war), "--expected-war-sha256", marker,
                "--source-revision", "a" * 40, "--output-dir", str(self.output)]
        with redirect_stderr(error), redirect_stdout(io.StringIO()):
            code = PACKAGE.main(args)
        self.assertEqual(1, code)
        self.assertNotIn(marker, error.getvalue())
        self.assertNotIn(str(self.directory), error.getvalue())


if __name__ == "__main__":
    unittest.main()
