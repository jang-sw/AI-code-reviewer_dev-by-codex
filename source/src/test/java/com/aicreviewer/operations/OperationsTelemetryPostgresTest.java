package com.aicreviewer.operations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Real MVCC/transaction checks in a disposable schema; no Git or AI connections. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class OperationsTelemetryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-27T04:00:00Z");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private String schema;
    private SimpleMeterRegistry meters;

    @BeforeEach void setup() {
        schema = "telemetry_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(dataSource(null));
        admin.setQueryTimeout(10);
        admin.execute("create schema " + schema);
        source = dataSource(schema);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        meters = new SimpleMeterRegistry();
        jdbc.update("insert into app_user(id,username,password_hash,git_username,role,enabled) values (1,'owner','fixture','owner','USER',true)");
    }

    @AfterEach void cleanup() {
        if (meters != null) meters.close();
        if (admin != null && schema != null && schema.matches("telemetry_test_[0-9a-f]{32}")) {
            admin.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test void aggregatesAllStatesExactDelayBoundaryAndLatestRunById() {
        project(jdbc, 10, "APPROVED");
        project(jdbc, 11, "APPROVED");
        project(jdbc, 12, "PENDING");
        project(jdbc, 13, "PAUSED");
        project(jdbc, 14, "REJECTED");
        project(jdbc, 15, "APPROVED");
        run(100, 10, "FAILED", NOW);
        run(101, 10, "SUCCEEDED", NOW.minusSeconds(60)); // ID, not wall-clock order.
        run(102, 11, "RUNNING", NOW);
        run(103, 13, "FAILED", NOW);
        request(jdbc, 10, "QUEUED", NOW.minusSeconds(7200), null);
        request(jdbc, 11, "RUNNING", NOW.minusSeconds(7201), 102L);
        request(jdbc, 12, "SUCCEEDED", NOW.minusSeconds(20000), null);
        request(jdbc, 13, "FAILED", NOW.minusSeconds(20000), null);
        request(jdbc, 14, "CANCELLED", NOW.minusSeconds(20000), null);
        request(jdbc, 15, "QUEUED", NOW.minusSeconds(7199), null);
        var telemetry = telemetry(jdbc);

        telemetry.refresh();

        var view = telemetry.observation();
        assertThat(view.status()).isEqualTo("READY");
        assertThat(view.projectCounts()).isEqualTo(Map.of("APPROVED", 3L, "PENDING", 1L, "PAUSED", 1L, "REJECTED", 1L));
        assertThat(view.requestCounts()).isEqualTo(Map.of("QUEUED", 2L, "RUNNING", 1L, "SUCCEEDED", 1L, "FAILED", 1L, "CANCELLED", 1L));
        assertThat(view.delayedActiveRequests()).isEqualTo(2L);
        assertThat(view.oldestActiveRequestedAt()).isEqualTo(NOW.minusSeconds(7201));
        assertThat(view.latestFailedProjects()).isEqualTo(1L);
        assertThat(meters.get("ai.reviewer.requests.delayed").gauge().value()).isEqualTo(2);
    }

    @Test void repeatableReadKeepsProjectAndRequestCountsConsistentAcrossAnotherCommittedTransaction() {
        project(jdbc, 10, "APPROVED");
        request(jdbc, 10, "QUEUED", NOW, null);
        var inserted = new AtomicBoolean();
        var observing = new JdbcTemplate(source) {
            @Override public void query(String sql, RowCallbackHandler callback) {
                execute((ConnectionCallback<Void>) connection -> {
                    assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                    assertThat(connection.isReadOnly()).isTrue();
                    return null;
                });
                assertThat(queryForObject("show transaction_read_only", String.class)).isEqualTo("on");
                super.query(sql, callback);
                if (inserted.compareAndSet(false, true)) {
                    // A distinct DataSource makes this an independent committed PostgreSQL session.
                    var otherSource = dataSource(schema);
                    var otherJdbc = new JdbcTemplate(otherSource);
                    new TransactionTemplate(new DataSourceTransactionManager(otherSource)).executeWithoutResult(status -> {
                        project(otherJdbc, 11, "APPROVED");
                        request(otherJdbc, 11, "QUEUED", NOW, null);
                    });
                }
            }
        };
        observing.setQueryTimeout(10);
        var telemetry = telemetry(observing);

        telemetry.refresh();

        assertThat(inserted).isTrue();
        assertThat(telemetry.observation().projectCounts().get("APPROVED")).isEqualTo(1L);
        assertThat(telemetry.observation().requestCounts().get("QUEUED")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from project", Long.class)).isEqualTo(2L);
        telemetry.refresh();
        assertThat(telemetry.observation().projectCounts().get("APPROVED")).isEqualTo(2L);
        assertThat(telemetry.observation().requestCounts().get("QUEUED")).isEqualTo(2L);
    }

    @Test void sqlFailureHidesBusinessMetricsAndRecoveryPublishesNewCounts() {
        project(jdbc, 10, "APPROVED");
        var telemetry = telemetry(jdbc);
        telemetry.refresh();
        assertThat(telemetry.observation().available()).isTrue();
        jdbc.execute("alter table review_request rename to temporarily_unavailable_request");
        try {
            telemetry.refresh();
            assertThat(telemetry.observation().status()).isEqualTo("FAILED");
            assertThat(telemetry.observation().observedAt()).isEqualTo(NOW);
            assertThat(telemetry.observation().projectCounts().get("APPROVED")).isEqualTo(1L);
            assertThat(meters.get("ai.reviewer.observation.available").gauge().value()).isZero();
            assertThat(meters.get("ai.reviewer.projects").tag("status", "APPROVED").gauge().value()).isNaN();
        } finally {
            jdbc.execute("alter table temporarily_unavailable_request rename to review_request");
        }
        project(jdbc, 11, "APPROVED");
        telemetry.refresh();
        assertThat(telemetry.observation().status()).isEqualTo("READY");
        assertThat(telemetry.observation().projectCounts().get("APPROVED")).isEqualTo(2L);
        assertThat(meters.get("ai.reviewer.projects").tag("status", "APPROVED").gauge().value()).isEqualTo(2);
    }

    @Test void successfulEmptyDatabaseIsDifferentFromUncollectedState() {
        var telemetry = telemetry(jdbc);
        assertThat(telemetry.observation().status()).isEqualTo("STARTING");
        assertThat(telemetry.observation().projectCounts()).isEmpty();
        telemetry.refresh();
        assertThat(telemetry.observation().status()).isEqualTo("READY");
        assertThat(telemetry.observation().projectCounts().values()).containsOnly(0L);
        assertThat(telemetry.observation().requestCounts().values()).containsOnly(0L);
        assertThat(telemetry.observation().latestFailedProjects()).isZero();
        assertThat(telemetry.observation().delayedActiveRequests()).isZero();
        assertThat(telemetry.observation().oldestActiveRequestedAt()).isNull();
    }

    private OperationsTelemetryService telemetry(JdbcTemplate selected) {
        return new OperationsTelemetryService(selected, new DataSourceTransactionManager(source), meters, 120, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void project(JdbcTemplate target, long id, String status) {
        target.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values(?,? ,?,'GITHUB','github.com',?,1,?)",
                id, "Telemetry fixture " + id, "https://github.com/fixture/telemetry-" + id, "fixture/telemetry-" + id, status);
    }

    private void run(long id, long project, String status, Instant started) {
        jdbc.update("insert into review_run(id,project_id,status,started_at) values(?,?,?,?)", id, project, status, Timestamp.from(started));
    }

    private void request(JdbcTemplate target, long project, String state, Instant requested, Long run) {
        boolean active = state.equals("QUEUED") || state.equals("RUNNING");
        target.update("insert into review_request(project_id,request_id,claim_token,state,source,requested_at,available_at,run_id,finished_at) values(?,?,?,?,'SCHEDULED',?,?,?,?)",
                project, UUID.randomUUID().toString(), state.equals("RUNNING") ? UUID.randomUUID().toString() : null,
                state, Timestamp.from(requested), Timestamp.from(requested), run, active ? null : Timestamp.from(NOW));
    }

    private DriverManagerDataSource dataSource(String selectedSchema) {
        var result = new DriverManagerDataSource();
        result.setUrl(System.getenv("TEST_DATABASE_URL"));
        result.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        result.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        if (selectedSchema != null) {
            properties.setProperty("currentSchema", selectedSchema);
            properties.setProperty("ApplicationName", selectedSchema);
        }
        result.setConnectionProperties(properties);
        return result;
    }
}
