"""Fail CI when any required real PostgreSQL test suite did not run successfully."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


REQUIRED_SUITES = (
    "com.aicreviewer.ApplicationPostgresTest",
    "com.aicreviewer.identity.IdentityPostgresTest",
    "com.aicreviewer.identity.SharedAttemptStorePostgresTest",
    "com.aicreviewer.review.ReviewRequestPostgresTest",
    "com.aicreviewer.operations.OperationsTelemetryPostgresTest",
)


def verify_reports(directory: Path) -> None:
    for suite in REQUIRED_SUITES:
        report = directory / f"TEST-{suite}.xml"
        try:
            root = ET.parse(report).getroot()
            counts = {key: int(root.attrib[key]) for key in ("tests", "skipped", "failures", "errors")}
        except (OSError, ET.ParseError, KeyError, ValueError) as error:
            raise ValueError(f"Missing or invalid required PostgreSQL report: {suite}") from error
        cases = root.findall("testcase")
        if (root.tag != "testsuite" or root.get("name") != suite or counts["tests"] <= 0
                or counts["tests"] != len(cases) or any(counts[key] != 0 for key in ("skipped", "failures", "errors"))
                or any(case.find(tag) is not None for case in cases for tag in ("skipped", "failure", "error"))):
            raise ValueError(f"Required PostgreSQL suite was skipped, empty or unsuccessful: {suite}")


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: verify-test-reports.py SUREFIRE_REPORT_DIRECTORY", file=sys.stderr)
        return 2
    try:
        verify_reports(Path(sys.argv[1]))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 1
    print("All required PostgreSQL suites ran with no skips, failures or errors.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
