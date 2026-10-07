package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL in owned schemas, with independent sessions and no HTTP or real credentials. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class SharedIntegrationCooldownPostgresTest {
    private static final RateLimitedException.Service GIT = RateLimitedException.Service.GIT;
    private static final RateLimitedException.Service AI = RateLimitedException.Service.AI;
    private static final URI ENDPOINT = URI.create("https://synthetic.example.invalid/api/commits?token=synthetic-private");
    private static final URI OTHER = URI.create("https://other.example.invalid/v1/responses");
    private String schema;
    private boolean schemaCreated;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private SharedIntegrationCooldown first;
    private SharedIntegrationCooldown second;

    @BeforeEach void setup() {
        schema = "cooldown_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = jdbc(dataSource(null));
        admin.execute("create schema " + schema);
        schemaCreated = true;
        source = dataSource(schema);
        migrate(source, schema, "15");
        jdbc = jdbc(source);
        first = store(source);
        second = store(dataSource(schema));
    }

    @AfterEach void cleanup() {
        if (schemaCreated && admin != null && schema != null && schema.matches("cooldown_test_[0-9a-f]{32}")) {
            admin.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test void independentConcurrentResponsesKeepTheLongestDeadlineAndRestartKeepsIt() throws Exception {
        Instant longer = databaseTime().plusSeconds(900);
        Instant shorter = longer.minusSeconds(600);
        var start = new CountDownLatch(1);
        try (Connection blocker = source.getConnection(); var workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                lockGuard(blocker, GIT);
                var left = workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    first.onRateLimited(GIT, ENDPOINT, longer);
                    return true;
                });
                var right = workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    second.onRateLimited(GIT, ENDPOINT, shorter);
                    return true;
                });
                start.countDown();
                awaitGuardWaiters(2);
                blocker.commit();
                assertThat(left.get(8, TimeUnit.SECONDS)).isTrue();
                assertThat(right.get(8, TimeUnit.SECONDS)).isTrue();
            } finally {
                blocker.rollback();
                workers.shutdownNow();
            }
        }
        assertThat(count(GIT)).isEqualTo(1);
        assertThat(deadline(GIT, ENDPOINT)).isEqualTo(longer);
        var snapshot = rows();
        SharedIntegrationCooldown restarted = store(dataSource(schema));
        assertCached(restarted, GIT, ENDPOINT, longer);
        restarted.onRateLimited(GIT, ENDPOINT, shorter);
        assertThat(rows()).isEqualTo(snapshot);
        assertThat(jdbc.queryForList("select service from integration_cooldown_guard order by service", String.class))
                .containsExactly("AI", "GIT");
    }

    @Test void persistentKeysShareCanonicalOriginsAndSeparateServiceHostSchemeAndPort() {
        Instant until = databaseTime().plusSeconds(300);
        first.onRateLimited(GIT, ENDPOINT, until);
        assertCached(second, GIT, URI.create("https://SYNTHETIC.example.invalid:443/other?secret=not-persisted"), until);
        second.beforeRequest(AI, ENDPOINT);
        second.beforeRequest(GIT, URI.create("http://synthetic.example.invalid/api"));
        second.beforeRequest(GIT, URI.create("https://synthetic.example.invalid:444/api"));
        second.beforeRequest(GIT, OTHER);
        second.onRateLimited(AI, ENDPOINT, until.plusSeconds(60));
        assertCached(first, AI, ENDPOINT, until.plusSeconds(60));
        assertCached(first, GIT, ENDPOINT, until);
        for (Map<String, Object> row : rows()) {
            assertThat(row.keySet()).containsExactlyInAnyOrder("service", "origin_hash", "retry_at");
            assertThat(row.get("origin_hash").toString()).matches("[0-9a-f]{64}");
            assertThat(row.toString()).doesNotContain("synthetic", "token", "secret", "/api", "example.invalid");
        }
    }

    @Test void guardTimeoutFailsClosedAndPreservesCommittedDeadlines() throws Exception {
        first.onRateLimited(GIT, ENDPOINT, databaseTime().plusSeconds(300));
        var before = rows();
        try (Connection blocker = source.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                lockGuard(blocker, GIT);
                var waiting = worker.submit(() -> catchThrowable(() ->
                        second.onRateLimited(GIT, ENDPOINT, databaseTime().plusSeconds(600))));
                awaitGuardWaiters(1);
                assertThat(waiting.get(8, TimeUnit.SECONDS)).isInstanceOf(DataAccessException.class)
                        .isNotInstanceOf(RateLimitedException.class);
                assertThat(rows()).isEqualTo(before);
            } finally {
                blocker.rollback();
                worker.shutdownNow();
            }
        }
        assertCached(second, GIT, ENDPOINT, deadline(GIT, ENDPOINT));
        Instant later = databaseTime().plusSeconds(900);
        second.onRateLimited(GIT, ENDPOINT, later);
        assertThat(deadline(GIT, ENDPOINT)).isEqualTo(later);
    }

    @Test void expiredCleanupAndNewOriginInsertionRollBackTogether() {
        first.onRateLimited(GIT, ENDPOINT, databaseTime().plusSeconds(300));
        first.onRateLimited(AI, ENDPOINT, databaseTime().plusSeconds(300));
        jdbc.update("update integration_cooldown set retry_at=clock_timestamp()-interval '1 second'");
        var before = rows();
        String rejectedHash = SharedIntegrationCooldown.originHash(OTHER);
        jdbc.execute("alter table integration_cooldown add constraint fixture_reject_origin check (origin_hash <> '" + rejectedHash + "')");
        try {
            assertThatThrownBy(() -> second.onRateLimited(GIT, OTHER, databaseTime().plusSeconds(300)))
                    .isInstanceOf(DataAccessException.class);
            assertThat(rows()).isEqualTo(before);
        } finally {
            jdbc.execute("alter table integration_cooldown drop constraint fixture_reject_origin");
        }
        second.onRateLimited(GIT, OTHER, databaseTime().plusSeconds(300));
        assertThat(count(GIT)).isEqualTo(1);
        assertThat(count(AI)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from integration_cooldown where service='GIT' and origin_hash=?",
                Integer.class, SharedIntegrationCooldown.originHash(ENDPOINT))).isZero();
        assertThat(jdbc.queryForMap("select * from integration_cooldown where service='AI'"))
                .isEqualTo(before.stream().filter(row -> row.get("service").equals("AI")).findFirst().orElseThrow());
    }

    @Test void tenThousandActiveOriginsNeverEvictAndAnExistingOriginCanStillExtend() {
        Instant until = databaseTime().plusSeconds(600);
        first.onRateLimited(GIT, ENDPOINT, until);
        jdbc.update("insert into integration_cooldown(service,origin_hash,retry_at) "
                + "select 'GIT',lpad(to_hex(value),64,'0'),? from generate_series(1,9999) value", Timestamp.from(until));
        assertThat(count(GIT)).isEqualTo(10000);
        String before = fingerprint();
        assertThatThrownBy(() -> second.onRateLimited(GIT, OTHER, until)).isExactlyInstanceOf(IntegrationException.class)
                .hasMessage("External service cooldown capacity exceeded").hasNoCause();
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(count(GIT)).isEqualTo(10000);
        second.onRateLimited(GIT, ENDPOINT, until.plusSeconds(300));
        assertThat(deadline(GIT, ENDPOINT)).isEqualTo(until.plusSeconds(300));
        assertThat(count(GIT)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("select count(*) from integration_cooldown where service='GIT' and retry_at=?",
                Integer.class, Timestamp.from(until))).isEqualTo(9999);
        // A full GIT scope does not consume AI capacity.
        second.onRateLimited(AI, OTHER, until);
        assertThat(count(AI)).isEqualTo(1);
    }

    @Test void guardWaitUsesFreshDatabaseTimeForExpiryAndTheMinimumNewDeadline() throws Exception {
        first.onRateLimited(GIT, ENDPOINT, databaseTime().plusSeconds(300));
        try (Connection blocker = source.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                lockGuard(blocker, GIT);
                jdbc.update("update integration_cooldown set retry_at=clock_timestamp()+interval '1 second'");
                Instant requestedBeforeWait = databaseTime().minusSeconds(1);
                var waiting = worker.submit(() -> {
                    second.onRateLimited(GIT, OTHER, requestedBeforeWait);
                    return true;
                });
                awaitGuardWaiters(1);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(() ->
                        jdbc.queryForObject("select bool_and(retry_at<=clock_timestamp()) from integration_cooldown", Boolean.class));
                Instant releasedAt = databaseTime();
                blocker.commit();
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isTrue();
                Instant completedAt = databaseTime();
                assertThat(deadline(GIT, OTHER)).isBetween(releasedAt.plusSeconds(30), completedAt.plusSeconds(30));
            } finally {
                blocker.rollback();
                worker.shutdownNow();
            }
        }
        assertThat(count(GIT)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select origin_hash from integration_cooldown where service='GIT'", String.class))
                .isEqualTo(SharedIntegrationCooldown.originHash(OTHER));
    }

    @Test void publishedCooldownSurvivesAnOuterApplicationTransactionRollback() {
        Instant until = databaseTime().plusSeconds(300);
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.setTimeout(10);
        outer.executeWithoutResult(status -> {
            first.onRateLimited(AI, ENDPOINT, until);
            status.setRollbackOnly();
        });
        assertCached(second, AI, ENDPOINT, until);
    }

    @Test void missingStorageFailsAdmissionWithoutChangingPersistedState() {
        Instant until = databaseTime().plusSeconds(300);
        first.onRateLimited(GIT, ENDPOINT, until);
        var before = rows();
        jdbc.execute("alter table integration_cooldown rename to fixture_unavailable_cooldown");
        try {
            assertThatThrownBy(() -> second.beforeRequest(GIT, ENDPOINT)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> second.onRateLimited(GIT, ENDPOINT, until.plusSeconds(300)))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("alter table fixture_unavailable_cooldown rename to integration_cooldown");
        }
        assertThat(rows()).isEqualTo(before);
        assertCached(second, GIT, ENDPOINT, until);
    }

    @Test void v14UpgradePreservesEveryExistingRequestStateAndAddsOnlyEmptyRetryDefaults() {
        String previousSchema = "cooldown_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + previousSchema);
        try {
            DriverManagerDataSource oldSource = dataSource(previousSchema);
            migrate(oldSource, previousSchema, "14");
            JdbcTemplate old = jdbc(oldSource);
            long owner = old.queryForObject("insert into app_user(username,password_hash,git_username,role) "
                    + "values('cooldown-owner','non-authenticating-fixture','cooldown-owner','USER') returning id", Long.class);
            for (String state : List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")) {
                long project = old.queryForObject("insert into project(name,repository_url,provider,repository_host,repository_path,owner_id,status,last_reviewed_sha) "
                        + "values(?,?,'GITHUB','github.com',?,?,'APPROVED',?) returning id", Long.class,
                        state, "https://github.com/fixture/" + state.toLowerCase(java.util.Locale.ROOT),
                        "fixture/" + state.toLowerCase(java.util.Locale.ROOT), owner, "a".repeat(40));
                boolean running = state.equals("RUNNING");
                boolean queued = state.equals("QUEUED");
                Long run = queued ? null : old.queryForObject("insert into review_run(project_id,status,reviewed_commits,progress_stage,"
                        + "progress_updated_at,last_saved_at,finished_at) values(?,?,1,'REVIEWING',current_timestamp,current_timestamp,?) returning id",
                        Long.class, project, running ? "RUNNING" : state.equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED",
                        running ? null : Timestamp.from(Instant.now()));
                old.update("insert into review_request(project_id,request_id,claim_token,state,source,requested_by,requested_at,available_at,"
                        + "last_attempt_at,attempt_count,run_id,finished_at,result_code) values(?,?,?,?,'MANUAL',?,current_timestamp,current_timestamp,"
                        + "?, ?, ?, ?, ?)", project, UUID.randomUUID().toString(), queued ? null : UUID.randomUUID().toString(), state, owner,
                        queued ? null : Timestamp.from(Instant.now()), queued ? 0 : 2, run,
                        queued || running ? null : Timestamp.from(Instant.now()), queued || running ? null : "LEGACY_RESULT");
            }
            var requestRows = old.queryForList("select * from review_request order by project_id");
            var projects = old.queryForList("select * from project order by id");
            var runs = old.queryForList("select * from review_run order by id");
            var history = old.queryForList("select * from flyway_schema_history order by installed_rank");
            migrate(oldSource, previousSchema, "15");
            var upgradedRows = old.queryForList("select * from review_request order by project_id");
            assertThat(upgradedRows).hasSize(5);
            for (int index = 0; index < upgradedRows.size(); index++) {
                var legacy = new LinkedHashMap<>(upgradedRows.get(index));
                assertThat(legacy.remove("rate_limit_count")).isEqualTo(0);
                assertThat(legacy.remove("rate_limited_at")).isNull();
                assertThat(legacy).isEqualTo(requestRows.get(index));
            }
            assertThat(old.queryForList("select * from project order by id")).isEqualTo(projects);
            assertThat(old.queryForList("select * from review_run order by id")).isEqualTo(runs);
            assertThat(old.queryForList("select * from flyway_schema_history where installed_rank<=14 order by installed_rank")).isEqualTo(history);
            assertThat(old.queryForList("select service from integration_cooldown_guard order by service", String.class)).containsExactly("AI", "GIT");
            assertThat(old.queryForObject("select count(*) from integration_cooldown", Integer.class)).isZero();
            assertThat(old.queryForObject("select count(*) from review_request q join project p on p.id=q.project_id join app_user u on u.id=q.requested_by",
                    Integer.class)).isEqualTo(5);
            assertThatThrownBy(() -> old.update("update review_request set rate_limit_count=1")).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> old.update("update review_request set rate_limit_count=7,rate_limited_at=clock_timestamp()"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(old.queryForList("select * from review_request order by project_id")).isEqualTo(upgradedRows);
        } finally {
            if (previousSchema.matches("cooldown_test_[0-9a-f]{32}")) admin.execute("drop schema " + previousSchema + " cascade");
        }
    }

    private static void assertCached(SharedIntegrationCooldown store, RateLimitedException.Service service, URI uri, Instant until) {
        assertThatThrownBy(() -> store.beforeRequest(service, uri)).isInstanceOfSatisfying(RateLimitedException.class, error -> {
            assertThat(error.service()).isEqualTo(service);
            assertThat(error.actualResponse()).isFalse();
            assertThat(error.retryAt()).isEqualTo(until);
        }).hasNoCause();
    }

    private List<Map<String, Object>> rows() { return jdbc.queryForList("select * from integration_cooldown order by service,origin_hash"); }
    private int count(RateLimitedException.Service service) {
        return jdbc.queryForObject("select count(*) from integration_cooldown where service=?", Integer.class, service.name());
    }
    private Instant deadline(RateLimitedException.Service service, URI uri) {
        return jdbc.queryForObject("select retry_at from integration_cooldown where service=? and origin_hash=?", Timestamp.class,
                service.name(), SharedIntegrationCooldown.originHash(uri)).toInstant();
    }
    private String fingerprint() {
        return jdbc.queryForObject("select md5(string_agg(service||origin_hash||retry_at::text,E'\\n' order by service,origin_hash)) from integration_cooldown", String.class);
    }
    private Instant databaseTime() { return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant(); }

    private void awaitGuardWaiters(int count) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                assertThat(admin.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and application_name=? and wait_event_type='Lock' and query like '%integration_cooldown_guard%'",
                        Integer.class, schema)).isEqualTo(count));
    }
    private static void lockGuard(Connection connection, RateLimitedException.Service service) throws Exception {
        try (var statement = connection.prepareStatement("select service from integration_cooldown_guard where service=? for update")) {
            statement.setString(1, service.name());
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }
    private static SharedIntegrationCooldown store(DriverManagerDataSource source) {
        return new SharedIntegrationCooldown(jdbc(source), new DataSourceTransactionManager(source));
    }
    private static JdbcTemplate jdbc(DriverManagerDataSource source) {
        var result = new JdbcTemplate(source);
        result.setQueryTimeout(10);
        return result;
    }
    private static void migrate(DriverManagerDataSource source, String schema, String version) {
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target(version).load().migrate();
    }
    private static DriverManagerDataSource dataSource(String selectedSchema) {
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
