#!/usr/bin/env python3
"""Opt-in real WAR restart drill with synthetic, loopback-only GitLab/Ollama.

Requires Python 3.10+, Java, psql and an already running isolated test database.
Example (all paths can be absolute):
  python scripts/verify-review-restart.py --war source/target/ai-code-reviewer.war \
    --java /path/to/java --psql /path/to/psql --port 18089

TEST_DATABASE_URL must identify local reviewer_integration with an explicit port;
TEST_DATABASE_USERNAME/PASSWORD supply its credentials. --port is the WAR port,
not the PostgreSQL port. No production database, real repository, installed model,
API credential, repository checkout, Docker or third-party Python package is used.
"""

import argparse
import collections
import hashlib
import http.cookiejar
import http.server
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


SHA_A = "a" * 40
SHA_B = "b" * 40
FILES = {SHA_A: "A.java", SHA_B: "B.java"}
CONTENTS = {SHA_A: b"unsafeA();\n", SHA_B: b"unsafeB();\n"}
MODEL = "synthetic-restart-fixture"
DB_PATTERN = re.compile(r"jdbc:postgresql://(127\.0\.0\.1|localhost):([0-9]{1,5})/reviewer_integration\Z")
SCHEMA_PATTERN = re.compile(r"restart_test_[0-9a-f]{32}\Z")
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


