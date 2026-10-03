package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL transactions in owned UUID schemas; no public fixture reset or external calls. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class SharedAttemptStorePostgresTest {
    private static final int WINDOW = 900;
    private String schema;
    private boolean schemaCreated;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private SharedAttemptStore first;
    private SharedAttemptStore second;

    @BeforeEach void setup() {
        schema = "attempt_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = jdbc(dataSource(null));
        admin.execute("create schema " + schema);
        schemaCreated = true;
        source = dataSource(schema);
        migrate(source, schema, "14");
        jdbc = jdbc(source);
        first = store(source);
        // Independent data sources/transaction managers: no shared JVM monitor or JDBC session.
        second = store(dataSource(schema));
    }

    @AfterEach void cleanup() {
        if (schemaCreated && admin != null && schema != null && schema.matches("attempt_test_[0-9a-f]{32}")) {
            admin.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void independentNodesCannotOverspendTheAccountOrAddressBudget(boolean sameAccount) throws Exception {
        int limit = 5;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<LoginAttemptLimiter.Decision>>();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 20; i++) {
                int attempt = i;
                results.add(workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return acquire(attempt % 2 == 0 ? first : second, "LOGIN",
                            sameAccount ? "shared-account" : "account-" + attempt,
                            sameAccount ? "address-" + attempt : "shared-address",
                            sameAccount ? limit : 100, sameAccount ? 100 : limit, 100);
                }));
            }
            start.countDown();
            var decisions = new ArrayList<LoginAttemptLimiter.Decision>();
            for (var result : results) decisions.add(result.get(15, TimeUnit.SECONDS));
            assertThat(decisions.stream().filter(LoginAttemptLimiter.Decision::allowed).count()).isEqualTo(limit);
            assertThat(decisions.stream().filter(value -> !value.allowed()).map(LoginAttemptLimiter.Decision::retryAfterSeconds))
                    .allSatisfy(retry -> assertThat(retry).isBetween(1L, (long) WINDOW));
        }
        assertThat(count("LOGIN")).isEqualTo(limit + 1);
        assertThat(jdbc.queryForObject("select sum(attempt_count) from auth_attempt_bucket where scope='LOGIN'", Long.class))
                .isEqualTo(2L * limit);
        var beforeRestart = buckets();
        assertThat(acquire(store(dataSource(schema)), "LOGIN", sameAccount ? "shared-account" : "another-account",
                sameAccount ? "another-address" : "shared-address", sameAccount ? limit : 100,
                sameAccount ? 100 : limit, 100).allowed()).isFalse();
        assertThat(buckets()).isEqualTo(beforeRestart);
    }

    @Test void capacityCompetitionAdmitsOneWholePairAndNeverEvictsTheWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (Connection blocker = source.getConnection(); var workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                lockLoginGuard(blocker);
                var left = workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return acquire(first, "LOGIN", "left", "left-address", 1, 100, 2);
                });
                var right = workers.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return acquire(second, "LOGIN", "right", "right-address", 1, 100, 2);
                });
                start.countDown();
                awaitGuardWaiters(2);
                blocker.commit();
                boolean leftAllowed = left.get(10, TimeUnit.SECONDS).allowed();
                boolean rightAllowed = right.get(10, TimeUnit.SECONDS).allowed();
                assertThat(leftAllowed ^ rightAllowed).isTrue();
                assertThat(count("LOGIN")).isEqualTo(2);
                var full = buckets();
                assertThat(acquire(second, "LOGIN", "attacker", "other-address", 1, 100, 2).allowed()).isFalse();
                assertThat(acquire(first, "LOGIN", leftAllowed ? "left" : "right",
                        leftAllowed ? "left-address" : "right-address", 1, 100, 2).allowed()).isFalse();
                assertThat(buckets()).isEqualTo(full);
            } finally {
                blocker.rollback();
                workers.shutdownNow();
            }
        }
    }

    @Test void aCapacityDenialDoesNotConsumeAnExistingAccountAndKnownPairsRemainUsable() {
        assertThat(acquire(first, "LOGIN", "alice", "shared-address", 10, 100, 3).allowed()).isTrue();
        assertThat(acquire(second, "LOGIN", "bob", "shared-address", 10, 100, 3).allowed()).isTrue();
        var before = buckets();
        assertThat(acquire(first, "LOGIN", "alice", "new-address", 10, 100, 3).allowed()).isFalse();
        assertThat(buckets()).isEqualTo(before);
        assertThat(acquire(second, "LOGIN", "alice", "shared-address", 10, 100, 3).allowed()).isTrue();
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(2);
        assertThat(attempts("LOGIN", address("shared-address"))).isEqualTo(3);
        assertThat(count("LOGIN")).isEqualTo(3);
    }

    @Test void loginSuccessOnlyClearsItsAccountAndSignupHasAnIndependentTwoBucketBudget() {
        assertThat(acquire(first, "LOGIN", "alice", "one-address", 2, 2, 100).allowed()).isTrue();
        assertThat(acquire(second, "LOGIN", "alice", "one-address", 2, 2, 100).allowed()).isTrue();
        assertThat(acquire(second, "SIGNUP", "alice", "one-address", 1, 1, 2).allowed()).isTrue();
        assertThat(count("SIGNUP")).isEqualTo(2);
        assertThat(acquire(first, "SIGNUP", "someone-else", "different-address", 1, 1, 2).allowed()).isFalse();
        second.clearAccount("LOGIN", account("alice"));
        assertThat(attempts("LOGIN", address("one-address"))).isEqualTo(2);
        assertThat(acquire(first, "LOGIN", "alice", "one-address", 2, 2, 100).allowed()).isFalse();
        assertThat(acquire(second, "LOGIN", "alice", "another-address", 2, 2, 100).allowed()).isTrue();
        assertThat(acquire(first, "SIGNUP", "alice", "one-address", 1, 1, 2).allowed()).isFalse();
        assertThat(attempts("SIGNUP", account("alice"))).isEqualTo(1);
        assertThat(attempts("SIGNUP", address("one-address"))).isEqualTo(1);
    }

    @Test void databaseTimeDefinesTheFixedWindowAndDeniedRetriesNeverExtendIt() {
        Instant lower = databaseTime();
        assertThat(acquire(first, "LOGIN", "alice", "address", 1, 100, 100).allowed()).isTrue();
        Instant upper = databaseTime();
        for (var expiry : jdbc.queryForList("select expires_at from auth_attempt_bucket", Timestamp.class)) {
            assertThat(expiry.toInstant()).isBetween(lower.plusSeconds(WINDOW), upper.plusSeconds(WINDOW));
        }
        var original = buckets();
        for (int i = 0; i < 3; i++) {
            var denied = acquire(second, "LOGIN", "alice", "new-address-" + i, 1, 100, 100);
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.retryAfterSeconds()).isBetween(1L, (long) WINDOW);
        }
        assertThat(buckets()).isEqualTo(original);
        jdbc.update("update auth_attempt_bucket set expires_at=clock_timestamp() where scope='LOGIN'");
        assertThat(acquire(second, "LOGIN", "alice", "address", 1, 100, 100).allowed()).isTrue();
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(1);
        assertThat(attempts("LOGIN", address("address"))).isEqualTo(1);
    }

    @Test void scheduledCleanupOnlyRemovesExpiredBucketsAndKeepsBothGuardRows() {
        assertThat(acquire(first, "LOGIN", "expired", "expired-address", 2, 3, 100).allowed()).isTrue();
        assertThat(acquire(second, "SIGNUP", "live", "live-address", 2, 2, 100).allowed()).isTrue();
        var live = jdbc.queryForList("select * from auth_attempt_bucket where scope='SIGNUP' order by key_hash");
        var policies = policies();
        jdbc.update("update auth_attempt_bucket set expires_at=clock_timestamp() where scope='LOGIN'");
        second.purgeExpired();
        assertThat(count("LOGIN")).isZero();
        assertThat(jdbc.queryForList("select * from auth_attempt_bucket where scope='SIGNUP' order by key_hash")).isEqualTo(live);
        assertThat(policies()).isEqualTo(policies);
    }

    @Test void activePolicyMismatchFailsClosedAndAnEmptyExpiredScopeCanAdoptNewLimits() {
        assertThat(acquire(first, "LOGIN", "alice", "address", 2, 100, 100).allowed()).isTrue();
        var before = buckets();
        var policy = policies();
        for (int[] changed : List.of(new int[] {3, 100, WINDOW, 100}, new int[] {2, 101, WINDOW, 100},
                new int[] {2, 100, WINDOW + 1, 100}, new int[] {2, 100, WINDOW, 101})) {
            assertUnavailable(catchThrowable(() -> second.acquire("LOGIN", account("alice"), address("address"),
                    changed[0], changed[1], changed[2], changed[3])));
            assertThat(buckets()).isEqualTo(before);
            assertThat(policies()).isEqualTo(policy);
        }
        assertThat(acquire(second, "LOGIN", "alice", "address", 2, 100, 100).allowed()).isTrue();
        jdbc.update("update auth_attempt_bucket set expires_at=clock_timestamp() where scope='LOGIN'");
        assertThat(acquire(second, "LOGIN", "alice", "address", 3, 100, 100).allowed()).isTrue();
        assertThat(policies()).isNotEqualTo(policy);
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(1);
        var changed = buckets();
        assertUnavailable(catchThrowable(() -> acquire(first, "LOGIN", "alice", "address", 2, 100, 100)));
        assertThat(buckets()).isEqualTo(changed);
    }

    @Test void aSecondBucketWriteFailureRollsBackBothCountersAndRecoversWithoutResettingQuota() {
        assertThat(acquire(first, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        assertThat(acquire(second, "LOGIN", "expired", "expired-address", 5, 5, 100).allowed()).isTrue();
        jdbc.update("update auth_attempt_bucket set expires_at=clock_timestamp() where key_hash in (?,?)",
                account("expired"), address("expired-address"));
        var before = buckets();
        var policy = policies();
        jdbc.execute("alter table auth_attempt_bucket add constraint fixture_address_failure check "
                + "(key_hash <> '" + address("address") + "' or attempt_count < 2)");
        try {
            assertUnavailable(catchThrowable(() -> acquire(second, "LOGIN", "alice", "address", 5, 5, 100)));
            assertThat(buckets()).isEqualTo(before);
            assertThat(policies()).isEqualTo(policy);
        } finally {
            jdbc.execute("alter table auth_attempt_bucket drop constraint fixture_address_failure");
        }
        assertThat(acquire(second, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(2);
        assertThat(attempts("LOGIN", address("address"))).isEqualTo(2);
        assertThat(count("LOGIN")).isEqualTo(2);
    }

    @Test void guardLockTimeoutFailsClosedAndAReleasedDatabaseRetainsTheCommittedBudget() throws Exception {
        assertThat(acquire(first, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        var before = buckets();
        try (Connection blocker = source.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                lockLoginGuard(blocker);
                var waiting = worker.submit(() -> catchThrowable(() -> acquire(second, "LOGIN", "alice", "address", 5, 5, 100)));
                awaitGuardWaiters(1);
                assertUnavailable(waiting.get(8, TimeUnit.SECONDS));
                assertThat(buckets()).isEqualTo(before);
            } finally {
                blocker.rollback();
                worker.shutdownNow();
            }
        }
        assertThat(acquire(second, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(2);
        assertThat(attempts("LOGIN", address("address"))).isEqualTo(2);
    }

    @Test void aSqlFailureReturnsOnlyTheFixedDiagnosticAndDoesNotReplaceTheStoredBudget() {
        assertThat(acquire(first, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        var before = buckets();
        var policy = policies();
        jdbc.execute("alter table auth_attempt_bucket rename to fixture_unavailable_bucket");
        try {
            assertUnavailable(catchThrowable(() -> acquire(second, "LOGIN", "alice", "address", 5, 5, 100)));
        } finally {
            jdbc.execute("alter table fixture_unavailable_bucket rename to auth_attempt_bucket");
        }
        assertThat(buckets()).isEqualTo(before);
        assertThat(policies()).isEqualTo(policy);
        assertThat(acquire(second, "LOGIN", "alice", "address", 5, 5, 100).allowed()).isTrue();
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(2);
    }

    @Test void anExpiredWindowIsRecheckedWithDatabaseTimeAfterWaitingForTheGuard() throws Exception {
        assertThat(acquire(first, "LOGIN", "alice", "address", 1, 100, 100).allowed()).isTrue();
        try (Connection blocker = source.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                lockLoginGuard(blocker);
                // Future at waiter transaction start, expired when the same guard is released.
                jdbc.update("update auth_attempt_bucket set expires_at=clock_timestamp()+interval '1 second' where scope='LOGIN'");
                var waiting = worker.submit(() -> acquire(second, "LOGIN", "alice", "address", 1, 100, 100));
                awaitGuardWaiters(1);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(() ->
                        jdbc.queryForObject("select bool_and(expires_at<=clock_timestamp()) from auth_attempt_bucket where scope='LOGIN'", Boolean.class));
                blocker.commit();
                assertThat(waiting.get(5, TimeUnit.SECONDS).allowed()).isTrue();
            } finally {
                blocker.rollback();
                worker.shutdownNow();
            }
        }
        assertThat(attempts("LOGIN", account("alice"))).isEqualTo(1);
    }

    @Test void admittedAttemptsCommitIndependentlyOfAnOuterApplicationRollback() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.setTimeout(10);
        outer.executeWithoutResult(status -> {
            assertThat(acquire(first, "LOGIN", "alice", "address", 1, 100, 100).allowed()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(acquire(second, "LOGIN", "alice", "another-address", 1, 100, 100).allowed()).isFalse();
        assertThat(count("LOGIN")).isEqualTo(2);
    }

    @Test void v13UpgradePreservesExistingRowsAndSeedsOnlyEmptyIndependentPolicyGuards() {
        String previousSchema = "attempt_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create schema " + previousSchema);
        try {
            var previousSource = dataSource(previousSchema);
            var previous = jdbc(previousSource);
            migrate(previousSource, previousSchema, "13");
            long owner = previous.queryForObject("insert into app_user(username,password_hash,git_username,role) "
                    + "values('migration-owner','non-authenticating-fixture','migration-owner','USER') returning id", Long.class);
            long project = previous.queryForObject("insert into project(name,repository_url,provider,repository_host,repository_path,owner_id,status,"
                    + "last_reviewed_sha,next_review_at) values('Preserved project','https://github.com/fixture/preserved','GITHUB',"
                    + "'github.com','fixture/preserved',?,'APPROVED',?,current_timestamp) returning id", Long.class, owner, "a".repeat(40));
            long run = previous.queryForObject("insert into review_run(project_id,status,reviewed_commits,progress_stage,progress_updated_at,last_saved_at) "
                    + "values(?,'RUNNING',1,'REVIEWING',current_timestamp,current_timestamp) returning id", Long.class, project);
            long commit = previous.queryForObject("insert into reviewed_commit(project_id,commit_sha,author_login,summary,coverage_type,coverage_details) "
                    + "values(?,?,'migration-owner','Existing manual evidence','MANUAL_ONLY','Preserved reason') returning id", Long.class,
                    project, "a".repeat(40));
            long file = previous.queryForObject("insert into manual_review_file(reviewed_commit_id,project_id,file_path,new_object_sha,new_mode,reason_code) "
                    + "values(?,?,'fixture.bin',?,'100644','SOURCE_DIFF_UNAVAILABLE') returning id", Long.class, commit, project, "b".repeat(40));
            long issue = previous.queryForObject("insert into review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,"
                    + "status,issue_kind,manual_file_id,resolution_note) values(?,?,?,NULL,'Preserved issue','fixture.bin','Preserved evidence',"
                    + "'Inspect object','RESOLVED','MANUAL_REVIEW',?,'수동 확인 사유를 보존한다') returning id", Long.class, project, commit, owner, file);
            previous.update("insert into audit_event(actor_id,action,target_type,target_id,detail) values(?,'ISSUE_RESOLVED','REVIEW_ISSUE',?,'Preserved resolution')",
                    owner, issue);
            previous.update("insert into git_author_mapping(user_id,repository_origin,author_email) values(?,'https://github.com','fixture@example.invalid')", owner);
            previous.update("insert into review_request(project_id,request_id,claim_token,state,source,requested_by,requested_at,available_at,"
                    + "last_attempt_at,attempt_count,run_id) values(?,?,?,'RUNNING','MANUAL',?,current_timestamp,current_timestamp,current_timestamp,2,?)",
                    project, UUID.randomUUID().toString(), UUID.randomUUID().toString(), owner, run);
            var snapshots = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (String table : List.of("app_user", "project", "review_run", "reviewed_commit", "manual_review_file", "review_issue",
                    "audit_event", "git_author_mapping", "review_request")) {
                snapshots.put(table, previous.queryForList("select * from " + table + " order by 1"));
            }
            var history = previous.queryForList("select * from flyway_schema_history order by installed_rank");
            migrate(previousSource, previousSchema, "14");
            snapshots.forEach((table, rows) -> assertThat(previous.queryForList("select * from " + table + " order by 1"))
                    .as("V14 preserved legacy table %s", table).isEqualTo(rows));
            assertThat(previous.queryForList("select * from flyway_schema_history where installed_rank<=13 order by installed_rank"))
                    .isEqualTo(history);
            assertThat(previous.queryForObject("select count(*) from flyway_schema_history where version='14' and success=true", Integer.class)).isEqualTo(1);
            assertThat(previous.queryForList("select scope from auth_attempt_policy order by scope", String.class)).containsExactly("LOGIN", "SIGNUP");
            assertThat(previous.queryForObject("select count(*) from auth_attempt_policy where policy_fingerprint is null", Integer.class)).isEqualTo(2);
            assertThat(previous.queryForObject("select count(*) from auth_attempt_bucket", Integer.class)).isZero();
            assertThat(acquire(store(previousSource), "LOGIN", "migration-owner", "address", 1, 2, 2).allowed()).isTrue();
            assertThat(previous.queryForObject("select policy_fingerprint from auth_attempt_policy where scope='LOGIN'", String.class)).matches("[0-9a-f]{64}");
            assertThat(previous.queryForObject("select policy_fingerprint from auth_attempt_policy where scope='SIGNUP'", String.class)).isNull();
            long fresh = previous.queryForObject("insert into app_user(username,password_hash,git_username,role) "
                    + "values('new-owner','non-authenticating-fixture','new-owner','USER') returning id", Long.class);
            assertThat(fresh).isGreaterThan(owner);
            assertThat(previous.queryForObject("select count(*) from review_request q join review_run r on q.run_id=r.id "
                    + "join project p on p.id=q.project_id join app_user u on u.id=p.owner_id where q.run_id=? and u.id=?", Integer.class,
                    run, owner)).isEqualTo(1);
        } finally {
            if (previousSchema.matches("attempt_test_[0-9a-f]{32}")) admin.execute("drop schema " + previousSchema + " cascade");
        }
    }

    private LoginAttemptLimiter.Decision acquire(SharedAttemptStore store, String scope, String account, String address,
                                                 int accountLimit, int addressLimit, int maxEntries) {
        return store.acquire(scope, account(account), address(address), accountLimit, addressLimit, WINDOW, maxEntries);
    }

    private List<Map<String, Object>> buckets() {
        return jdbc.queryForList("select * from auth_attempt_bucket order by scope,key_hash");
    }

    private List<Map<String, Object>> policies() {
        return jdbc.queryForList("select * from auth_attempt_policy order by scope");
    }

    private int count(String scope) {
        return jdbc.queryForObject("select count(*) from auth_attempt_bucket where scope=?", Integer.class, scope);
    }

    private int attempts(String scope, String key) {
        return jdbc.queryForObject("select attempt_count from auth_attempt_bucket where scope=? and key_hash=?", Integer.class, scope, key);
    }

    private Instant databaseTime() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private void awaitGuardWaiters(int expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                assertThat(admin.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                        + "and application_name=? and wait_event_type='Lock' and query like '%auth_attempt_policy%'",
                        Integer.class, schema)).isEqualTo(expected));
    }

    private static void lockLoginGuard(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select scope from auth_attempt_policy where scope='LOGIN' for update")) {
            assertThat(rows.next()).isTrue();
        }
    }

    private static void assertUnavailable(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(AttemptStoreUnavailableException.class)
                .hasMessage("Shared authentication attempt storage is unavailable.").hasNoCause();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static SharedAttemptStore store(DriverManagerDataSource dataSource) {
        return new SharedAttemptStore(jdbc(dataSource), new DataSourceTransactionManager(dataSource));
    }

    private static JdbcTemplate jdbc(DriverManagerDataSource dataSource) {
        var result = new JdbcTemplate(dataSource);
        result.setQueryTimeout(10);
        return result;
    }

    private static void migrate(DriverManagerDataSource dataSource, String schema, String version) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(version).load().migrate();
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

    private static String account(String value) { return "account:" + digest(value); }
    private static String address(String value) { return "ip:" + digest(value); }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
