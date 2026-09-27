package com.aicreviewer.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Opt-in synthetic local database observation, not a production throughput or latency guarantee. */
@EnabledIfEnvironmentVariable(named = "RUN_OPERATIONS_LOAD_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class OperationsTelemetryLoadSmokeTest {
    private static final int PROJECTS = 10_000;
    private static final int RUNS_PER_PROJECT = 10;
    private static final Instant NOW = Instant.parse("2026-09-27T05:00:00Z");
    private static final Path REPORT = Path.of("target/operations-load-result.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void collectsTenThousandProjectsAndExplainsTheActualLatestRunQuery() throws Exception {
        String schema = "telemetry_load_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = jdbc(dataSource(null));
        boolean schemaCreated = false;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("status", "RUNNING");
        report.put("fixture", "synthetic-local-postgresql");
        report.put("externalServicesUsed", false);
        report.put("productionPerformanceGuarantee", false);
        report.put("queryTimeoutSeconds", 30);
        report.put("socketTimeoutSeconds", 45);
        writeReport(report); // A failed new attempt must never leave an old successful report appearing current.
        try {
            admin.execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
            var source = dataSource(schema);
            var fixture = jdbc(source);
            assertThat(fixture.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            var transactionManager = new DataSourceTransactionManager(source);
            var seedTransaction = new TransactionTemplate(transactionManager);
            long seedStarted = System.nanoTime();
            seedTransaction.executeWithoutResult(status -> seed(fixture));
            report.put("seedMillis", elapsedMillis(seedStarted));
            Map<String, Long> rows = Map.of(
                    "projects", fixture.queryForObject("SELECT COUNT(*) FROM project", Long.class),
                    "reviewRuns", fixture.queryForObject("SELECT COUNT(*) FROM review_run", Long.class),
                    "reviewRequests", fixture.queryForObject("SELECT COUNT(*) FROM review_request", Long.class));
            assertThat(rows).containsEntry("projects", (long) PROJECTS)
                    .containsEntry("reviewRuns", (long) PROJECTS * RUNS_PER_PROJECT)
                    .containsEntry("reviewRequests", (long) PROJECTS);
            assertThat(fixture.queryForObject("SELECT COUNT(*) FROM review_run WHERE status='FAILED'", Long.class))
                    .isEqualTo(92_000L);
            report.put("rowCounts", rows);
            fixture.execute("ANALYZE project");
            fixture.execute("ANALYZE review_run");
            fixture.execute("ANALYZE review_request");

            var observing = new CapturingJdbcTemplate(source);
            var meters = new SimpleMeterRegistry();
            try {
                var telemetry = new OperationsTelemetryService(observing, transactionManager, meters, 120,
                        Clock.fixed(NOW, ZoneOffset.UTC));
                assertThat(telemetry.observation().status()).isEqualTo("STARTING");
                long collectionStarted = System.nanoTime();
                telemetry.refresh();
                report.put("collectionMillis", elapsedMillis(collectionStarted));
                var result = telemetry.observation();
                assertThat(result.status()).isEqualTo("READY");
                assertThat(result.projectCounts()).isEqualTo(Map.of("PENDING", 2500L, "APPROVED", 2500L,
                        "REJECTED", 2500L, "PAUSED", 2500L));
                assertThat(result.requestCounts()).isEqualTo(Map.of("QUEUED", 2000L, "RUNNING", 2000L,
                        "SUCCEEDED", 2000L, "FAILED", 2000L, "CANCELLED", 2000L));
                assertThat(result.projectCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(PROJECTS);
                assertThat(result.requestCounts().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(PROJECTS);
                assertThat(result.requestCounts().get("QUEUED") + result.requestCounts().get("RUNNING")).isEqualTo(4000L);
                assertThat(result.delayedActiveRequests()).isEqualTo(2000L);
                assertThat(result.latestFailedProjects()).isEqualTo(2000L);
                assertThat(result.oldestActiveRequestedAt()).isEqualTo(NOW.minusSeconds(7201));
                assertThat(meters.getMeters()).hasSize(14);
                assertThat(meters.find("ai.reviewer.projects").gauges()).hasSize(4);
                assertThat(meters.find("ai.reviewer.requests").gauges()).hasSize(5);
                meters.getMeters().forEach(meter -> {
                    assertThat(meter).isInstanceOf(Gauge.class);
                    assertThat(((Gauge) meter).value()).isFinite().isGreaterThanOrEqualTo(0);
                    meter.getId().getTags().forEach(tag -> {
                        assertThat(tag.getKey()).isIn("status", "state");
                        assertThat(tag.getValue()).isIn("PENDING", "APPROVED", "REJECTED", "PAUSED",
                                "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
                    });
                });
                assertThat(meters.get("ai.reviewer.requests.delayed").gauge().value()).isEqualTo(2000);
                assertThat(meters.get("ai.reviewer.projects.latest.failed").gauge().value()).isEqualTo(2000);
                assertThat(meters.get("ai.reviewer.requests.oldest.active.age.seconds").gauge().value()).isEqualTo(7201);
                report.put("projectCounts", result.projectCounts());
                report.put("requestCounts", result.requestCounts());
                report.put("activeRequests", 4000);
                report.put("delayedActiveRequests", result.delayedActiveRequests());
                report.put("latestFailedProjects", result.latestFailedProjects());
                report.put("metricSeries", meters.getMeters().size());
                assertThat(observing.scalarQueries).hasSize(1);
                String latestQuery = observing.scalarQueries.getFirst();
                assertThat(latestQuery).contains("FROM project p JOIN review_run", "ORDER BY latest.id DESC LIMIT 1");
                long explainStarted = System.nanoTime();
                String explained = fixture.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + latestQuery, String.class);
                report.put("explainMillis", elapsedMillis(explainStarted));
                JsonNode explanation = JSON.readTree(explained);
                assertThat(explanation.isArray()).isTrue();
                assertThat(explanation.size()).isEqualTo(1);
                JsonNode execution = explanation.get(0);
                JsonNode plan = execution.path("Plan");
                assertThat(plan.isObject()).isTrue();
                assertThat(plan.path("Node Type").asText()).isEqualTo("Aggregate");
                assertThat(plan.path("Actual Rows").asLong()).isEqualTo(1);
                assertThat(plan.path("Actual Loops").asLong()).isEqualTo(1);
                assertThat(execution.path("Planning Time").isNumber()).isTrue();
                assertThat(execution.path("Execution Time").isNumber()).isTrue();
                report.put("latestRunPlan", sanitizedPlan(plan));
                report.put("planPlanningMillis", execution.path("Planning Time").asDouble());
                report.put("planExecutionMillis", execution.path("Execution Time").asDouble());
                report.put("planScope", "Actual collection SQL, EXPLAIN ANALYZE BUFFERS after collection; warm-cache observation; expressions omitted");
                report.put("status", "PASSED");
            } finally {
                meters.close();
            }
        } finally {
            try {
                if (schemaCreated) {
                    if (!schema.matches("telemetry_load_[0-9a-f]{32}")) throw new IllegalStateException("Invalid owned fixture schema");
                    Long owned = admin.queryForObject("SELECT COUNT(*) FROM pg_namespace WHERE nspname=? AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)", Long.class, schema);
                    if (!Long.valueOf(1).equals(owned)) throw new IllegalStateException("Fixture schema ownership cannot be verified");
                    admin.execute("DROP SCHEMA " + schema + " CASCADE");
                    report.put("fixtureSchemaRemoved", true);
                }
            } catch (RuntimeException cleanupFailure) {
                report.put("status", "FAILED");
                throw cleanupFailure;
            } finally {
                if (!"PASSED".equals(report.get("status"))) report.put("status", "FAILED");
                writeReport(report);
            }
        }
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES(1,'load-owner','fixture','load-owner','USER')");
        jdbc.update("""
                INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status)
                SELECT g, 'Synthetic project ' || g, 'https://github.com/fixture/operations-' || g,
                       'GITHUB','github.com','fixture/operations-' || g,1,
                       CASE g % 4 WHEN 0 THEN 'APPROVED' WHEN 1 THEN 'PENDING' WHEN 2 THEN 'REJECTED' ELSE 'PAUSED' END
                FROM generate_series(1, ?) AS g
                """, PROJECTS);
        // Every project has nine old failures. The newest ID has an earlier wall-clock
        // start than the old failures, so a timestamp-based latest lookup is incorrect.
        jdbc.update("""
                INSERT INTO review_run(id,project_id,status,started_at,finished_at)
                SELECT (g-1)*10+n,g,
                       CASE WHEN n < 10 OR g % 5 = 3 THEN 'FAILED' WHEN g % 5 = 1 THEN 'RUNNING' ELSE 'SUCCEEDED' END,
                       CAST(? AS TIMESTAMP WITH TIME ZONE) - n * INTERVAL '1 minute',
                       CASE WHEN n=10 AND g % 5=1 THEN NULL ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END
                FROM generate_series(1, ?) AS g CROSS JOIN generate_series(1, 10) AS n
                """, Timestamp.from(NOW), Timestamp.from(NOW), PROJECTS);
        jdbc.update("""
                INSERT INTO review_request(project_id,request_id,claim_token,state,source,requested_at,available_at,run_id,finished_at)
                SELECT g, '00000000-0000-0000-0000-' || lpad(g::text,12,'0'),
                       CASE WHEN g % 5=1 THEN '10000000-0000-0000-0000-' || lpad(g::text,12,'0') END,
                       CASE g % 5 WHEN 0 THEN 'QUEUED' WHEN 1 THEN 'RUNNING' WHEN 2 THEN 'SUCCEEDED' WHEN 3 THEN 'FAILED' ELSE 'CANCELLED' END,
                       'SCHEDULED', CAST(? AS TIMESTAMP WITH TIME ZONE) -
                           CASE g % 10 WHEN 0 THEN 7200 WHEN 1 THEN 7201 WHEN 5 THEN 7199 WHEN 6 THEN -1 ELSE 14400 END * INTERVAL '1 second',
                       CAST(? AS TIMESTAMP WITH TIME ZONE),
                       CASE WHEN g % 5=1 THEN g*10 END,
                       CASE WHEN g % 5 IN (0,1) THEN NULL ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END
                FROM generate_series(1, ?) AS g
                """, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW), PROJECTS);
    }

    /** Keep topology, costs and buffer counts; omit SQL expressions, schema, aliases and fixture values. */
    private static Map<String, Object> sanitizedPlan(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("Node Type", "Join Type", "Strategy", "Scan Direction")) {
            if (node.has(key)) result.put(key, node.path(key).asText());
        }
        if (node.has("Relation Name")) {
            String relation = node.path("Relation Name").asText();
            assertThat(relation).isIn("project", "review_run");
            result.put("Relation Name", relation);
        }
        if (node.has("Index Name")) {
            String index = node.path("Index Name").asText();
            assertThat(index).matches("[a-z_][a-z0-9_]{0,119}");
            result.put("Index Name", index);
        }
        for (String key : List.of("Startup Cost", "Total Cost", "Plan Rows", "Plan Width", "Actual Startup Time",
                "Actual Total Time", "Actual Rows", "Actual Loops", "Shared Hit Blocks", "Shared Read Blocks",
                "Shared Dirtied Blocks", "Shared Written Blocks", "Local Hit Blocks", "Local Read Blocks",
                "Temp Read Blocks", "Temp Written Blocks")) {
            if (node.path(key).isNumber()) result.put(key, node.path(key).asDouble());
        }
        if (node.has("Plans")) {
            var children = new ArrayList<Map<String, Object>>();
            node.path("Plans").forEach(child -> children.add(sanitizedPlan(child)));
            result.put("Plans", children);
        }
        return result;
    }

    private static JdbcTemplate jdbc(DriverManagerDataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        return jdbc;
    }

    private static DriverManagerDataSource dataSource(String schema) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")) {
            throw new IllegalStateException("An explicit local integration database is required");
        }
        var source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        source.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "45");
        properties.setProperty("options", "-c statement_timeout=30000");
        if (schema != null) {
            properties.setProperty("currentSchema", schema);
            properties.setProperty("ApplicationName", schema);
        }
        source.setConnectionProperties(properties);
        return source;
    }

    private static long elapsedMillis(long since) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since); }

    private static void writeReport(Map<String, Object> report) throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        final List<String> scalarQueries = new ArrayList<>();
        CapturingJdbcTemplate(DriverManagerDataSource source) { super(source); setQueryTimeout(30); }
        @Override public <T> T queryForObject(String sql, Class<T> requiredType) {
            scalarQueries.add(sql);
            return super.queryForObject(sql, requiredType);
        }
    }
}