class VerificationError(Exception):
    """Messages contain fixed diagnostics, never response bodies or credentials."""


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def base_environment():
    # Do not copy arbitrary environment secrets, Spring configuration, proxies or
    # JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS into either child process.
    names = ("PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",
             "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "LANG", "LC_ALL")
    return {name: os.environ[name] for name in names if name in os.environ}


def bounded_wait(check, deadline, message, process=None):
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise VerificationError("The owned WAR process exited before its expected state")
        if check():
            return
        time.sleep(0.15)
    raise VerificationError(message)


class Database:
    def __init__(self, psql, url, working_directory):
        match = DB_PATTERN.fullmatch(url)
        require(match is not None, "TEST_DATABASE_URL must identify loopback reviewer_integration without URL parameters")
        port = int(match.group(2))
        require(1 <= port <= 65535, "Invalid isolated PostgreSQL port")
        self.url = "jdbc:postgresql://127.0.0.1:" + str(port) + "/reviewer_integration"
        self.user = os.environ.get("TEST_DATABASE_USERNAME", "reviewer_test")
        require(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", self.user) is not None, "Invalid test database account name")
        self.password = os.environ.get("TEST_DATABASE_PASSWORD", "")
        self.command = [str(psql), "-X", "-w", "-h", "127.0.0.1", "-p", str(port),
                        "-U", self.user, "-d", "reviewer_integration", "-A", "-t", "-v", "ON_ERROR_STOP=1"]
        self.environment = base_environment()
        self.environment.update({"PGPASSWORD": self.password, "PGCONNECT_TIMEOUT": "5", "PGSSLMODE": "disable",
                                 "PGOPTIONS": "-c statement_timeout=5000 -c lock_timeout=5000",
                                 "PGPASSFILE": str(working_directory / "unused-pgpass"),
                                 "PGSERVICEFILE": str(working_directory / "unused-pgservice")})

    def sql(self, text):
        try:
            result = subprocess.run(self.command, input=text, text=True, encoding="utf-8",
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    env=self.environment, timeout=10, creationflags=CREATE_FLAGS, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise VerificationError("The isolated PostgreSQL command failed or timed out") from None
        require(result.returncode == 0, "The isolated PostgreSQL command failed; credentials and SQL output were withheld")
        return result.stdout.strip()

    def snapshot(self, schema, project):
        require(SCHEMA_PATTERN.fullmatch(schema) is not None and isinstance(project, int) and project > 0,
                "Invalid generated fixture identifiers")
        query = f"""
SELECT json_build_object(
  'request', (SELECT json_build_object('requestId',request_id,'actor',requested_by,'source',source,
      'state',state,'attempts',attempt_count) FROM {schema}.review_request WHERE project_id={project}),
  'cursor', (SELECT last_reviewed_sha FROM {schema}.project WHERE id={project}),
  'commits', COALESCE((SELECT json_agg(commit_sha ORDER BY id) FROM {schema}.reviewed_commit WHERE project_id={project}),'[]'::json),
  'issues', (SELECT count(*) FROM {schema}.review_issue WHERE project_id={project}),
  'assignees', COALESCE((SELECT json_agg(assignee_id ORDER BY id) FROM {schema}.review_issue WHERE project_id={project}),'[]'::json),
  'runs', COALESCE((SELECT json_agg(json_build_object('state',status,'commits',reviewed_commits) ORDER BY id)
      FROM {schema}.review_run WHERE project_id={project}),'[]'::json),
  'progress', (SELECT json_build_object('runId',r.id,'stage',r.progress_stage,
      'saved',r.reviewed_commits,'recordedAt',r.progress_updated_at,'lastSavedAt',r.last_saved_at)
      FROM {schema}.review_request q JOIN {schema}.review_run r ON r.id=q.run_id AND r.project_id=q.project_id
      WHERE q.project_id={project})
);
"""
        try:
            return json.loads(self.sql(query))
        except (ValueError, TypeError):
            raise VerificationError("The isolated database returned an invalid fixture snapshot") from None


class Fixture:
    def __init__(self):
        self.lock = threading.Lock()
        self.counts = collections.Counter()
        self.errors = []
        self.first_b_entered = threading.Event()
        self.release_first_b = threading.Event()
        self.second_b_entered = threading.Event()
        self.release_second_b = threading.Event()
        self.server = None
        self.thread = None

    @staticmethod
    def metadata(sha):
        return {"id": sha, "parent_ids": [] if sha == SHA_A else [SHA_A],
                "message": "Synthetic restart commit " + FILES[sha], "author_email": "fixture@example.invalid"}

    @staticmethod
    def tree(sha):
        entries = []
        for commit in ([SHA_A] if sha == SHA_A else [SHA_A, SHA_B]):
            content = CONTENTS[commit]
            object_sha = hashlib.sha1(b"blob " + str(len(content)).encode("ascii") + b"\0" + content).hexdigest()
            entries.append({"path": FILES[commit], "id": object_sha, "type": "blob", "mode": "100644"})
        return entries

    def note(self, key):
        with self.lock:
            self.counts[key] += 1
            return self.counts[key]

    def observed(self):
        with self.lock:
            return dict(self.counts)

    def fail(self, code):
        with self.lock:
            self.errors.append(code)

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
                    # The first B response intentionally outlives its killed client.
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
                    path = urllib.parse.unquote(url.path)
                    query = urllib.parse.parse_qs(url.query, strict_parsing=True)
                    base = "/api/v4/projects/fixture/restart/repository"
                    if path == base + "/commits":
                        fixture.note("history")
                        if query.get("per_page") == ["1"]:
                            require("ref_name" not in query, "Unexpected initial branch")
                            self.reply([fixture.metadata(SHA_B)])
                            return
                        require(query == {"ref_name": [SHA_B], "per_page": ["100"], "page": ["1"], "order": ["topo"]},
                                "History request was not pinned or exceeded fixture budget")
                        self.reply([fixture.metadata(SHA_B), fixture.metadata(SHA_A)])
                        return
                    if path == base + "/tree":
                        sha = query.get("ref", [None])[0]
                        require(sha in FILES and query == {"ref": [sha], "recursive": ["true"], "per_page": ["100"], "page": ["1"]},
                                "Unexpected immutable tree request")
                        fixture.note("tree_" + FILES[sha])
                        self.reply(fixture.tree(sha))
                        return
                    for sha in FILES:
                        if path == base + "/commits/" + sha:
                            require(query == {"stats": ["true"]}, "Unexpected detail request")
                            fixture.note("detail_" + FILES[sha])
                            self.reply(dict(fixture.metadata(sha), stats={"additions": 1, "deletions": 0, "total": 1}))
                            return
                        if path == base + "/commits/" + sha + "/diff":
                            require(query == {"unidiff": ["true"], "per_page": ["100"], "page": ["1"]}, "Unexpected diff request")
                            fixture.note("diff_" + FILES[sha])
                            self.reply([{"old_path": FILES[sha], "new_path": FILES[sha], "a_mode": "000000", "b_mode": "100644",
                                         "new_file": True, "deleted_file": False, "renamed_file": False,
                                         "collapsed": False, "too_large": False,
                                         "diff": "@@ -0,0 +1 @@\n+" + CONTENTS[sha].decode("ascii").rstrip("\n")}])
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
                    require(request.get("model") == MODEL and request.get("stream") is False, "Unexpected AI fixture settings")
                    require(isinstance(request.get("format"), dict), "Structured review schema was not supplied")
                    messages = request.get("messages")
                    require(isinstance(messages, list) and len(messages) == 2 and messages[1].get("role") == "user", "Unexpected AI messages")
                    content = json.loads(messages[1]["content"])
                    sha = content.get("commitSha")
                    require(sha in FILES and ("diff --git a/" + FILES[sha] + " b/" + FILES[sha]) in content.get("diff", ""),
                            "AI fixture received the wrong commit or file")
                    count = fixture.note("ai_" + FILES[sha])
                    if sha == SHA_B and count == 1:
                        fixture.first_b_entered.set()
                        if not fixture.release_first_b.wait(180):
                            raise VerificationError("The intentional B response delay exceeded its hard limit")
                    elif sha == SHA_B and count == 2:
                        fixture.second_b_entered.set()
                        if not fixture.release_second_b.wait(180):
                            raise VerificationError("The recovered B response delay exceeded its hard limit")
                    result = {"summary": "합성 재시작 검증용 리뷰", "findings": [{"severity": "HIGH", "title": "Synthetic finding " + FILES[sha],
                              "filePath": FILES[sha], "lineNumber": 1, "description": "합성 입력 검증입니다.", "suggestion": "합성 변경을 확인하세요."}]}
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
        self.release_first_b.set()
        self.release_second_b.set()
        if self.server is not None:
            if self.thread is not None and self.thread.is_alive():
                self.server.shutdown()
            self.server.server_close()
        if self.thread is not None:
            self.thread.join(3)


class ProgressParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.cards = []

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if values.get("id") == "review-progress":
            self.cards.append(values)


def verify_progress_page(page, expected_stage, expected_saved):
    parser = ProgressParser()
    parser.feed(page)
    require(len(parser.cards) == 1, "The packaged page did not render exactly one current progress card")
    card = parser.cards[0]
    require(card.get("data-progress-stage") == expected_stage
            and card.get("data-saved-commits") == str(expected_saved),
            "The packaged page rendered stale or incorrect current-attempt progress")


class CsrfParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.token = None

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if tag == "input" and values.get("name") == "_csrf":
            self.token = values.get("value")


def csrf(html):
    parser = CsrfParser()
    parser.feed(html)
    require(bool(parser.token), "The packaged JSP omitted its CSRF input")
    return parser.token


class LocalRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, base):
        self.base = base

    def redirect_request(self, request, fp, code, message, headers, new_url):
        require(new_url == self.base or new_url.startswith(self.base + "/") or new_url.startswith(self.base + "?"),
                "The WAR redirected outside its unique loopback context")
        return super().redirect_request(request, fp, code, message, headers, new_url)


class Browser:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                      urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), LocalRedirects(base))

    def request(self, path, fields=None):
        require(path.startswith("/") and not path.startswith("//"), "Invalid local application path")
        data = None if fields is None else urllib.parse.urlencode(fields).encode("utf-8")
        try:
            with self.opener.open(urllib.request.Request(self.base + path, data=data), timeout=10) as response:
                body = response.read(2_000_001)
                require(len(body) <= 2_000_000, "The packaged page exceeded the fixture limit")
                return response.geturl(), body.decode("utf-8")
        except (urllib.error.URLError, TimeoutError, UnicodeError):
            raise VerificationError("The packaged application HTTP request failed") from None

    def login(self, username, password):
        _, page = self.request("/login")
        location, _ = self.request("/login", {"_csrf": csrf(page), "username": username, "password": password})
        require("/login" not in urllib.parse.urlsplit(location).path, "Synthetic bootstrap login failed")


