"""Offline WAR/dependency coverage fixtures; no Maven, service or network calls."""

from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
import warnings
import zipfile
import zlib


SCRIPT = Path(__file__).absolute().parents[1] / "check-war-dependencies.py"
SPEC = importlib.util.spec_from_file_location("check_war_dependencies", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class WarDependencyAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="reviewer-war-audit-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).absolute()
        self.war = self.directory / "already built.war"
        self.inventory = self.directory / "fresh inventory.txt"
        self.pom = self.directory / "pom.xml"
        self.approved = self.directory / "reviewed public coordinates.txt"
        self.policy = self.directory / "supplemental.json"
        self.cache = self.directory / "maven repository"
        self.cache.mkdir()
        self.report = self.directory / "fresh report.json"
        self.pom.write_bytes(
            b'<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
            b' <modelVersion>4.0.0</modelVersion>\n'
            b' <groupId>org.example.app</groupId><artifactId>reviewer-app</artifactId>\n'
            b' <version>0.1.0-SNAPSHOT</version><packaging>war</packaging>\n'
            b'</project>\n'
        )
        self.inventory_lines = [
            "The following files have been resolved:",
            "   org.example:corelib:jar:1.2:compile -- module example.corelib [auto]",
            "   org.example:container:jar:2.0:provided",
            "   org.example:starter:jar:1.2:runtime",
            "   private.test:fixture:jar:secret-version:test",
        ]
        self.approved_lines = [
            "# Synthetic reviewed public fixture coordinates only.",
            "org.example:corelib:1.2", "org.example:container:2.0", "org.example:starter:1.2",
        ]
        self.artifacts = []
        self.jars = {
            "WEB-INF/lib/corelib-1.2.jar": self.jar("org.example", "corelib", "1.2"),
            "WEB-INF/lib-provided/container-2.0.jar": self.jar("org.example", "container", "2.0"),
        }
        self.sync_text_inputs()
        self.write_cache("org.example", "corelib", "1.2", self.jars["WEB-INF/lib/corelib-1.2.jar"])
        self.write_cache("org.example", "container", "2.0", self.jars["WEB-INF/lib-provided/container-2.0.jar"])
        self.write_war()

    def jar(self, group, artifact, version, *, metadata=True, extras=(), manifest=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest or "Manifest-Version: 1.0\r\n")
            archive.writestr("example/Fixture.class", b"Synthetic fixture; never executed.")
            if metadata:
                archive.writestr(
                    f"META-INF/maven/{group}/{artifact}/pom.properties",
                    f"groupId={group}\nartifactId={artifact}\nversion={version}\n",
                )
            for name, value in extras:
                archive.writestr(name, value)
        return stream.getvalue()

    def write_cache(self, group, artifact, version, data, *, classifier=""):
        name = f"{artifact}-{version}{'-' + classifier if classifier else ''}.jar"
        path = self.cache / group.replace(".", "/") / artifact / version / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def sync_text_inputs(self):
        self.inventory.write_text("\n".join(self.inventory_lines) + "\n", encoding="utf-8")
        self.approved.write_text("\n".join(self.approved_lines) + "\n", encoding="utf-8")
        self.policy.write_text(json.dumps({"schemaVersion": 1, "artifacts": self.artifacts}) + "\n", encoding="utf-8")

    def write_war(self, *, extra=(), missing=(), embedded_pom=None, raw_manifest=None):
        manifest = raw_manifest or (
            "Manifest-Version: 1.0\r\n"
            "Main-Class: org.springframework.boot.loader.launch.WarLauncher\r\n"
            "Start-Class: com.aicreviewer.AiCodeReviewerApplication\r\n"
            "Spring-Boot-Version: 4.0.8\r\n"
            "Implementation-Version: 0.1.0-SNAPSHOT\r\n"
        )
        entries = {
            "META-INF/MANIFEST.MF": manifest,
            "org/springframework/boot/loader/launch/WarLauncher.class": b"Synthetic launcher fixture.",
            "WEB-INF/classes/com/aicreviewer/AiCodeReviewerApplication.class": b"Synthetic application fixture.",
            "WEB-INF/jsp/login.jsp": b"Synthetic JSP fixture.",
            "META-INF/maven/org.example.app/reviewer-app/pom.xml": self.pom.read_bytes() if embedded_pom is None else embedded_pom,
            **self.jars,
        }
        with zipfile.ZipFile(self.war, "w") as archive:
            for name, value in entries.items():
                if name not in missing:
                    archive.writestr(name, value)
            for name, value in extra:
                archive.writestr(name, value)

    def audit(self, **changes):
        args = dict(war_file=self.war, expected_war_sha256=sha(self.war.read_bytes()),
                    inventory_file=self.inventory, pom_file=self.pom, approved_file=self.approved,
                    supplemental_file=self.policy, maven_repository=self.cache)
        args.update(changes)
        return AUDIT.audit_war(**args)

    def cli(self, **changes):
        values = dict(war_file=self.war, expected_war_sha256=sha(self.war.read_bytes()),
                      inventory_file=self.inventory, pom_file=self.pom, approved_file=self.approved,
                      supplemental_file=self.policy, maven_repository=self.cache)
        values.update(changes)
        args = []
        for key, value in values.items():
            args.extend(["--" + key.replace("_", "-"), str(value)])
        output, error = io.StringIO(), io.StringIO()
        with redirect_stdout(output), redirect_stderr(error):
            code = AUDIT.main(args)
        return code, output.getvalue(), error.getvalue()

    def reject(self, **changes):
        with self.assertRaises(AUDIT.AuditError):
            self.audit(**changes)

    def add_supplement(self, *, present=True):
        data = self.jar("org.example", "packaging-tools", "4.0", metadata=False)
        entry = "WEB-INF/lib/packaging-tools-4.0.jar"
        self.artifacts.append({
            "coordinate": "org.example:packaging-tools:4.0", "entry": entry,
            "size": len(data), "sha256": sha(data),
            "provenance": "https://repo.maven.apache.org/maven2/org/example/packaging-tools/4.0/packaging-tools-4.0.pom",
        })
        self.approved_lines.append("org.example:packaging-tools:4.0")
        if present:
            self.jars[entry] = data
        self.sync_text_inputs()
        self.write_war()
        return entry, data

    def test_complete_coverage_includes_provided_and_retains_inventory_only_dependencies(self):
        result = self.audit()
        self.assertEqual("COMPLETE", result["status"])
        self.assertEqual({("org.example:corelib", "1.2", "compile"),
                          ("org.example:container", "2.0", "provided"),
                          ("org.example:starter", "1.2", "runtime")},
                         {(p["name"], p["version"], p["scope"]) for p in result["packages"]})
        self.assertEqual(2, result["coverage"]["nestedJarCount"])
        self.assertEqual(2, result["coverage"]["ordinaryJarCount"])
        self.assertEqual(0, result["coverage"]["supplementalJarCount"])
        self.assertEqual(1, result["coverage"]["inventoryOnlyCount"])

    def test_report_binds_exact_war_inventory_pom_allowlist_and_policy_bytes(self):
        result = self.audit()
        for field, path in (("warSha256", self.war), ("inventorySha256", self.inventory),
                            ("pomSha256", self.pom), ("allowlistSha256", self.approved),
                            ("supplementalPolicySha256", self.policy)):
            self.assertEqual(sha(path.read_bytes()), result[field])

    def test_inventory_classifier_maps_to_the_exact_classifier_cache_filename(self):
        data = self.jars.pop("WEB-INF/lib/corelib-1.2.jar")
        self.jars["WEB-INF/lib/corelib-1.2-fixture.jar"] = data
        self.inventory_lines[1] = "org.example:corelib:jar:fixture:1.2:compile"
        self.write_cache("org.example", "corelib", "1.2", data, classifier="fixture")
        self.sync_text_inputs()
        self.write_war()
        self.assertEqual(2, self.audit()["coverage"]["ordinaryJarCount"])

    def test_duplicate_identical_inventory_lines_do_not_repeat_osv_coordinates(self):
        self.inventory_lines.append(self.inventory_lines[1])
        self.sync_text_inputs()
        self.assertEqual(3, len(self.audit()["packages"]))

    def test_explicit_reviewed_supplement_adds_only_the_packaged_coordinate(self):
        self.add_supplement()
        result = self.audit()
        self.assertEqual(4, len(result["packages"]))
        self.assertIn({"name": "org.example:packaging-tools", "version": "4.0", "scope": "runtime"}, result["packages"])
        self.assertEqual(1, result["coverage"]["supplementalJarCount"])
        self.assertEqual(2, result["coverage"]["ordinaryJarCount"])

    def test_unused_reviewed_supplement_is_not_queried(self):
        self.add_supplement(present=False)
        result = self.audit()
        self.assertEqual(3, len(result["packages"]))
        self.assertEqual(0, result["coverage"]["supplementalJarCount"])

    def test_missing_pom_properties_requires_and_uses_equal_local_cache_bytes(self):
        data = self.jar("org.example", "corelib", "1.2", metadata=False)
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = data
        self.write_cache("org.example", "corelib", "1.2", data)
        self.write_war()
        self.assertEqual(2, self.audit()["coverage"]["ordinaryJarCount"])

    def test_missing_cache_cannot_be_accepted_from_basename_or_metadata(self):
        path = self.cache / "org/example/corelib/1.2/corelib-1.2.jar"
        path.unlink()
        self.reject()

    def test_mismatching_cache_cannot_be_accepted_from_basename_or_metadata(self):
        path = self.cache / "org/example/corelib/1.2/corelib-1.2.jar"
        path.write_bytes(path.read_bytes() + b"different cached bytes")
        self.reject()

    def test_matching_cache_does_not_override_wrong_nested_maven_coordinates(self):
        data = self.jar("private.company", "secret-library", "hidden-version")
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = data
        self.write_cache("org.example", "corelib", "1.2", data)
        self.write_war()
        self.reject()

    def test_conflicting_duplicate_nested_properties_are_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            data = self.jar("org.example", "corelib", "1.2", extras=[
                ("META-INF/maven/org.example/corelib/pom.properties", "groupId=org.example\nartifactId=corelib\nversion=9.9\n")])
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = data
        self.write_cache("org.example", "corelib", "1.2", data)
        self.write_war()
        self.reject()

    def test_matching_expected_metadata_does_not_hide_additional_unreviewed_metadata(self):
        data = self.jar("org.example", "corelib", "1.2", extras=[
            ("META-INF/maven/private.company/hidden/pom.properties",
             "groupId=private.company\nartifactId=hidden\nversion=private-version\n")])
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = data
        self.write_cache("org.example", "corelib", "1.2", data)
        self.write_war()
        self.reject()

    def test_ambiguous_same_basename_from_two_groups_is_rejected(self):
        self.inventory_lines.append("other.public:corelib:jar:1.2:compile")
        self.approved_lines.append("other.public:corelib:1.2")
        self.sync_text_inputs()
        self.reject()

    def test_unapproved_inventory_coordinate_or_version_is_rejected(self):
        for line in ("private.company:confidential:jar:1.0:compile", "org.example:corelib:jar:internal:compile"):
            with self.subTest(line_kind=line.split(":")[0]):
                self.inventory_lines.append(line)
                self.sync_text_inputs()
                self.reject()
                self.inventory_lines.pop()

    def test_empty_malformed_and_unsupported_inventory_fail_closed(self):
        for text in ("", "The following files have been resolved:\n", "malformed\n",
                     "org.example:corelib:jar:1.2:system\n"):
            with self.subTest(kind=text[:12]):
                self.inventory.write_text(text, encoding="utf-8")
                self.reject()

    def test_test_only_inventory_cannot_be_an_empty_success(self):
        self.inventory.write_text("private.test:fixture:jar:secret-version:test\n", encoding="utf-8")
        self.reject()

    def test_unlisted_packaged_jar_is_not_auto_approved_from_its_metadata(self):
        self.jars["WEB-INF/lib/secret-library-1.0.jar"] = self.jar("private.company", "secret-library", "1.0")
        self.write_war()
        self.reject()

    def test_duplicate_basename_across_normal_and_provided_libraries_is_rejected(self):
        self.jars["WEB-INF/lib-provided/corelib-1.2.jar"] = self.jars["WEB-INF/lib/corelib-1.2.jar"]
        self.write_war()
        self.reject()

    def test_empty_library_set_is_not_a_successful_coverage_check(self):
        self.jars.clear()
        self.write_war()
        self.reject()

    def test_library_roots_allow_only_flat_regular_jar_files(self):
        for name in ("WEB-INF/lib/nested/corelib-1.2.jar", "WEB-INF/lib/untracked.txt",
                     "WEB-INF/lib-provided/subdir/container-2.0.jar"):
            with self.subTest(kind=name):
                self.write_war(extra=[(name, b"unexpected library root payload")])
                self.reject()

    def test_wrong_expected_hash_and_invalid_hash_claims_fail_closed(self):
        for expected in ("0" * 64, "not-a-hash", "a" * 63):
            with self.subTest(kind=len(expected)):
                self.reject(expected_war_sha256=expected)

    def test_embedded_pom_is_mandatory_and_must_match_exact_source_bytes(self):
        self.write_war(missing=["META-INF/maven/org.example.app/reviewer-app/pom.xml"])
        self.reject()
        self.write_war(embedded_pom=self.pom.read_bytes() + b"<!-- different build -->")
        self.reject()

    def test_parent_coordinates_cannot_substitute_for_application_pom_coordinates(self):
        self.pom.write_bytes(
            b'<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            b'<parent><groupId>org.other.parent</groupId><artifactId>parent</artifactId><version>4.0.8</version></parent>'
            b'<groupId>org.example.app</groupId><artifactId>reviewer-app</artifactId><version>0.1.0-SNAPSHOT</version></project>'
        )
        self.write_war()
        self.assertEqual("COMPLETE", self.audit()["status"])

    def test_malformed_and_entity_declaration_poms_are_rejected(self):
        for data in (b"not XML", b'<!DOCTYPE project [<!ENTITY x "org.example.app">]><project><groupId>&x;</groupId><artifactId>reviewer-app</artifactId></project>'):
            with self.subTest(kind=data[:12]):
                self.pom.write_bytes(data)
                self.write_war()
                self.reject()

    def test_utf16_pom_cannot_bypass_doctype_and_entity_rejection(self):
        document = (
            '<?xml version="1.0" encoding="UTF-16"?>'
            '<!DOCTYPE project [<!ENTITY fixture_group "org.example.app">]>'
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion><groupId>&fixture_group;</groupId>'
            '<artifactId>reviewer-app</artifactId><version>0.1.0-SNAPSHOT</version></project>'
        )
        cases = {
            "little-endian-bom": b"\xff\xfe" + document.encode("utf-16-le"),
            "big-endian-bom": b"\xfe\xff" + document.encode("utf-16-be"),
            "little-endian-no-bom": document.encode("utf-16-le"),
            "big-endian-no-bom": document.encode("utf-16-be"),
        }
        for name, data in cases.items():
            with self.subTest(encoding=name):
                self.pom.write_bytes(data)
                self.write_war()
                self.reject()

    def test_pom_with_nul_bytes_is_rejected(self):
        self.pom.write_bytes(self.pom.read_bytes().replace(b"org.example.app", b"org.example\x00.app"))
        self.write_war()
        self.reject()

    def test_utf8_bom_pom_is_supported_with_exact_embedded_bytes(self):
        self.pom.write_bytes(b"\xef\xbb\xbf" + self.pom.read_bytes())
        self.write_war()
        self.assertEqual("COMPLETE", self.audit()["status"])

    def test_manifest_project_contract_remains_required(self):
        self.write_war(raw_manifest="Manifest-Version: 1.0\r\nMain-Class: unexpected.Launcher\r\n")
        self.reject()

    def test_supplement_requires_exact_approved_coordinate(self):
        self.add_supplement()
        self.approved_lines.remove("org.example:packaging-tools:4.0")
        self.sync_text_inputs()
        self.reject()

    def test_supplement_exact_entry_size_and_hash_are_all_required(self):
        self.add_supplement()
        original = dict(self.artifacts[0])
        for field, value in (("entry", "WEB-INF/lib-provided/packaging-tools-4.0.jar"),
                             ("size", original["size"] + 1), ("sha256", "0" * 64)):
            with self.subTest(field=field):
                self.artifacts[0] = {**original, field: value}
                self.sync_text_inputs()
                self.reject()

    def test_supplement_duplicate_or_ambiguous_policy_entries_are_rejected(self):
        self.add_supplement()
        self.artifacts.append(dict(self.artifacts[0]))
        self.sync_text_inputs()
        self.reject()

    def test_duplicate_policy_json_keys_are_rejected_instead_of_last_value_winning(self):
        self.policy.write_text('{"schemaVersion": 2, "schemaVersion": 1, "artifacts": []}', encoding="utf-8")
        self.reject()

    def test_supplement_hash_does_not_override_conflicting_nested_coordinates(self):
        entry, _ = self.add_supplement()
        data = self.jar("private.company", "conflicting-library", "private-version")
        self.jars[entry] = data
        self.artifacts[0]["size"] = len(data)
        self.artifacts[0]["sha256"] = sha(data)
        self.sync_text_inputs()
        self.write_war()
        self.reject()

    def test_supplement_policy_schema_and_public_provenance_are_validated(self):
        self.add_supplement()
        original = dict(self.artifacts[0])
        for value in ({"schemaVersion": 2, "artifacts": self.artifacts},
                      {"schemaVersion": 1, "artifacts": "not-a-list"},
                      {"schemaVersion": 1, "artifacts": [{**original, "size": True}]},
                      {"schemaVersion": 1, "artifacts": [{**original, "provenance": "http://private.invalid/source"}]},
                      {"schemaVersion": 1, "artifacts": [{**original, "provenance": "https://user:password@public.invalid/pom"}]}):
            with self.subTest(kind=str(value)[:45]):
                self.policy.write_text(json.dumps(value), encoding="utf-8")
                self.reject()

    def test_other_public_gav_pom_url_cannot_be_used_as_supplement_provenance(self):
        self.add_supplement()
        self.artifacts[0]["provenance"] = (
            "https://repo.maven.apache.org/maven2/org/other/other-artifact/9.9/other-artifact-9.9.pom"
        )
        self.sync_text_inputs()
        self.reject()

    def test_supplement_does_not_override_an_ordinary_dependency_cache_mismatch(self):
        entry = "WEB-INF/lib/corelib-1.2.jar"
        data = self.jars[entry]
        self.artifacts = [{"coordinate": "org.example:corelib:1.2", "entry": entry,
                           "size": len(data), "sha256": sha(data),
                           "provenance": "https://repo.maven.apache.org/maven2/org/example/corelib/1.2/corelib-1.2.pom"}]
        self.sync_text_inputs()
        self.write_cache("org.example", "corelib", "1.2", b"different cached bytes")
        self.reject()

    def test_unsafe_duplicate_absolute_traversal_and_link_war_entries_are_rejected(self):
        link = zipfile.ZipInfo("linked-entry")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for name in ("WEB-INF/lib/corelib-1.2.jar", "../outside", "/outside", "C:/outside", "a/../../outside", link):
            with self.subTest(kind=str(name)):
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    self.write_war(extra=[(name, b"unexpected fixture")])
                self.reject()

    def test_malformed_nested_jar_is_rejected_even_when_equal_to_local_cache(self):
        data = b"not a zip archive"
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = data
        self.write_cache("org.example", "corelib", "1.2", data)
        self.write_war()
        self.reject()

    def test_forged_small_nested_properties_cannot_hide_large_inflation(self):
        prefix = b"groupId=org.example\nartifactId=corelib\nversion=1.2\n"
        stream = io.BytesIO()
        meta = "META-INF/maven/org.example/corelib/pom.properties"
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr(meta, prefix + b"#" * (1024 * 1024), compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("example/Fixture.class", b"fixture")
        data = bytearray(stream.getvalue())
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            info = archive.getinfo(meta)
        central = data.index(b"PK\x01\x02")
        for offset in (info.header_offset + 22, central + 24):
            struct.pack_into("<I", data, offset, len(prefix))
        for offset in (info.header_offset + 14, central + 16):
            struct.pack_into("<I", data, offset, zlib.crc32(prefix))
        self.jars["WEB-INF/lib/corelib-1.2.jar"] = bytes(data)
        self.write_cache("org.example", "corelib", "1.2", bytes(data))
        self.write_war()
        self.reject()

    def test_relative_inputs_and_parent_traversal_are_rejected(self):
        for change in (dict(war_file=Path("relative.war")), dict(maven_repository=Path("relative-cache")),
                       dict(inventory_file=self.directory / "child" / ".." / self.inventory.name)):
            with self.subTest(field=next(iter(change))):
                self.reject(**change)

    def test_linked_input_or_cache_parent_is_rejected(self):
        link = self.directory / "linked-cache"
        try:
            link.symlink_to(self.cache, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("Creating filesystem symlinks is unavailable on this host")
        self.reject(maven_repository=link)

    def test_success_cli_json_and_exclusive_report_match_without_network(self):
        code, output, error = self.cli(report_file=self.report)
        self.assertEqual(0, code)
        self.assertEqual("", error)
        result = json.loads(output)
        saved = json.loads(self.report.read_text(encoding="utf-8"))
        self.assertEqual(result, saved)
        self.assertEqual("COMPLETE", result["status"])
        self.assertEqual(sha(self.war.read_bytes()), result["warSha256"])

    def test_existing_report_is_preserved_and_cannot_be_reported_successful(self):
        self.report.write_bytes(b"preserve previous audit\n")
        code, output, error = self.cli(report_file=self.report)
        self.assertEqual(1, code)
        self.assertEqual(b"preserve previous audit\n", self.report.read_bytes())
        self.assertNotIn('"status": "COMPLETE"', output)
        self.assertNotIn("Traceback", output + error)

    def test_cli_failure_never_prints_private_coordinate_paths_or_exception_values(self):
        marker = "private-marker-do-not-print"
        self.inventory.write_text(f"private.company:{marker}:jar:hidden-version:compile\n", encoding="utf-8")
        code, output, error = self.cli(report_file=self.report)
        self.assertEqual(1, code)
        self.assertEqual("INCOMPLETE", json.loads(output)["status"])
        for sensitive in (marker, "private.company", "hidden-version", str(self.directory), "Traceback"):
            self.assertNotIn(sensitive, output + error)
        if self.report.exists():
            for sensitive in (marker, "private.company", "hidden-version", str(self.directory)):
                self.assertNotIn(sensitive, self.report.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
