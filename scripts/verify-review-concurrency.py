#!/usr/bin/env python3
"""Opt-in two-WAR concurrency drill against an isolated local PostgreSQL schema.

Uses only Python's standard library and synthetic loopback GitLab/Ollama routes.
Supply --war, --java, --psql, --port-a and --port-b explicitly. PostgreSQL comes
from TEST_DATABASE_URL, which must name loopback reviewer_integration, with its
credentials in TEST_DATABASE_USERNAME/PASSWORD. No real repository or AI is used.
This bounded scenario does not establish arbitrary fairness or exactly-once paid
API delivery. The existing restart drill separately checks crash recovery.
"""

import argparse
from contextlib import ExitStack
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sys
import tempfile
import threading
import time
import types
import urllib.parse
import uuid


SPEC = importlib.util.spec_from_file_location("review_restart_helpers", Path(__file__).with_name("verify-review-restart.py"))
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)
VerificationError, require = HELPERS.VerificationError, HELPERS.require
PROJECTS = {
    "slow": {"sha": "a" * 40, "file": "Slow.java", "body": b"unsafeSlow();\n"},
    "fast1": {"sha": "b" * 40, "file": "FastOne.java", "body": b"unsafeFastOne();\n"},
    "fast2": {"sha": "c" * 40, "file": "FastTwo.java", "body": b"unsafeFastTwo();\n"},
}


class ConcurrencyFixture(HELPERS.Fixture):
    """Three tiny complete repositories, one AI latch, and exact per-project counters."""
    def __init__(self):
        super().__init__()
        self.slow_entered = threading.Event()
        self.release_slow = threading.Event()
        self.fast_while_slow = set()

    @staticmethod
    def metadata(name):
        item = PROJECTS[name]
        return {"id": item["sha"], "parent_ids": [], "message": "Synthetic concurrency " + name,
                "author_email": "fixture@example.invalid"}

    @staticmethod
    def tree(name):
        item = PROJECTS[name]
        blob = hashlib.sha1(b"blob " + str(len(item["body"])).encode("ascii") + b"\0" + item["body"]).hexdigest()
        return [{"path": item["file"], "id": blob, "type": "blob", "mode": "100644"}]

    def start(self):
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *unused):
                pass

            def reply(self, value, status=200):
                body = json.dumps(value, ensure_ascii=False).encode("utf-8")
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Content-Length", str(len(body)))
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass
                finally:
                    self.close_connection = True

            def check_headers(self):
                require(self.client_address[0] == "127.0.0.1", "Non-loopback fixture client")
                require(not self.headers.get("Authorization") and not self.headers.get("PRIVATE-TOKEN"),
                        "Unexpected credential sent to synthetic fixture")
                require(self.headers.get("Host") == "127.0.0.1:" + str(fixture.server.server_port), "Unexpected fixture Host")

            def do_GET(self):
                try:
                    self.check_headers()
                    url = urllib.parse.urlsplit(self.path)
                    route = urllib.parse.unquote(url.path)
                    query = urllib.parse.parse_qs(url.query, strict_parsing=True)
                    for name, item in PROJECTS.items():
                        base = "/api/v4/projects/fixture/concurrency-" + name + "/repository"
                        sha = item["sha"]
                        if route == base + "/commits":
                            require(query == {"per_page": ["1"]} or query == {
                                "ref_name": [sha], "per_page": ["100"], "page": ["1"], "order": ["topo"]},
                                "Unexpected unpinned or oversized history request")
                            fixture.note(name + ".history")
                            self.reply([fixture.metadata(name)])
                            return
                        if route == base + "/tree":
                            require(query == {"ref": [sha], "recursive": ["true"], "per_page": ["100"], "page": ["1"]},
                                    "Unexpected immutable tree request")
                            fixture.note(name + ".tree")
                            self.reply(fixture.tree(name))
                            return
                        if route == base + "/commits/" + sha:
                            require(query == {"stats": ["true"]}, "Unexpected detail request")
                            fixture.note(name + ".detail")
                            self.reply(dict(fixture.metadata(name), stats={"additions": 1, "deletions": 0, "total": 1}))
                            return
                        if route == base + "/commits/" + sha + "/diff":
                            require(query == {"unidiff": ["true"], "per_page": ["100"], "page": ["1"]}, "Unexpected diff request")
                            fixture.note(name + ".diff")
                            self.reply([{"old_path": item["file"], "new_path": item["file"], "a_mode": "000000", "b_mode": "100644",
                                "new_file": True, "deleted_file": False, "renamed_file": False, "collapsed": False, "too_large": False,
                                "diff": "@@ -0,0 +1 @@\n+" + item["body"].decode("ascii").rstrip("\n")}])
                            return
                    raise VerificationError("Unexpected Git fixture route")
                except Exception as error:
                    fixture.fail(type(error).__name__)
                    self.reply({"error": "Synthetic Git fixture rejected request"}, 400)

            def do_POST(self):
                try:
                    self.check_headers()
                    require(self.path == "/api/chat", "Unexpected AI fixture route")
                    length = int(self.headers.get("Content-Length", "0"))
                    require(0 < length <= 65536, "Unexpected AI payload size")
                    self.connection.settimeout(5)
                    request = json.loads(self.rfile.read(length))
                    require(request.get("model") == HELPERS.MODEL and request.get("stream") is False
                            and isinstance(request.get("format"), dict), "Unexpected AI fixture settings")
                    messages = request.get("messages")
                    require(isinstance(messages, list) and len(messages) == 2 and messages[1].get("role") == "user", "Unexpected AI messages")
                    content = json.loads(messages[1]["content"])
                    name = next((key for key, value in PROJECTS.items() if value["sha"] == content.get("commitSha")), None)
                    require(name is not None, "Unexpected AI commit")
                    item = PROJECTS[name]
                    require(("diff --git a/" + item["file"] + " b/" + item["file"]) in content.get("diff", ""), "Unexpected AI file")
                    require(fixture.note(name + ".ai") == 1, "Duplicate synthetic AI call")
                    if name == "slow":
                        fixture.slow_entered.set()
                        require(fixture.release_slow.wait(120), "The intentional slow response exceeded its hard limit")
                    else:
                        with fixture.lock:
                            if fixture.slow_entered.is_set() and not fixture.release_slow.is_set():
                                fixture.fast_while_slow.add(name)
                    result = {"summary": "합성 동시 실행 검증", "findings": [{"severity": "HIGH", "title": "Synthetic finding " + name,
                        "filePath": item["file"], "lineNumber": 1, "description": "합성 입력 검증입니다.", "suggestion": "합성 변경을 확인하세요."}]}
                    self.reply({"done": True, "done_reason": "stop", "message": {"role": "assistant", "content": json.dumps(result, ensure_ascii=False)}})
                except Exception as error:
                    fixture.fail(type(error).__name__)
                    self.reply({"error": "Synthetic AI fixture rejected request"}, 400)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.server.block_on_close = False
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        self.thread.start()
        return "http://127.0.0.1:" + str(self.server.server_port)

    def close(self):
        self.release_slow.set()
        super().close()