class OwnedWar:
    def __init__(self, args, database, schema, fixture_url, work, logs, deadline):
        self.args, self.database, self.schema = args, database, schema
        self.fixture_url, self.work, self.logs, self.deadline = fixture_url, work, logs, deadline
        self.username = "restartadmin"
        self.password = "Restart-fixture!" + secrets.token_hex(16)
        self.base = "http://127.0.0.1:" + str(args.port) + "/" + schema
        self.process, self.log_stream = None, None
        self.starts = 0

    def start(self, worker):
        require(self.process is None, "A previous owned WAR has not stopped")
        require(time.monotonic() < self.deadline, "The restart drill exceeded its total deadline")
        self.starts += 1
        environment = base_environment()
        environment.update({"DB_URL": self.database.url + "?currentSchema=" + self.schema,
            "DB_USERNAME": self.database.user, "DB_PASSWORD": self.database.password,
            "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(self.args.port), "SESSION_COOKIE_SECURE": "false",
            "BOOTSTRAP_ADMIN_USERNAME": self.username, "BOOTSTRAP_ADMIN_GIT_USERNAME": self.username,
            "BOOTSTRAP_ADMIN_PASSWORD": self.password, "REVIEW_ENABLED": "false", "REVIEW_WORKER_ENABLED": str(worker).lower(),
            "REVIEW_CONCURRENCY": "1", "REVIEW_MAX_COMMITS": "100", "GIT_ALLOWED_HOSTS": "127.0.0.1",
            "GIT_TOKEN": "", "GIT_TOKEN_HOST": "127.0.0.1", "GIT_TOKEN_ORIGIN": "", "GITHUB_API_URL": self.fixture_url,
            "GIT_TIMEOUT_SECONDS": "10", "GIT_OPERATION_TIMEOUT_SECONDS": "30",
            "AI_PROVIDER": "ollama", "AI_BASE_URL": self.fixture_url, "AI_MODEL": MODEL, "AI_API_KEY": "",
            "AI_TIMEOUT_SECONDS": "120", "AI_CONTEXT_TOKENS": "32768", "AI_MAX_OUTPUT_TOKENS": "4096",
            "JDBC_QUERY_TIMEOUT_SECONDS": "5", "JDBC_SOCKET_TIMEOUT_SECONDS": "10", "JDBC_CONNECT_TIMEOUT_SECONDS": "5"})
        log = self.logs / ("war-start-" + str(self.starts) + ".log")
        self.log_stream = log.open("wb")
        command = [str(self.args.java), "-Xmx384m", "-Djava.io.tmpdir=" + str(self.work), "-jar", str(self.args.war),
            "--spring.config.location=classpath:/application.properties",
            "--spring.flyway.schemas=" + self.schema, "--spring.flyway.default-schema=" + self.schema,
            "--server.servlet.context-path=/" + self.schema]
        try:
            self.process = subprocess.Popen(command, cwd=self.work, env=environment,
                stdin=subprocess.DEVNULL, stdout=self.log_stream, stderr=subprocess.STDOUT, creationflags=CREATE_FLAGS)
        except OSError:
            self.log_stream.close()
            self.log_stream = None
            raise VerificationError("Could not start the explicitly configured Java executable") from None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), LocalRedirects(self.base))

        def ready():
            try:
                # The application protects actuator with authentication. Render its
                # public packaged login/CSRF form without weakening that security policy.
                with opener.open(self.base + "/login", timeout=3) as response:
                    page = response.read(1_000_001)
                    return response.status == 200 and len(page) <= 1_000_000 and bool(csrf(page.decode("utf-8")))
            except (urllib.error.URLError, TimeoutError, UnicodeError, VerificationError):
                return False

        bounded_wait(ready, min(self.deadline, time.monotonic() + self.args.startup_timeout_seconds),
                     "The owned WAR did not render its packaged login and CSRF form before the deadline", self.process)
        # Readiness can race the bootstrap ApplicationRunner; wait for its synthetic account.
        bounded_wait(lambda: self.database.sql(f"SELECT count(*) FROM {self.schema}.app_user WHERE username='restartadmin';") == "1",
                     min(self.deadline, time.monotonic() + 10), "The synthetic bootstrap account was not created", self.process)

    def stop(self, force=False):
        if self.process is None:
            return
        owned = self.process
        try:
            if owned.poll() is None:
                if force:
                    owned.kill()
                else:
                    owned.terminate()
                try:
                    owned.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    owned.kill()
                    owned.wait(timeout=5)
        finally:
            if self.log_stream is not None:
                self.log_stream.close()
                self.log_stream = None
            if owned.poll() is not None:
                self.process = None
        require(owned.poll() is not None, "An owned WAR process could not be stopped")


