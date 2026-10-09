package com.aicreviewer.operations;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.issue.IssueService;
import com.aicreviewer.review.ReviewActor;
import com.aicreviewer.web.AuditController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Bounded synthetic list observations. Cache state is not controlled; no latency pass threshold. */
@EnabledIfEnvironmentVariable(named = "RUN_PAGE_LOAD_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class PageLoadPostgresTest {
    private static final int ISSUES = 100_000;
    private static final int AUDITS = 100_000;
    private static final int PROJECTS = 3_000;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Path REPORT = Path.of("target/page-load-result.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ReviewActor ADMIN = new ReviewActor(1, "page-admin", true);
    private static final ReviewActor MEMBER = new ReviewActor(2, "page-member", false);
    private static final ReviewActor OUTSIDER = new ReviewActor(4, "page-outsider", false);
    private static final Set<String> RELATIONS = Set.of("review_issue", "reviewed_commit", "project", "app_user", "audit_event", "review_run", "review_request");

    @Test @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SAME_THREAD)
    void observesRealListQueriesAtFirstMiddleLastAndEmptyPages() throws Exception {
        String schema = "page_load_" + UUID.randomUUID().toString().replace("-", "");
        String marker = "page-load-owned-" + UUID.randomUUID();
        JdbcTemplate admin = jdbc(dataSource(null));
        SchemaIdentity owned = null;
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> observations = new ArrayList<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("status", "RUNNING");
        report.put("fixture", "synthetic-local-postgresql");
        report.put("externalServicesUsed", false);
        report.put("productionPerformanceGuarantee", false);
        report.put("scope", "Actual service/controller SQL; sequential calls and EXPLAIN after seeding/counting/ANALYZE; cache state is not controlled, not HTTP/render or concurrent throughput");
        report.put("queryTimeoutSeconds", 30);
        report.put("socketTimeoutSeconds", 45);
        report.put("observations", observations);
        report.put("fixtureSchemaRemoved", false);
        writeReport(report); // Invalidate any previous successful attempt before touching the database.
        String failure = null;
        boolean assertionsPassed = false;
        try {
            report.put("stage", "schema");
            var creation = new TransactionTemplate(new DataSourceTransactionManager(admin.getDataSource()));
            owned = creation.execute(status -> {
                admin.execute("CREATE SCHEMA " + schema);
                admin.execute("COMMENT ON SCHEMA " + schema + " IS '" + marker + "'");
                SchemaIdentity identity = schemaIdentity(admin, schema);
                assertThat(identity).isNotNull();
                assertThat(identity.owner()).isEqualTo(admin.queryForObject("SELECT current_user", String.class));
                assertThat(identity.marker()).isEqualTo(marker);
                return identity;
            });
            var source = dataSource(schema);
            var fixture = jdbc(source);
            assertThat(fixture.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            report.put("stage", "seed");
            long seedStarted = System.nanoTime();
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> seed(fixture));
            report.put("seedMillis", elapsedMillis(seedStarted));
            var counts = Map.of("projects", count(fixture, "project"), "reviewRuns", count(fixture, "review_run"),
                    "reviewedCommits", count(fixture, "reviewed_commit"), "issues", count(fixture, "review_issue"),
                    "auditEvents", count(fixture, "audit_event"), "reviewRequests", count(fixture, "review_request"));
            assertThat(counts).containsEntry("projects", 3000L).containsEntry("reviewRuns", 27000L)
                    .containsEntry("reviewedCommits", 3000L).containsEntry("issues", 100000L)
                    .containsEntry("auditEvents", 100000L).containsEntry("reviewRequests", 3000L);
            report.put("rowCounts", counts);
            for (String table : RELATIONS) fixture.execute("ANALYZE " + table);
            var capture = new CapturingJdbcTemplate(source);
            report.put("stage", "issues");
            observeIssues(capture, fixture, observations);
            report.put("stage", "audit");
            observeAudit(capture, fixture, observations);
            report.put("stage", "operations");
            observeOperations(capture, fixture, observations);
            assertionsPassed = true;
        } catch (Exception | AssertionError problem) {
            failure = problem.getClass().getSimpleName(); // Never retain SQL, connection details or exception messages.
        } finally {
            try {
                if (owned != null) {
                    if (!schema.matches("page_load_[0-9a-f]{32}")) throw new IllegalStateException("Invalid fixture identity");
                    assertThat(schemaIdentity(admin, schema)).as("Only the exact newly created schema may be removed").isEqualTo(owned);
                    admin.execute("DROP SCHEMA " + schema + " CASCADE");
                    assertThat(schemaIdentity(admin, schema)).isNull();
                    report.put("fixtureSchemaRemoved", true);
                }
            } catch (Exception | AssertionError cleanup) {
                report.put("cleanupFailureType", cleanup.getClass().getSimpleName());
                failure = failure == null ? "CleanupFailure" : failure;
            }
            boolean passed = assertionsPassed && failure == null && Boolean.TRUE.equals(report.get("fixtureSchemaRemoved"));
            report.put("status", passed ? "PASSED" : "FAILED");
            if (failure != null) report.put("failureType", failure);
            report.put("finishedAt", Instant.now().toString());
            writeReport(report);
        }
        assertThat(report.get("status")).as("Local list observation; see sanitized target/page-load-result.json").isEqualTo("PASSED");
    }

    private static void observeIssues(CapturingJdbcTemplate capture, JdbcTemplate explain, List<Map<String, Object>> reports) throws Exception {
        var service = new IssueService(capture);
        for (ReviewActor actor : List.of(ADMIN, MEMBER)) {
            for (String status : List.of("", "OPEN")) {
                List<Long> expected = LongStream.iterate(ISSUES, id -> id > 0, id -> id - 1)
                        .filter(id -> actor.admin() || id % 2 == 0)
                        .filter(id -> status.isEmpty() || id % 3 == 0).boxed().toList();
                for (int page : samplePages(expected.size(), IssueService.PAGE_SIZE)) {
                    String name = "issues-" + (actor.admin() ? "admin" : "assigned") + "-" + (status.isEmpty() ? "all" : "open");
                    measure(name, page, capture, explain, reports, () -> {
                        var result = service.list(actor, status, page);
                        List<Long> ids = result.issues().stream().map(row -> ((Number) row.get("id")).longValue()).toList();
                        assertPage(ids, result.hasNext(), page, IssueService.PAGE_SIZE, expected);
                        for (var row : result.issues()) {
                            long id = ((Number) row.get("id")).longValue();
                            long project = (id - 1) % PROJECTS + 1;
                            assertThat(row.get("status")).isEqualTo(issueStatus(id));
                            assertThat(row.get("assignee_username")).isEqualTo(id % 2 == 0 ? "page-member" : "page-other");
                            assertThat(row.get("fallback_assignment")).isEqualTo(project % 3 == 0);
                            assertThat(row.get("description_preview").toString()).hasSize(240);
                            assertThat(row).doesNotContainKey("repository_url");
                            assertThat(row.get("commit_url")).isEqualTo("https://github.com/fixture/page-" + project + "/commit/" + String.format("%040x", project));
                        }
                        return ids.size();
                    });
                }
            }
        }
        for (String status : List.of("RESOLVED", "DISMISSED")) {
            List<Long> expected = LongStream.iterate(ISSUES, id -> id > 0, id -> id - 1)
                    .filter(id -> id % 2 == 0 && issueStatus(id).equals(status)).boxed().toList();
            var result = service.list(MEMBER, status, 0);
            assertPage(result.issues().stream().map(row -> ((Number) row.get("id")).longValue()).toList(), result.hasNext(), 0, 25, expected);
        }
        assertThat(service.list(OUTSIDER, "", 0).issues()).isEmpty();
        assertThatThrownBy(() -> service.detail(2, OUTSIDER)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        for (int page : List.of(-1, 10001)) assertThatThrownBy(() -> service.list(ADMIN, "", page)).isInstanceOf(ResponseStatusException.class);
    }

    private static void observeAudit(CapturingJdbcTemplate capture, JdbcTemplate explain, List<Map<String, Object>> reports) throws Exception {
        var previous = SecurityContextHolder.getContext();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(AuditController.class, () -> new AuditController(capture));
            context.refresh();
            var controller = context.getBean(AuditController.class);
            authenticate("ROLE_USER");
            capture.clear();
            assertThatThrownBy(() -> controller.audit(0, new ExtendedModelMap())).isInstanceOf(AccessDeniedException.class);
            assertThat(capture.queries).isEmpty();
            authenticate("ROLE_ADMIN");
            List<Long> expected = LongStream.iterate(AUDITS, id -> id > 0, id -> id - 1).boxed().toList();
            for (int page : samplePages(AUDITS, 50)) {
                measure("audit-admin", page, capture, explain, reports, () -> {
                    var model = new ExtendedModelMap();
                    assertThat(controller.audit(page, model)).isEqualTo("admin/audit");
                    @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) model.get("events");
                    assertPage(rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList(), (Boolean) model.get("hasNext"), page, 50, expected);
                    rows.forEach(row -> assertThat(row.get("username")).isEqualTo(((Number) row.get("id")).longValue() % 4 == 0 ? null : "page-admin"));
                    return rows.size();
                });
            }
            for (int page : List.of(-1, 10001)) {
                capture.clear();
                assertThatThrownBy(() -> controller.audit(page, new ExtendedModelMap())).isInstanceOf(ResponseStatusException.class);
                assertThat(capture.queries).isEmpty();
            }
        } finally { SecurityContextHolder.setContext(previous); }
    }

    private static void observeOperations(CapturingJdbcTemplate capture, JdbcTemplate explain, List<Map<String, Object>> reports) throws Exception {
        // Account authorization has its own one-row SQL; keep it out of the captured list plan.
        var users = new UserAccountService(explain, new BCryptPasswordEncoder(4), new AuditEventWriter(explain));
        var service = new OperationsService(capture, users, 120, Clock.fixed(NOW, ZoneOffset.UTC));
        for (String user : List.of("page-member", "page-outsider")) {
            capture.clear();
            assertThatThrownBy(() -> service.list(user, "ATTENTION", 0)).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode().value()).isEqualTo(403));
            assertThat(capture.queries).isEmpty();
        }
        for (String filter : List.of("ATTENTION", "FAILED", "STALE", "NEVER_RUN", "QUEUED", "REQUEST_DELAYED", "RATE_LIMITED")) {
            boolean requestOrder = Set.of("QUEUED", "REQUEST_DELAYED", "RATE_LIMITED").contains(filter);
            Comparator<Long> order = Comparator.comparing((Long id) -> requestOrder ? requestedAt(id) : startedAt(id), Comparator.nullsFirst(Comparator.naturalOrder()));
            List<Long> expected = LongStream.rangeClosed(1, PROJECTS).boxed().filter(id -> matchesOperation(id, filter))
                    .sorted(order.thenComparingLong(Long::longValue)).toList();
            assertThat(expected).as("Synthetic filter must contain rows").isNotEmpty();
            for (int page : samplePages(expected.size(), 50)) {
                measure("operations-" + filter.toLowerCase(java.util.Locale.ROOT), page, capture, explain, reports, () -> {
                    var result = service.list("page-admin", filter, page);
                    assertThat(result.filter()).isEqualTo(filter);
                    assertThat(result.observedAt()).isEqualTo(NOW);
                    assertPage(result.projects().stream().map(OperationsService.ProjectObservation::projectId).toList(), result.hasNext(), page, 50, expected);
                    for (var row : result.projects()) {
                        long id = row.projectId();
                        assertThat(row.runId()).isEqualTo(id % 10 == 0 ? null : id * 10);
                        assertThat(row.runStatus()).isEqualTo(latestStatus(id));
                        assertThat(row.startedAt()).isEqualTo(startedAt(id));
                        assertThat(row.requestState()).isEqualTo(requestState(id));
                        assertThat(row.requestDelayed()).isEqualTo(delayed(id));
                        assertThat(row.rateLimited()).isEqualTo(id % 20 == 0 || id % 20 == 5);
                    }
                    return result.projects().size();
                });
            }
        }
    }

    private static void measure(String name, int page, CapturingJdbcTemplate capture, JdbcTemplate explain,
                                List<Map<String, Object>> reports, Supplier<Integer> operation) throws Exception {
        capture.clear();
        long started = System.nanoTime();
        int returned = operation.get();
        long millis = elapsedMillis(started);
        assertThat(capture.queries).hasSize(1);
        var actual = capture.queries.getFirst();
        String output = explain.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + actual.sql(), String.class, actual.arguments());
        JsonNode document = JSON.readTree(output);
        assertThat(document.isArray()).isTrue();
        assertThat(document.size()).isEqualTo(1);
        JsonNode execution = document.get(0);
        assertThat(execution.path("Plan").isObject()).isTrue();
        assertThat(execution.path("Planning Time").isNumber()).isTrue();
        assertThat(execution.path("Execution Time").isNumber()).isTrue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        result.put("page", page);
        result.put("returnedRows", returned);
        result.put("serviceAndAssertionsMillis", millis);
        result.put("planningMillis", execution.path("Planning Time").asDouble());
        result.put("executionMillis", execution.path("Execution Time").asDouble());
        result.put("plan", sanitizedPlan(execution.path("Plan")));
        reports.add(result);
    }

    private static void assertPage(List<Long> actual, boolean hasNext, int page, int size, List<Long> expected) {
        long offset = (long) page * size;
        int start = (int) Math.min(offset, expected.size());
        int end = Math.min(start + size, expected.size());
        assertThat(actual).containsExactlyElementsOf(expected.subList(start, end));
        assertThat(actual).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(size);
        assertThat(hasNext).isEqualTo(offset + size < expected.size() && page < 10000);
    }

    private static List<Integer> samplePages(int rows, int size) {
        int last = (rows - 1) / size;
        return List.of(0, last / 2, last, last + 1, 10000).stream().distinct().toList();
    }

    private static boolean matchesOperation(long id, String filter) {
        boolean failed = "FAILED".equals(latestStatus(id));
        boolean never = id % 4 == 0 && startedAt(id) == null;
        boolean stale = id % 4 == 0 && startedAt(id) != null && !startedAt(id).isAfter(NOW.minusSeconds(7200));
        return switch (filter) {
            case "FAILED" -> failed;
            case "STALE" -> stale;
            case "NEVER_RUN" -> never;
            case "QUEUED" -> id % 5 == 0;
            case "REQUEST_DELAYED" -> delayed(id);
            case "RATE_LIMITED" -> id % 20 == 0 || id % 20 == 5;
            default -> failed || never || stale || delayed(id);
        };
    }

    private static boolean delayed(long id) { return id % 5 <= 1 && !requestedAt(id).isAfter(NOW.minusSeconds(7200)); }
    private static Instant requestedAt(long id) { return NOW.minusSeconds(switch ((int) (id % 4)) { case 0 -> 7200; case 1 -> 7201; case 2 -> 7199; default -> -1; }); }
    private static Instant startedAt(long id) { return id % 10 == 0 ? null : NOW.minusSeconds(id % 6 < 3 ? 7200 : 7199); }
    private static String latestStatus(long id) { return id % 10 == 0 ? null : switch ((int) (id % 3)) { case 0 -> "FAILED"; case 1 -> "SUCCEEDED"; default -> "RUNNING"; }; }
    private static String requestState(long id) { return List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").get((int) (id % 5)); }
    private static String issueStatus(long id) { return List.of("OPEN", "RESOLVED", "DISMISSED").get((int) (id % 3)); }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES
                (1,'page-admin','synthetic-not-a-login-hash','page-admin','ADMIN'),
                (2,'page-member','synthetic-not-a-login-hash','page-member','USER'),
                (3,'page-other','synthetic-not-a-login-hash','page-other','USER'),
                (4,'page-outsider','synthetic-not-a-login-hash','page-outsider','USER')
                """);
        jdbc.update("""
                INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status)
                SELECT g,'Synthetic project '||g,'https://github.com/fixture/page-'||g,'GITHUB','github.com','fixture/page-'||g,2,
                       CASE g%4 WHEN 0 THEN 'APPROVED' WHEN 1 THEN 'PENDING' WHEN 2 THEN 'REJECTED' ELSE 'PAUSED' END
                FROM generate_series(1,?) g
                """, PROJECTS);
        jdbc.update("""
                INSERT INTO reviewed_commit(id,project_id,commit_sha,author_login,summary)
                SELECT g,g,lpad(to_hex(g),40,'0'),'page-member','Synthetic summary' FROM generate_series(1,?) g
                """, PROJECTS);
        jdbc.update("""
                INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status)
                SELECT g,(g-1)%3000+1,(g-1)%3000+1,CASE WHEN g%2=0 THEN 2 ELSE 3 END,'MEDIUM','Synthetic finding '||g,
                       'src/Fixture.java',repeat(md5(g::text),16),'Synthetic suggestion',
                       CASE g%3 WHEN 0 THEN 'OPEN' WHEN 1 THEN 'RESOLVED' ELSE 'DISMISSED' END
                FROM generate_series(1,?) g
                """, ISSUES);
        jdbc.update("""
                INSERT INTO audit_event(id,actor_id,action,target_type,target_id,detail)
                SELECT g,CASE WHEN g%4=0 THEN NULL ELSE 1 END,
                       CASE WHEN g<=1000 THEN 'ISSUE_ASSIGNEE_FALLBACK' ELSE 'ISSUE_STATUS_CHANGED' END,
                       CASE WHEN g<=1000 THEN 'REVIEWED_COMMIT' ELSE 'REVIEW_ISSUE' END,
                       CASE WHEN g<=1000 THEN g*3 ELSE g END,repeat(md5(g::text),16)
                FROM generate_series(1,?) g
                """, AUDITS);
        // Latest IDs deliberately start before the nine historical failures. Every tenth project has no run.
        jdbc.update("""
                INSERT INTO review_run(id,project_id,status,started_at,finished_at)
                SELECT (g-1)*10+n,g,
                       CASE WHEN n<10 OR g%3=0 THEN 'FAILED' WHEN g%3=1 THEN 'SUCCEEDED' ELSE 'RUNNING' END,
                       CAST(? AS TIMESTAMP WITH TIME ZONE)-CASE WHEN n<10 THEN 60 WHEN g%6<3 THEN 7200 ELSE 7199 END * INTERVAL '1 second',
                       CASE WHEN n=10 AND g%3=2 THEN NULL ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END
                FROM generate_series(1,?) g CROSS JOIN generate_series(1,10) n WHERE g%10<>0
                """, Timestamp.from(NOW), Timestamp.from(NOW), PROJECTS);
        jdbc.update("""
                INSERT INTO review_request(project_id,request_id,claim_token,state,source,requested_at,available_at,run_id,finished_at,result_code)
                SELECT g,'00000000-0000-0000-0000-'||lpad(g::text,12,'0'),
                       CASE WHEN g%5=1 THEN '10000000-0000-0000-0000-'||lpad(g::text,12,'0') END,
                       CASE g%5 WHEN 0 THEN 'QUEUED' WHEN 1 THEN 'RUNNING' WHEN 2 THEN 'SUCCEEDED' WHEN 3 THEN 'FAILED' ELSE 'CANCELLED' END,
                       'SCHEDULED',CAST(? AS TIMESTAMP WITH TIME ZONE)-CASE g%4 WHEN 0 THEN 7200 WHEN 1 THEN 7201 WHEN 2 THEN 7199 ELSE -1 END * INTERVAL '1 second',
                       CAST(? AS TIMESTAMP WITH TIME ZONE)+INTERVAL '120 seconds',
                       CASE WHEN g%5=1 THEN g*10 END,
                       CASE WHEN g%5 IN (0,1) THEN NULL ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,
                       CASE g%20 WHEN 0 THEN 'GIT_RATE_LIMITED' WHEN 5 THEN 'AI_RATE_LIMITED' END
                FROM generate_series(1,?) g
                """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), PROJECTS);
    }

    /** Explicit fields only: no SQL, expressions, identifiers from source data, schema or raw errors. */
    private static Map<String, Object> sanitizedPlan(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("Node Type", "Join Type", "Strategy", "Scan Direction", "Sort Method", "Sort Space Type")) {
            if (node.has(field)) {
                String value = node.path(field).asText();
                assertThat(value).matches("[A-Za-z0-9 -]{1,80}");
                result.put(field, value);
            }
        }
        if (node.has("Relation Name")) {
            String relation = node.path("Relation Name").asText();
            assertThat(relation).isIn(RELATIONS);
            result.put("Relation Name", relation);
        }
        if (node.has("Index Name")) {
            String index = node.path("Index Name").asText();
            assertThat(index).matches("[a-z_][a-z0-9_]{0,119}");
            result.put("Index Name", index);
        }
        for (String field : List.of("Startup Cost", "Total Cost", "Plan Rows", "Plan Width", "Actual Startup Time", "Actual Total Time",
                "Actual Rows", "Actual Loops", "Rows Removed by Filter", "Rows Removed by Join Filter", "Heap Fetches", "Sort Space Used",
                "Shared Hit Blocks", "Shared Read Blocks", "Shared Dirtied Blocks", "Shared Written Blocks", "Temp Read Blocks", "Temp Written Blocks")) {
            if (node.path(field).isNumber()) result.put(field, node.path(field).asDouble());
        }
        if (node.has("Plans")) {
            var children = new ArrayList<Map<String, Object>>();
            node.path("Plans").forEach(child -> children.add(sanitizedPlan(child)));
            result.put("Plans", children);
        }
        return result;
    }

    private static void authenticate(String authority) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("synthetic-actor", "unused", AuthorityUtils.createAuthorityList(authority)));
        SecurityContextHolder.setContext(context);
    }

    @TestConfiguration(proxyBeanMethods = false) @EnableMethodSecurity
    static class MethodSecurity { }

    private static DriverManagerDataSource dataSource(String schema) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")) throw new IllegalStateException("Local test database required");
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        source.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "45");
        properties.setProperty("options", "-c statement_timeout=30000 -c lock_timeout=10000");
        if (schema != null) { properties.setProperty("currentSchema", schema); properties.setProperty("ApplicationName", schema); }
        source.setConnectionProperties(properties);
        return source;
    }

    private static JdbcTemplate jdbc(DriverManagerDataSource source) { var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(30); return jdbc; }
    private static long count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static long elapsedMillis(long since) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since); }
    private static void writeReport(Map<String, Object> report) throws Exception { Files.createDirectories(REPORT.getParent()); Files.writeString(REPORT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
    private static SchemaIdentity schemaIdentity(JdbcTemplate jdbc, String schema) {
        return jdbc.query("SELECT n.oid,pg_get_userbyid(n.nspowner) AS owner,obj_description(n.oid,'pg_namespace') AS marker FROM pg_namespace n WHERE n.nspname=?",
                (rs, row) -> new SchemaIdentity(rs.getLong("oid"), rs.getString("owner"), rs.getString("marker")), schema).stream().findFirst().orElse(null);
    }
    private record SchemaIdentity(long oid, String owner, String marker) { }
    private record CapturedQuery(String sql, Object[] arguments) { }
    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<CapturedQuery> queries = new ArrayList<>();
        private int captureDepth;
        CapturingJdbcTemplate(DriverManagerDataSource source) { super(source); setQueryTimeout(30); }
        void clear() { queries.clear(); }
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
            queries.add(new CapturedQuery(sql, Arrays.copyOf(args, args.length)));
            captureDepth++;
            try { return super.queryForList(sql, args); }
            finally { captureDepth--; }
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (captureDepth == 0) queries.add(new CapturedQuery(sql, Arrays.copyOf(args, args.length)));
            return super.query(sql, rowMapper, args);
        }
    }
}