def identifiers(schema, project=None):
    require(isinstance(schema, str) and HELPERS.SCHEMA_PATTERN.fullmatch(schema) is not None,
            "Refusing a schema outside the generated fixture namespace")
    if project is not None:
        require(type(project) is int and project > 0, "Invalid generated project identifier")


def ownership(database, schema, project):
    identifiers(schema, project)
    query = f"""SELECT json_build_object('requestId',request_id,'token',claim_token,'runId',run_id,
        'attempts',attempt_count,'state',state,'due',available_at <= CURRENT_TIMESTAMP)
        FROM {schema}.review_request WHERE project_id={project};"""
    try:
        value = json.loads(database.sql(query))
        require(isinstance(value, dict), "Invalid isolated ownership snapshot")
        return value
    except (ValueError, TypeError):
        raise VerificationError("Invalid isolated ownership snapshot") from None


def force_fixture_due(database, schema, project):
    identifiers(schema, project)
    changed = database.sql(f"""WITH changed AS (
        UPDATE {schema}.review_request SET available_at=TIMESTAMP '2000-01-01 00:00:00'
        WHERE project_id={project} AND state='RUNNING' RETURNING project_id)
        SELECT count(*) FROM changed;""")
    require(changed == "1", "The isolated running request could not be made due for the lease contention check")