def arguments():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("war", "java", "psql"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--port", type=int, required=True, help="Unused loopback WAR HTTP port; PostgreSQL port comes from TEST_DATABASE_URL")
    parser.add_argument("--timeout-seconds", type=int, default=180, help="Overall scenario deadline, excluding bounded cleanup (90..600)")
    parser.add_argument("--startup-timeout-seconds", type=int, default=45, help="Per-start readiness deadline (15..120)")
    parser.add_argument("--report", type=Path, default=Path(__file__).resolve().parents[1] / ".local" / "review-restart-result.json")
    args = parser.parse_args()
    for name in ("war", "java", "psql"):
        # Preserve executable symlinks: Debian/Ubuntu's psql wrapper dispatches
        # by argv[0], so resolving /usr/bin/psql to pg_wrapper breaks execution.
        value = getattr(args, name).resolve(strict=True) if name == "war" else getattr(args, name).absolute()
        require(value.is_file(), "An explicitly configured executable or WAR is not a regular file")
        setattr(args, name, value)
    require(args.war.suffix.lower() == ".war", "The packaged application must be a WAR")
    require(1024 <= args.port <= 65535 and 90 <= args.timeout_seconds <= 600
            and 15 <= args.startup_timeout_seconds <= 120, "Invalid restart drill port or timeout")
    args.report = args.report.resolve()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        try:
            probe.bind(("127.0.0.1", args.port))
        except OSError:
            raise VerificationError("The requested loopback WAR port is already in use") from None
    return args


def main():
    args = arguments()
    started = time.monotonic()
    deadline = started + args.timeout_seconds
    schema = "restart_test_" + uuid.uuid4().hex
    args.report.parent.mkdir(parents=True, exist_ok=True)
    logs = args.report.parent / schema
    logs.mkdir()
    report = {"result": "FAIL", "externalServicesUsed": False, "paidAiUsed": False,
              "scope": "packaged WAR, isolated local PostgreSQL schema, synthetic GitLab/Ollama",
              "schema": schema, "logDirectory": str(logs), "checks": {}, "stage": "setup"}
    owned_schema, war, fixture, database = False, None, Fixture(), None
    failure = None
    try:
        with tempfile.TemporaryDirectory(prefix="owned-work-", dir=logs) as temporary:
            work = Path(temporary).resolve()
            require(work.parent == logs.resolve() and work.name.startswith("owned-work-"),
                    "The generated temporary cleanup target escaped its owned report directory")
            database = Database(args.psql, os.environ.get("TEST_DATABASE_URL", ""), work)
            require(database.sql("SELECT current_database();") == "reviewer_integration", "The connected database is not the isolated test database")
            database.sql("CREATE SCHEMA " + schema + ";")
            owned_schema = True
            fixture_url = fixture.start()
            war = OwnedWar(args, database, schema, fixture_url, work, logs, deadline)
            try:
                report["stage"] = "enqueue_with_workers_disabled"
                war.start(False)
                browser = Browser(war.base)
                browser.login(war.username, war.password)
                _, project_form = browser.request("/projects")
                location, page = browser.request("/projects", {"_csrf": csrf(project_form), "name": "Synthetic restart fixture",
                    "repositoryUrl": fixture_url + "/fixture/restart", "reviewBranch": ""})
                match = re.fullmatch(re.escape("/" + schema + "/projects/") + r"([0-9]+)", urllib.parse.urlsplit(location).path)
                require(match is not None, "Project registration did not create the synthetic project")
                project = int(match.group(1))
                _, page = browser.request("/admin/projects/" + str(project) + "/approve", {"_csrf": csrf(page)})
                require("새 예약 요청 생성은 꺼져" in page and "요청을 저장할 수 있습니다" in page,
                        "The packaged page did not disclose disabled scheduling and saved-request behavior")
                _, page = browser.request("/projects/" + str(project) + "/review", {"_csrf": csrf(page)})
                # The POST redirects to review history; inspect the project itself too.
                _, page = browser.request("/projects/" + str(project))
                require('id="review-worker-paused"' in page and 'data-request-state="QUEUED"' in page,
                        "The packaged page did not disclose the worker pause and durable queued state")
                require("이미 리뷰 요청이 접수되어 있습니다" in page and "검토를 시작하세요" not in page,
                        "The packaged page advised starting a review while its request was already active")
                _, review_page = browser.request("/reviews?projectId=" + str(project))
                require('data-request-state="QUEUED"' in review_page and "이미 리뷰 요청이 접수되어 있습니다" in review_page,
                        "Review history did not explain its already queued request")
                initial = database.snapshot(schema, project)
                require(initial["request"]["state"] == "QUEUED" and initial["request"]["attempts"] == 0
                        and initial["request"]["source"] == "MANUAL" and initial["request"]["actor"] is not None
                        and not initial["runs"] and not initial["commits"] and not fixture.observed(),
                        "Worker-disabled request was not durably queued without external calls")
                require(initial["progress"] is None and 'id="review-progress"' not in page
                        and 'id="review-progress"' not in review_page,
                        "A never-started queued request was shown with another run's progress")
                report["checks"]["durableQueuedWithWorkersDisabled"] = True
                report["checks"]["packagedLoginCsrfAndBootstrapAuthentication"] = True
                report["checks"]["queuedJspAndDisabledScheduleNotice"] = True
                report["checks"]["workerPausedBannerAndQueuedState"] = True
                report["checks"]["activeRequestGuidanceInProjectAndHistory"] = True
                war.stop()

                report["stage"] = "restart_and_interrupt_second_commit"
                war.start(True)
                bounded_wait(fixture.first_b_entered.is_set, deadline, "The second synthetic AI call did not start", war.process)
                interrupted = database.snapshot(schema, project)
                require(interrupted["commits"] == [SHA_A] and interrupted["issues"] == 1 and interrupted["cursor"] is None
                        and interrupted["request"]["state"] == "RUNNING" and interrupted["request"]["attempts"] == 1
                        and interrupted["runs"] == [{"state": "RUNNING", "commits": 1}],
                        "The first commit was not atomic before the deliberately interrupted second AI call")
                before_kill = fixture.observed()
                require(before_kill.get("ai_A.java") == 1 and before_kill.get("ai_B.java") == 1
                        and before_kill.get("diff_A.java") == 1 and before_kill.get("diff_B.java") == 1,
                        "The synthetic pre-crash request count was unexpected")
                first_progress = interrupted["progress"]
                require(first_progress["stage"] == "REVIEWING" and first_progress["saved"] == 1
                        and first_progress["lastSavedAt"] is not None and first_progress["recordedAt"] is not None,
                        "The first saved commit did not durably update the current run's progress")
                browser = Browser(war.base)
                browser.login(war.username, war.password)
                for route in ("/projects/" + str(project), "/reviews?projectId=" + str(project)):
                    _, progress_page = browser.request(route)
                    verify_progress_page(progress_page, "REVIEWING", 1)
                report["checks"]["persistedProgressShownBeforeCrash"] = True
                war.stop(force=True)
                fixture.release_first_b.set()
                report["checks"]["queuedRequestSurvivedFirstProcessStop"] = True
                report["checks"]["forcedKillAfterFirstCommitBeforeCheckpoint"] = True

                report["stage"] = "recover_running_request_after_forced_kill"
                war.start(True)
                bounded_wait(fixture.second_b_entered.is_set, deadline,
                             "The recovered second synthetic AI call did not start", war.process)
                recovering = database.snapshot(schema, project)
                recovered_progress = recovering["progress"]
                require(recovering["commits"] == [SHA_A] and recovering["issues"] == 1
                        and recovered_progress["runId"] != first_progress["runId"]
                        and recovered_progress["stage"] == "REVIEWING" and recovered_progress["saved"] == 0
                        and recovered_progress["lastSavedAt"] is None and recovered_progress["recordedAt"] is not None,
                        "Recovery mixed prior saved results with this attempt's progress")
                browser = Browser(war.base)
                browser.login(war.username, war.password)
                for route in ("/projects/" + str(project), "/reviews?projectId=" + str(project)):
                    _, progress_page = browser.request(route)
                    verify_progress_page(progress_page, "REVIEWING", 0)
                report["checks"]["recoveredAttemptStartsWithZeroNewSaves"] = True
                fixture.release_second_b.set()
                final = None

                def completed():
                    nonlocal final
                    final = database.snapshot(schema, project)
                    require(final["request"]["state"] not in ("FAILED", "CANCELLED"), "Recovered request became terminal without succeeding")
                    return final["request"]["state"] == "SUCCEEDED"

                bounded_wait(completed, deadline, "The recovered request did not finish before its deadline", war.process)
                require(final["request"]["requestId"] == initial["request"]["requestId"]
                        and final["request"]["actor"] == initial["request"]["actor"] and final["request"]["source"] == "MANUAL"
                        and final["request"]["attempts"] == 2, "Request identity, requester or recovery attempt count changed unexpectedly")
                require(final["commits"] == [SHA_A, SHA_B] and final["cursor"] == SHA_B and final["issues"] == 2
                        and final["assignees"] == [initial["request"]["actor"]] * 2
                        and final["runs"] == [{"state": "FAILED", "commits": 1}, {"state": "SUCCEEDED", "commits": 1}],
                        "Recovery duplicated or lost reviewed commits, assignments, run state or the safe checkpoint")
                require(final["progress"]["runId"] == recovered_progress["runId"]
                        and final["progress"]["stage"] == "FINALIZING" and final["progress"]["saved"] == 1
                        and final["progress"]["lastSavedAt"] is not None,
                        "The recovered attempt's saved progress was not retained at completion")
                counts = fixture.observed()
                require(counts.get("ai_A.java") == 1 and counts.get("detail_A.java") == 1 and counts.get("diff_A.java") == 1
                        and counts.get("ai_B.java") == 2 and counts.get("detail_B.java") == 2 and counts.get("diff_B.java") == 2,
                        "Recovery refetched persisted commit content or failed to retry the unfinished commit")
                require(not fixture.errors, "A synthetic endpoint rejected an unexpected request")
                browser = Browser(war.base)
                browser.login(war.username, war.password)
                _, page = browser.request("/issues")
                require("Synthetic finding A.java" in page and "Synthetic finding B.java" in page, "Recovered issues were not rendered by the packaged JSP")
                require('id="review-worker-paused"' not in page, "The packaged page still showed paused workers after restart")
                report["checks"].update({"requestAndActorPreserved": True, "persistedCommitDiffAndAiNotRepeated": True,
                    "unfinishedCommitRetried": True, "exactlyTwoIssuesAndSafeCheckpoint": True, "recoveredIssuesRendered": True})
                report["requestCounts"] = counts
                report["runStates"] = [run["state"] for run in final["runs"]]
                report["attempts"] = final["request"]["attempts"]
            finally:
                # Stop only the Popen instances created here, before removing their working directory.
                if war is not None:
                    war.stop(force=True)
                fixture.close()
                if owned_schema:
                    require(SCHEMA_PATTERN.fullmatch(schema) is not None, "Refusing unexpected schema cleanup target")
                    database.sql("DROP SCHEMA " + schema + " CASCADE;")
                    owned_schema = False
                    report["checks"]["ownedSchemaDropped"] = True
                report["checks"]["ownedWarProcessesStopped"] = True
        report["result"] = "PASS"
        report["stage"] = "complete"
    except (Exception, KeyboardInterrupt) as error:
        failure = error
        report["failureType"] = type(error).__name__
        if isinstance(error, VerificationError):
            report["failure"] = str(error)
        # No tracebacks, child environment, HTTP bodies, SQL responses or arbitrary exception messages.
    finally:
        fixture.release_first_b.set()
        fixture.release_second_b.set()
        # Also cover failures between schema creation and the scenario's inner try.
        # Each resource is owned by this invocation; no PID lookup or global cleanup is used.
        cleanup_failed = False
        try:
            if war is not None and war.process is not None:
                war.stop(force=True)
            fixture.close()
            if owned_schema and database is not None:
                require(SCHEMA_PATTERN.fullmatch(schema) is not None, "Refusing unexpected schema cleanup target")
                database.sql("DROP SCHEMA " + schema + " CASCADE;")
                owned_schema = False
                report["checks"]["ownedSchemaDropped"] = True
        except Exception:
            cleanup_failed = True
            report["cleanupFailed"] = True
        if cleanup_failed:
            failure = VerificationError("Owned resource cleanup did not complete")
            report["result"] = "FAIL"
            report["failureType"] = "CleanupFailure"
        report["elapsedMillis"] = round((time.monotonic() - started) * 1000)
        report["warStarts"] = war.starts if war is not None else 0
        report["ownedSchemaCleanupPending"] = owned_schema
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(report["result"] + ": packaged review restart drill; report=" + str(args.report))
    return 1 if failure is not None else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (VerificationError, OSError):
        print("FAIL: restart drill preflight rejected the supplied local configuration", file=sys.stderr)
        sys.exit(1)
