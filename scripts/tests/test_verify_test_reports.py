import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("verify_test_reports", Path(__file__).resolve().parents[1] / "verify-test-reports.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PostgreSQLReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for suite in MODULE.REQUIRED_SUITES:
            self.write_report(suite)

    def write_report(self, suite, status=None, count="1"):
        attributes = {"name": suite, "tests": count, "skipped": "0", "failures": "0", "errors": "0"}
        root = ET.Element("testsuite", attributes)
        case = ET.SubElement(root, "testcase", {"name": "fixture"})
        if status:
            ET.SubElement(case, status)
            root.set({"skipped": "skipped", "failure": "failures", "error": "errors"}[status], "1")
        ET.ElementTree(root).write(self.directory / f"TEST-{suite}.xml", encoding="utf-8")

    def test_accepts_all_successful_real_database_suites(self):
        MODULE.verify_reports(self.directory)

    def test_missing_suite_fails_instead_of_treating_database_tests_as_optional(self):
        (self.directory / f"TEST-{MODULE.REQUIRED_SUITES[1]}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)

    def test_queue_recovery_suite_is_mandatory(self):
        suite = "com.aicreviewer.review.ReviewRequestPostgresTest"
        self.assertIn(suite, MODULE.REQUIRED_SUITES)
        (self.directory / f"TEST-{suite}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)

    def test_disabled_failed_or_errored_suite_fails(self):
        for status in ("skipped", "failure", "error"):
            with self.subTest(status=status):
                self.write_report(MODULE.REQUIRED_SUITES[0], status)
                with self.assertRaisesRegex(ValueError, "skipped, empty or unsuccessful"):
                    MODULE.verify_reports(self.directory)

    def test_telemetry_snapshot_suite_is_mandatory(self):
        suite = "com.aicreviewer.operations.OperationsTelemetryPostgresTest"
        self.assertIn(suite, MODULE.REQUIRED_SUITES)
        (self.directory / f"TEST-{suite}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)

    def test_shared_auth_attempt_suite_is_mandatory_and_must_pass(self):
        suite = "com.aicreviewer.identity.SharedAttemptStorePostgresTest"
        self.assertIn(suite, MODULE.REQUIRED_SUITES)
        (self.directory / f"TEST-{suite}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)
        for status in ("skipped", "failure", "error"):
            with self.subTest(status=status):
                self.write_report(suite, status)
                with self.assertRaisesRegex(ValueError, "skipped, empty or unsuccessful"):
                    MODULE.verify_reports(self.directory)

    def test_zero_or_invalid_test_count_fails(self):
        for count in ("0", "-1", "2", "invalid"):
            with self.subTest(count=count):
                self.write_report(MODULE.REQUIRED_SUITES[0], count=count)
                with self.assertRaises(ValueError):
                    MODULE.verify_reports(self.directory)

    def test_shared_integration_cooldown_suite_is_mandatory(self):
        suite = "com.aicreviewer.git.SharedIntegrationCooldownPostgresTest"
        self.assertIn(suite, MODULE.REQUIRED_SUITES)
        (self.directory / f"TEST-{suite}.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)

    def test_malformed_report_fails(self):
        (self.directory / f"TEST-{MODULE.REQUIRED_SUITES[0]}.xml").write_text("<broken", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Missing or invalid"):
            MODULE.verify_reports(self.directory)

    def test_correction_suites_are_mandatory_and_must_run_successfully(self):
        for suite in ("com.aicreviewer.identity.UserGitUsernamePostgresTest",
                      "com.aicreviewer.identity.AccountMutationPostgresTest",
                      "com.aicreviewer.project.BranchCorrectionPostgresTest"):
            with self.subTest(suite=suite):
                self.assertIn(suite, MODULE.REQUIRED_SUITES)
                (self.directory / f"TEST-{suite}.xml").unlink()
                with self.assertRaisesRegex(ValueError, "Missing or invalid"):
                    MODULE.verify_reports(self.directory)
                for status in ("skipped", "failure", "error"):
                    self.write_report(suite, status)
                    with self.assertRaisesRegex(ValueError, "skipped, empty or unsuccessful"):
                        MODULE.verify_reports(self.directory)
                self.write_report(suite)
                MODULE.verify_reports(self.directory)

    def test_skipped_case_cannot_hide_behind_zero_skip_counter(self):
        self.write_report(MODULE.REQUIRED_SUITES[0], "skipped")
        report = self.directory / f"TEST-{MODULE.REQUIRED_SUITES[0]}.xml"
        document = ET.parse(report)
        document.getroot().set("skipped", "0")
        document.write(report, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "skipped, empty or unsuccessful"):
            MODULE.verify_reports(self.directory)


if __name__ == "__main__":
    unittest.main()