def assert_owner_unchanged(original, current):
    require(all(current.get(key) == original.get(key) for key in ("requestId", "token", "runId", "attempts", "state")),
            "A competing worker changed the live owner's claim or run")


def assert_complete(snapshot, initial, name):
    request = snapshot["request"]
    require(request["requestId"] == initial["request"]["requestId"]
            and request["actor"] == initial["request"]["actor"] and request["actor"] is not None
            and request["source"] == "MANUAL" and request["state"] == "SUCCEEDED" and request["attempts"] == 1,
            "Request identity, requester, attempt count or completion state changed unexpectedly")
    require(snapshot["commits"] == [PROJECTS[name]["sha"]] and snapshot["cursor"] == PROJECTS[name]["sha"]
            and snapshot["issues"] == 1 and snapshot["assignees"] == [request["actor"]]
            and snapshot["runs"] == [{"state": "SUCCEEDED", "commits": 1}],
            "A project lost or duplicated its commit, issue, assignment, run or checkpoint")


def register_project(browser, schema, fixture_url, name):
    _, page = browser.request("/projects")
    location, page = browser.request("/projects", {"_csrf": HELPERS.csrf(page), "name": "Synthetic concurrency " + name,
        "repositoryUrl": fixture_url + "/fixture/concurrency-" + name, "reviewBranch": ""})
    match = re.fullmatch(re.escape("/" + schema + "/projects/") + r"([0-9]+)", urllib.parse.urlsplit(location).path)
    require(match is not None, "Project registration did not create the synthetic project")
    project = int(match.group(1))
    browser.request("/admin/projects/" + str(project) + "/approve", {"_csrf": HELPERS.csrf(page)})
    return project


def enqueue(browser, project):
    _, page = browser.request("/projects/" + str(project))
    browser.request("/projects/" + str(project) + "/review", {"_csrf": HELPERS.csrf(page)})


def cleanup_owned(wars, fixture, database, schema, owned_schema):
    """Try every owned process independently; never drop a schema while one remains alive."""
    failed = False
    fixture.release_slow.set()
    for war in wars:
        try:
            war.stop(force=True)
        except Exception:
            failed = True
    try:
        fixture.close()
    except Exception:
        failed = True
    alive = any(war.process is not None and war.process.poll() is None for war in wars)
    if owned_schema and not alive:
        try:
            identifiers(schema)
            database.sql("DROP SCHEMA " + schema + " CASCADE;")
            owned_schema = False
        except Exception:
            failed = True
    return owned_schema, failed or alive


def remove_owned_work(work, directory, wars):
    require(not any(war.process is not None and war.process.poll() is None for war in wars),
            "Working directories were retained because an owned WAR remains alive")
    identifiers(directory.parent.name)
    require(directory.name in ("node-a", "node-b") and work.resolve().parent == directory.resolve()
            and work.name.startswith("owned-work-") and not work.is_symlink(),
            "Refusing an unexpected working-directory cleanup target")
    if work.exists():
        shutil.rmtree(work)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("war", "java", "psql"):
        parser.add_argument("--" + name, type=Path, required=True)
    for node in ("a", "b"):
        parser.add_argument("--port-" + node, type=int, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=240)
    parser.add_argument("--startup-timeout-seconds", type=int, default=45)
    parser.add_argument("--report", type=Path, default=Path(__file__).resolve().parents[1] / ".local" / "review-concurrency-result.json")
    args = parser.parse_args()
    for name in ("war", "java", "psql"):
        value = getattr(args, name).resolve(strict=True) if name == "war" else getattr(args, name).absolute()
        require(value.is_file(), "An explicitly configured executable or WAR is not a regular file")
        setattr(args, name, value)
    require(args.war.suffix.lower() == ".war", "The packaged application must be a WAR")
    require(args.port_a != args.port_b and all(1024 <= port <= 65535 for port in (args.port_a, args.port_b)), "Two distinct unused loopback WAR ports are required")
    require(90 <= args.timeout_seconds <= 600 and 15 <= args.startup_timeout_seconds <= 120, "Invalid concurrency drill timeout")
    args.report = args.report.resolve()
    for port in (args.port_a, args.port_b):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise VerificationError("A requested loopback WAR port is already in use") from None
    return args


