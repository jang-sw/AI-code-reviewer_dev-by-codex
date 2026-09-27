#!/usr/bin/env python3
"""Opt-in packaged WAR drill; stops only the parent-owned .local/pg-validation cluster.

Run through scripts/test-postgres.ps1, which supplies its current postmaster PID.
Uses synthetic loopback GitLab/Ollama and an invocation-owned PostgreSQL schema.
No external Git/AI, production database, API credential or repository clone is used.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid


SPEC = importlib.util.spec_from_file_location("db_recovery_helpers", Path(__file__).with_name("verify-review-restart.py"))
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)
VerificationError, require = HELPERS.VerificationError, HELPERS.require
WORKSPACE = Path(__file__).absolute().parent.parent
MARKER = "Isolated local AI Reviewer tests only."


def checked_path(path, directory=False):
    path = Path(path).absolute()
    require(".." not in path.parts and not str(path).startswith(("\\\\", "//")), "Invalid isolated cluster path")
    for item in (*reversed(path.parents), path):
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not (getattr(info, "st_file_attributes", 0) & 0x400),
                "Links or reparse points are not allowed for isolated cluster control")
    require(path.is_dir() if directory else path.is_file(), "Missing isolated cluster control file")
    return path


def limited_text(path):
    checked_path(path)
    with path.open("rb") as source:
        data = source.read(16385)
    require(len(data) <= 16384, "Isolated cluster control file exceeds its limit")
    try:
        return data.decode("utf-8-sig")
    except UnicodeError:
        raise VerificationError("Invalid isolated cluster control file encoding") from None


class ParentOwnedCluster:
    """A PID is necessary but never sufficient: disk and connected-server identity must agree."""
    def __init__(self, args, database, logs):
        expected = checked_path(WORKSPACE / ".local" / "pg-validation", directory=True)
        self.path = checked_path(args.cluster_path, directory=True)
        require(self.path == expected, "Only the repository's isolated pg-validation cluster may be controlled")
        require(type(args.expected_postmaster_pid) is int and 0 < args.expected_postmaster_pid <= 2147483647,
                "An explicit parent-owned postmaster PID is required")
        require(type(args.expected_postmaster_started_at) is int and 0 < args.expected_postmaster_started_at <= 9223372036854775807,
                "An explicit parent-owned postmaster start time is required")
        self.database, self.pg_ctl, self.logs = database, args.pg_ctl, logs
        self.port = int(HELPERS.DB_PATTERN.fullmatch(database.url).group(2))
        self.pid = args.expected_postmaster_pid
        self.started_at = args.expected_postmaster_started_at
        self.path_identity = self.identity(self.path)
        self.marker_identity = self.identity(self.path / "reviewer-test-cluster")
        self.stop_attempted = False
        self.restart_started = False
        self.environment = HELPERS.base_environment()
        self.verify_running(self.pid)

    @staticmethod
    def identity(path):
        value = checked_path(path, directory=path.is_dir()).stat()
        return value.st_dev, value.st_ino

    def verify_directory(self):
        require(checked_path(self.path, directory=True) == WORKSPACE / ".local" / "pg-validation"
                and self.identity(self.path) == self.path_identity,
                "The parent-owned cluster directory was replaced")
        marker = self.path / "reviewer-test-cluster"
        require(self.identity(marker) == self.marker_identity and limited_text(marker).strip() == MARKER
                and limited_text(self.path / "PG_VERSION").strip() == "17",
                "The isolated PostgreSQL marker or version is invalid")

    def pid_record(self):
        self.verify_directory()
        lines = limited_text(self.path / "postmaster.pid").splitlines()
        require(len(lines) >= 6 and lines[0].isdigit() and lines[2].isdigit() and lines[3].isdigit(),
                "Invalid isolated PostgreSQL PID file")
        require(0 < int(lines[0]) <= 2147483647 and 0 < int(lines[2]) <= 9223372036854775807,
                "Invalid isolated PostgreSQL process identity bounds")
        require(Path(lines[1]).absolute() == self.path and int(lines[3]) == self.port and lines[5] == "127.0.0.1",
                "Postmaster PID file does not identify the expected loopback cluster")
        return int(lines[0]), int(lines[2])

    def verify_running(self, expected_pid):
        pid, started = self.pid_record()
        require(pid == expected_pid, "The parent-owned PostgreSQL PID changed unexpectedly")
        # Superuser access is confined to the disposable test cluster. Do not emit these values.
        raw = self.database.sql("SELECT json_build_object('directory',current_setting('data_directory'),"
            "'port',current_setting('port'),'listen',current_setting('listen_addresses'),"
            "'pid',split_part(pg_read_file('postmaster.pid',0,16384),chr(10),1),"
            "'started',extract(epoch FROM pg_postmaster_start_time())::bigint);")
        try:
            server = json.loads(raw)
            matched = (Path(server["directory"]).absolute() == self.path and int(server["port"]) == self.port
                       and server["listen"] == "127.0.0.1" and int(server["pid"]) == pid
                       and abs(int(server["started"]) - started) <= 1)
        except (ValueError, TypeError, KeyError):
            matched = False
        require(matched, "Connected PostgreSQL does not match the parent-owned cluster")
        if self.started_at is not None:
            require(started == self.started_at, "The parent-owned PostgreSQL instance was replaced")
        self.started_at = started

    def control(self, operation):
        command = [str(self.pg_ctl), "-D", str(self.path), "-w", "-t", "20"]
        if operation == "stop":
            command += ["-m", "fast", "stop"]
        else:
            require(operation == "start", "Invalid isolated cluster operation")
            command += ["-l", str(self.logs / "postgres-recovery.log"), "-o", "-p " + str(self.port) + " -h 127.0.0.1", "start"]
        try:
            # Never PIPE pg_ctl: the postmaster could inherit a pipe and prevent communicate() returning.
            result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, env=self.environment, timeout=25, creationflags=HELPERS.CREATE_FLAGS, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise VerificationError("Isolated PostgreSQL lifecycle command failed or timed out") from None
        require(result.returncode == 0, "Isolated PostgreSQL lifecycle command failed")

    def stop(self):
        self.verify_running(self.pid)
        self.stop_attempted = True
        self.control("stop")
        self.verify_directory()
        require(not (self.path / "postmaster.pid").exists(), "The owned PostgreSQL did not fully stop")
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(1)
            require(probe.connect_ex(("127.0.0.1", self.port)) != 0, "The isolated PostgreSQL port remains occupied")

    def restore(self):
        if not self.stop_attempted:
            return
        self.verify_directory()
        pid_file = self.path / "postmaster.pid"
        if pid_file.exists():
            # A failed stop may leave the original server alive. Never adopt an unrelated replacement.
            self.verify_running(self.pid)
            return
        require(not self.restart_started, "The owned PostgreSQL restart did not leave a verifiable server")
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(("127.0.0.1", self.port))
            except OSError:
                raise VerificationError("Refusing restart because the isolated PostgreSQL port is occupied") from None
        self.restart_started = True
        # An unsuccessful start cannot prove who created a new PID. Preserve evidence and fail cleanup
        # rather than adopting a concurrently started or otherwise ambiguous postmaster.
        self.control("start")
        self.pid, self.started_at = self.pid_record()
        self.verify_running(self.pid)


def probe_health(base, group, status, body):
    require(group in ("liveness", "readiness"), "Unexpected public health probe")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), HELPERS.LocalRedirects(base))
    try:
        response = opener.open(base + "/actuator/health/" + group, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    except (urllib.error.URLError, TimeoutError):
        raise VerificationError("The packaged public health probe failed") from None
    with response:
        payload = response.read(10001)
        require(len(payload) <= 10000 and response.code == status, "Unexpected packaged health status or response size")
        try:
            parsed = json.loads(payload)
        except (ValueError, UnicodeError):
            raise VerificationError("The packaged health probe did not return bounded JSON") from None
        require(parsed == {"status": body}, "The public health probe exposed details or returned an unexpected state")


def worker_failed(log, project):
    require(type(project) is int and project > 0, "Invalid generated fixture project")
    with log.open("rb") as source:
        data = source.read(2_000_001)
    require(len(data) <= 2_000_000, "The owned WAR log exceeded the fixture inspection limit")
    # Fixed production diagnostic, not arbitrary exception text. Never copy the log into the report.
    return ("Project " + str(project) + " review execution failed: ").encode("ascii") in data


def cleanup_owned(war, fixture, cluster, database, schema, owned_schema):
    failed = False
    fixture.release_first_b.set()
    fixture.release_second_b.set()
    if war is not None:
        try:
            war.stop(force=True)
        except Exception:
            failed = True
    try:
        fixture.close()
    except Exception:
        failed = True
    restored = cluster is None or not cluster.stop_attempted
    if cluster is not None:
        try:
            cluster.restore()
            restored = True
        except Exception:
            failed = True
    alive = war is not None and war.process is not None and war.process.poll() is None
    if owned_schema and not alive and restored:
        try:
            require(HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None, "Refusing unexpected schema cleanup")
            database.sql("DROP SCHEMA " + schema + " CASCADE;")
            owned_schema = False
        except Exception:
            failed = True
    return owned_schema, restored, failed or alive


def parent_handoff(cluster):
    """Only positively verified server identity can replace the parent's original PID ownership."""
    if cluster is None:
        return None
    try:
        cluster.verify_running(cluster.pid)
        return {"pid": cluster.pid, "startedAt": cluster.started_at}
    except Exception:
        # Do not let a report turn a refused replacement PID or ambiguous start into authority to stop it.
        return None


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("war", "java", "psql", "pg-ctl", "cluster-path"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--expected-postmaster-pid", type=int, required=True)
    parser.add_argument("--expected-postmaster-started-at", type=int, required=True)
    parser.add_argument("--parent-run-token", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=240)
    parser.add_argument("--startup-timeout-seconds", type=int, default=45)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    for name in ("war", "java", "psql", "pg_ctl"):
        value = getattr(args, name).resolve(strict=True) if name == "war" else getattr(args, name).absolute()
        require(value.is_file(), "An explicitly configured executable or WAR is missing")
        setattr(args, name, value)
    args.cluster_path = args.cluster_path.absolute()
    require(args.war.suffix.lower() == ".war" and 1024 <= args.port <= 65535
            and 90 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120
            and 0 < args.expected_postmaster_pid <= 2147483647 and 0 < args.expected_postmaster_started_at <= 9223372036854775807
            and re.fullmatch(r"[0-9a-f]{32}", args.parent_run_token) is not None,
            "Invalid DB recovery drill arguments")
    args.report = (args.report or WORKSPACE / ".local" / ("review-db-recovery-" + args.parent_run_token + ".json")).absolute()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", args.port))
        except OSError:
            raise VerificationError("The requested loopback WAR port is occupied") from None
    return args


def main():
    args = arguments()
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = "restart_test_" + uuid.uuid4().hex
    args.report.parent.mkdir(parents=True, exist_ok=True)
    logs = args.report.parent / schema
    logs.mkdir()
    work = Path(tempfile.mkdtemp(prefix="owned-work-", dir=logs)).resolve()
    war, cluster, database, fixture, owned_schema = None, None, None, HELPERS.Fixture(), False
    report = {"result": "FAIL", "stage": "setup", "externalServicesUsed": False, "paidAiUsed": False,
              "scope": "one WAR, parent-owned isolated PostgreSQL restart, synthetic GitLab/Ollama", "checks": {},
              "schema": schema, "logDirectory": str(logs), "parentRunToken": args.parent_run_token}
    failure = None
    try:
        database = HELPERS.Database(args.psql, os.environ.get("TEST_DATABASE_URL", ""), work)
        cluster = ParentOwnedCluster(args, database, logs)
        require(args.port != cluster.port, "The WAR and isolated PostgreSQL ports must differ")
        database.sql("CREATE SCHEMA " + schema + ";")
        owned_schema = True
        fixture_url = fixture.start()
        war = HELPERS.OwnedWar(args, database, schema, fixture_url, work, logs, deadline)
        war.start(True)
        browser = HELPERS.Browser(war.base)
        browser.login(war.username, war.password)
        _, page = browser.request("/projects")
        location, page = browser.request("/projects", {"_csrf": HELPERS.csrf(page), "name": "Synthetic DB recovery",
            "repositoryUrl": fixture_url + "/fixture/restart", "reviewBranch": ""})
        match = re.fullmatch(re.escape("/" + schema + "/projects/") + r"([0-9]+)", HELPERS.urllib.parse.urlsplit(location).path)
        require(match is not None, "Synthetic project registration returned an unexpected location")
        project = int(match.group(1))
        _, page = browser.request("/admin/projects/" + str(project) + "/approve", {"_csrf": HELPERS.csrf(page)})
        browser.request("/projects/" + str(project) + "/review", {"_csrf": HELPERS.csrf(page)})
        HELPERS.bounded_wait(fixture.first_b_entered.is_set, deadline, "Synthetic B did not reach its first latch", war.process)
        initial = database.snapshot(schema, project)
        require(initial["request"]["state"] == "RUNNING" and initial["request"]["attempts"] == 1
                and initial["commits"] == [HELPERS.SHA_A] and initial["issues"] == 1 and initial["cursor"] is None
                and initial["progress"]["saved"] == 1 and initial["progress"]["lastSavedAt"] is not None,
                "Initial atomic commit progress did not match the DB outage scenario")
        probe_health(war.base, "liveness", 200, "UP")
        probe_health(war.base, "readiness", 200, "UP")
        report["stage"] = "stop_owned_database_during_review"
        cluster.stop()
        report["checks"]["ownedDatabaseStopped"] = True
        probe_health(war.base, "liveness", 200, "UP")
        probe_health(war.base, "readiness", 503, "DOWN")
        report["checks"]["databaseOutageReadiness503Liveness200StatusOnly"] = True
        fixture.release_first_b.set()
        HELPERS.bounded_wait(lambda: worker_failed(logs / "war-start-1.log", project), deadline,
                             "The first worker did not finish with a database failure while PostgreSQL was stopped", war.process)
        require(not (cluster.path / "postmaster.pid").exists(), "PostgreSQL unexpectedly restarted before the failed worker observation")
        report["checks"]["workerFailedBeforeDatabaseRestart"] = True
        report["stage"] = "restore_database_and_recover_same_request"
        cluster.restore()
        probe_health(war.base, "liveness", 200, "UP")
        HELPERS.bounded_wait(fixture.second_b_entered.is_set, deadline, "The pending request did not retry B after DB restart", war.process)
        recovering = database.snapshot(schema, project)
        require(recovering["request"]["requestId"] == initial["request"]["requestId"]
                and recovering["request"]["actor"] == initial["request"]["actor"]
                and recovering["request"]["source"] == "MANUAL" and recovering["request"]["state"] == "RUNNING"
                and recovering["request"]["attempts"] == 2 and recovering["commits"] == [HELPERS.SHA_A]
                and recovering["issues"] == 1 and recovering["cursor"] is None
                and recovering["progress"]["runId"] != initial["progress"]["runId"]
                and recovering["progress"]["stage"] == "REVIEWING" and recovering["progress"]["saved"] == 0
                and recovering["progress"]["lastSavedAt"] is None,
                "DB recovery lost request ownership, reused prior attempt counts or duplicated stored work")
        probe_health(war.base, "readiness", 200, "UP")
        browser = HELPERS.Browser(war.base)
        browser.login(war.username, war.password)
        for route in ("/projects/" + str(project), "/reviews?projectId=" + str(project)):
            _, page = browser.request(route)
            HELPERS.verify_progress_page(page, "REVIEWING", 0)
        fixture.release_second_b.set()
        final = None

        def complete():
            nonlocal final
            final = database.snapshot(schema, project)
            require(final["request"]["state"] not in ("FAILED", "CANCELLED"), "Recovered request became terminal without succeeding")
            return final["request"]["state"] == "SUCCEEDED"

        HELPERS.bounded_wait(complete, deadline, "The DB-recovered request did not complete", war.process)
        require(final["request"]["requestId"] == initial["request"]["requestId"]
                and final["request"]["actor"] == initial["request"]["actor"] and final["request"]["attempts"] == 2
                and final["commits"] == [HELPERS.SHA_A, HELPERS.SHA_B] and final["issues"] == 2
                and final["assignees"] == [initial["request"]["actor"]] * 2 and final["cursor"] == HELPERS.SHA_B
                and final["runs"] == [{"state": "FAILED", "commits": 1}, {"state": "SUCCEEDED", "commits": 1}]
                and final["progress"]["runId"] == recovering["progress"]["runId"]
                and final["progress"]["stage"] == "FINALIZING" and final["progress"]["saved"] == 1
                and final["progress"]["lastSavedAt"] is not None,
                "DB recovery duplicated or lost commits, issues, safe checkpoint or attempt progress")
        counts = fixture.observed()
        require(all(counts.get(kind + "_A.java") == 1 and counts.get(kind + "_B.java") == 2
                    for kind in ("ai", "detail", "diff")) and not fixture.errors,
                "DB recovery repeated saved commit content or did not retry only unfinished work")
        require(war.starts == 1 and war.process.poll() is None, "The WAR was restarted instead of recovering its live DB connection")
        report["checks"].update({"sameLiveWarRecovered": True, "requestAndActorPreserved": True,
            "persistedAReusedAndUnfinishedBRetried": True, "exactlyTwoIssuesAndSafeCheckpoint": True,
            "currentAttemptProgressAndJspCorrect": True, "readinessReturnedTo200": True})
        report["requestCounts"], report["attempts"] = counts, final["request"]["attempts"]
        report["runStates"] = [run["state"] for run in final["runs"]]
        report["result"], report["stage"] = "PASS", "complete"
    except (Exception, KeyboardInterrupt) as error:
        failure = error
        report["failureType"] = type(error).__name__
        if isinstance(error, VerificationError):
            report["failure"] = str(error)
    finally:
        owned_schema, restored, cleanup_failed = cleanup_owned(war, fixture, cluster, database, schema, owned_schema)
        alive = war is not None and war.process is not None and war.process.poll() is None
        if not alive:
            try:
                require(work.parent == logs.resolve() and work.name.startswith("owned-work-") and not work.is_symlink(),
                        "Refusing unexpected owned work cleanup target")
                shutil.rmtree(work)
            except Exception:
                cleanup_failed = True
        report["checks"].update({"ownedWarStopped": not alive, "databaseRestoredForParent": restored,
                                "ownedSchemaDropped": not owned_schema})
        if cleanup_failed:
            failure = VerificationError("Owned resource cleanup did not complete")
            report["result"], report["failureType"] = "FAIL", "CleanupFailure"
        report["ownedSchemaCleanupPending"] = owned_schema
        report["elapsedMillis"] = round((time.monotonic() - started) * 1000)
        report["warStarts"] = war.starts if war is not None else 0
        handoff = parent_handoff(cluster) if restored else None
        if handoff is not None:
            report["parentOwnedPostmaster"] = handoff
        elif cluster is not None and cluster.stop_attempted and not cleanup_failed:
            failure = VerificationError("PostgreSQL ownership could not be handed back to the parent")
            report["result"], report["failureType"] = "FAIL", "OwnershipHandoffFailure"
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(report["result"] + ": packaged review DB recovery drill; report=" + str(args.report))
    return 1 if failure is not None else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (VerificationError, OSError):
        print("FAIL: DB recovery drill rejected the supplied local configuration", file=sys.stderr)
        sys.exit(1)