def main():
    args = arguments()
    started, deadline = time.monotonic(), time.monotonic() + args.timeout_seconds
    schema = "restart_test_" + uuid.uuid4().hex
    args.report.parent.mkdir(parents=True, exist_ok=True)
    logs = args.report.parent / schema
    logs.mkdir()
    report = {"result": "FAIL", "externalServicesUsed": False, "paidAiUsed": False, "stage": "setup", "checks": {},
        "scope": "two packaged WARs, three single-commit projects, isolated local PostgreSQL, synthetic GitLab/Ollama",
        "limitations": ["Not a guarantee of arbitrary fairness", "Not a guarantee of exactly-once paid API calls"],
        "schema": schema, "logDirectory": str(logs), "perWarConcurrency": 1, "schedulerEnabled": False}
    wars, works, database, fixture, owned_schema, failure = [], [], None, ConcurrencyFixture(), False, None
    try:
        with ExitStack() as resources:
            node_logs = []
            for node in ("a", "b"):
                directory = logs / ("node-" + node)
                directory.mkdir()
                work = Path(tempfile.mkdtemp(prefix="owned-work-", dir=directory)).resolve()
                require(work.parent == directory.resolve() and work.name.startswith("owned-work-"), "Temporary cleanup target escaped its owned directory")
                works.append(work)
                resources.callback(remove_owned_work, work, directory, wars)
                node_logs.append(directory)
            try:
                database = HELPERS.Database(args.psql, os.environ.get("TEST_DATABASE_URL", ""), works[0])
                require(database.sql("SELECT current_database();") == "reviewer_integration", "The connected database is not the isolated test database")
                identifiers(schema)
                database.sql("CREATE SCHEMA " + schema + ";")
                owned_schema = True
                fixture_url = fixture.start()
                for index, port in enumerate((args.port_a, args.port_b)):
                    node_args = types.SimpleNamespace(**vars(args), port=port)
                    wars.append(HELPERS.OwnedWar(node_args, database, schema, fixture_url, works[index], node_logs[index], deadline))
                # Bootstrap is created by A, never reset by B. Both login flows use the same synthetic account.
                wars[1].password = wars[0].password
                report["stage"] = "hold_slow_project_on_first_war"
                wars[0].start(True)
                browser_a = HELPERS.Browser(wars[0].base)
                browser_a.login(wars[0].username, wars[0].password)
                project_ids = {name: register_project(browser_a, schema, fixture_url, name) for name in PROJECTS}
                enqueue(browser_a, project_ids["slow"])
                HELPERS.bounded_wait(fixture.slow_entered.is_set, min(deadline, time.monotonic() + 20), "Slow synthetic AI call did not enter its latch", wars[0].process)
                initial = {"slow": database.snapshot(schema, project_ids["slow"])}
                slow_owner = ownership(database, schema, project_ids["slow"])
                require(slow_owner["state"] == "RUNNING" and slow_owner["attempts"] == 1 and slow_owner["token"] is not None,
                        "The first WAR did not claim the slow request exactly once")
                require(not initial["slow"]["commits"] and initial["slow"]["issues"] == 0 and initial["slow"]["cursor"] is None,
                        "The blocked slow result was persisted before the AI response")
                report["checks"]["firstWarHoldsSlowProject"] = True

                report["stage"] = "prove_second_war_busy_deferral"
                wars[1].start(True)
                browser_b = HELPERS.Browser(wars[1].base)
                browser_b.login(wars[1].username, wars[1].password)
                force_fixture_due(database, schema, project_ids["slow"])
                enqueue(browser_b, project_ids["slow"])

                def busy_deferred():
                    current = ownership(database, schema, project_ids["slow"])
                    assert_owner_unchanged(slow_owner, current)
                    return current["due"] is False

                HELPERS.bounded_wait(busy_deferred, min(deadline, time.monotonic() + 15), "Second WAR did not defer the still-owned request", wars[1].process)
                require(not fixture.release_slow.is_set() and fixture.observed().get("slow.ai") == 1, "Slow AI was released or duplicated during contention")
                report["checks"]["bothWarsAuthenticatedWithSharedSyntheticAccount"] = True
                report["checks"]["secondWarBusyDeferralPreservedClaimRunAndAttempt"] = True

                report["stage"] = "complete_other_projects_while_slow_is_held"
                for name in ("fast1", "fast2"):
                    enqueue(browser_b, project_ids[name])
                    initial[name] = database.snapshot(schema, project_ids[name])
                final = {}

                def fast_completed():
                    require(not fixture.release_slow.is_set(), "Slow latch was released before the other projects completed")
                    for name in ("fast1", "fast2"):
                        final[name] = database.snapshot(schema, project_ids[name])
                        require(final[name]["request"]["state"] not in ("FAILED", "CANCELLED"), "A fast fixture request failed")
                    return all(final[name]["request"]["state"] == "SUCCEEDED" for name in ("fast1", "fast2"))

                HELPERS.bounded_wait(fast_completed, min(deadline, time.monotonic() + 25), "Other projects did not complete while the slow project was held", wars[1].process)
                slow_held = database.snapshot(schema, project_ids["slow"])
                require(slow_held["request"]["state"] == "RUNNING" and not slow_held["commits"] and slow_held["issues"] == 0
                        and slow_held["cursor"] is None, "Slow project did not remain blocked while other projects completed")
                assert_owner_unchanged(slow_owner, ownership(database, schema, project_ids["slow"]))
                with fixture.lock:
                    require(fixture.fast_while_slow == {"fast1", "fast2"}, "Concurrent synthetic AI overlap was not observed")
                for name in ("fast1", "fast2"):
                    assert_complete(final[name], initial[name], name)
                report["checks"]["bothFastProjectsCompletedWhileSlowAiHeld"] = True

                report["stage"] = "release_slow_and_verify_all_results"
                fixture.release_slow.set()

                def slow_completed():
                    final["slow"] = database.snapshot(schema, project_ids["slow"])
                    require(final["slow"]["request"]["state"] not in ("FAILED", "CANCELLED"), "The slow fixture request failed")
                    return final["slow"]["request"]["state"] == "SUCCEEDED"

                HELPERS.bounded_wait(slow_completed, min(deadline, time.monotonic() + 20), "Slow project did not complete after latch release", wars[0].process)
                counts = fixture.observed()
                for name in PROJECTS:
                    final[name] = database.snapshot(schema, project_ids[name])
                    assert_complete(final[name], initial[name], name)
                    require(all(counts.get(name + "." + operation) == 1 for operation in ("ai", "detail", "diff")),
                            "A project repeated or omitted its AI, detail or diff request")
                require(not fixture.errors, "A synthetic endpoint rejected an unexpected request")
                _, issues_a = browser_a.request("/issues")
                _, issues_b = browser_b.request("/issues")
                require(all("Synthetic finding " + name in issues_a and "Synthetic finding " + name in issues_b for name in PROJECTS),
                        "Both packaged servers did not render all three durable findings")
                report["checks"].update({"threeProjectsSingleRunAttemptCommitIssueAndCheckpoint": True,
                    "eachAiDetailAndDiffCalledOnce": True, "bothWarsRenderedSharedIssues": True})
                report["requestCounts"] = counts
                report["stage"] = "cleanup"
            finally:
                owned_schema, cleanup_failed = cleanup_owned(wars, fixture, database, schema, owned_schema)
                report["checks"]["ownedWarProcessesStopped"] = all(war.process is None or war.process.poll() is not None for war in wars)
                report["checks"]["ownedSchemaDropped"] = not owned_schema
                if cleanup_failed:
                    raise VerificationError("Owned resource cleanup did not complete")
        report["result"] = "PASS"
        report["stage"] = "complete"
    except (Exception, KeyboardInterrupt) as error:
        failure = error
        report["failureType"] = type(error).__name__
        if isinstance(error, VerificationError):
            report["failure"] = str(error)
        # Do not persist arbitrary exceptions, HTTP bodies, SQL output, credentials or ownership tokens.
    finally:
        report["elapsedMillis"] = round((time.monotonic() - started) * 1000)
        report["warStarts"] = sum(war.starts for war in wars)
        report["ownedSchemaCleanupPending"] = owned_schema
        report["checks"]["ownedWorkingDirectoriesRemoved"] = all(not work.exists() for work in works)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(report["result"] + ": packaged review concurrency drill; report=" + str(args.report))
    return 1 if failure is not None else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (VerificationError, OSError):
        print("FAIL: concurrency drill preflight rejected the supplied local configuration", file=sys.stderr)
        sys.exit(1)
