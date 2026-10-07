package com.aicreviewer.project;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.PostgresProjectReviewLock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real independent PostgreSQL sessions; only this fixture's generated schema is mutated. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class BranchCorrectionPostgresTest {
    private static final String CURSOR = "a".repeat(40);
    private String schema;
    private String marker;
    private SchemaIdentity identity;
    private DriverManagerDataSource source;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private long projectId;
    private BranchCorrectionService service;

    @BeforeEach void setup() {
        schema = "branch_test_" + UUID.randomUUID().toString().replace("-", "");
        marker = "branch-owned-" + UUID.randomUUID();
        admin = new JdbcTemplate(dataSource(null));
        admin.setQueryTimeout(10);
        admin.execute("CREATE SCHEMA " + schema);
        admin.execute("COMMENT ON SCHEMA " + schema + " IS '" + marker + "'");
        identity = schemaIdentity();
        assertThat(identity).isNotNull();
        assertThat(identity.owner()).isEqualTo(admin.queryForObject("SELECT current_user", String.class));
        assertThat(identity.marker()).isEqualTo(marker);
        source = dataSource(schema);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        service = service();
        // Advisory keys are database-wide: do not share the public fixtures' IDs.
        projectId = 12_884_901_888L + Integer.toUnsignedLong(UUID.randomUUID().hashCode());
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES " +
                "(1,'owner','fixture','owner','USER'),(3,'admin','fixture','admin','ADMIN')");
        jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status,review_branch,last_reviewed_sha,next_review_at) " +
                "VALUES(?,'Correction','https://github.com/fixture/branch','GITHUB','github.com','fixture/branch',1,'PAUSED','mian',?,CURRENT_TIMESTAMP)", projectId, CURSOR);
        jdbc.update("INSERT INTO review_run(id,project_id,status,reviewed_commits) VALUES(40,?,'FAILED',1)", projectId);
        jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,summary) VALUES(50,?,?,'Original review')", projectId, CURSOR);
        jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) " +
                "VALUES(100,?,50,1,'HIGH','Original issue','A.java','Original description','Original suggestion','RESOLVED')", projectId);
        jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at,run_id,finished_at,result_code) " +
                "VALUES(?,'00000000-0000-0000-0000-000000000010','FAILED','MANUAL',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,40,CURRENT_TIMESTAMP,'REVIEW_FAILED')", projectId);
    }

    @AfterEach void cleanup() {
        if (identity != null && schema != null && schema.matches("branch_test_[0-9a-f]{32}")) {
            assertThat(schemaIdentity()).as("Refuse cleanup if the fixture schema identity changed").isEqualTo(identity);
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void realLiveWorkerLeaseBlocksCorrectionWithoutChangingHistory() {
        var before = history();
        try (var lease = locks().tryAcquire(projectId).orElseThrow()) {
            assertConflict(() -> correct(service));
            assertThat(history()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT review_branch FROM project WHERE id=?", String.class, projectId)).isEqualTo("mian");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
        }
        try (var lease = locks().tryAcquire(projectId).orElseThrow()) { assertThat(lease).isNotNull(); }
    }

    @Test void simultaneousCorrectionsCommitOnceAndNeverOverwriteTheWinningBranch() throws Exception {
        var barrier = new CyclicBarrier(2);
        var before = history();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return attempt(service(), "main"); });
            var second = workers.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return attempt(service(), "release"); });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("corrected", "conflict");
        }
        assertThat(jdbc.queryForObject("SELECT review_branch FROM project WHERE id=?", String.class, projectId)).isIn("main", "release");
        assertThat(jdbc.queryForObject("SELECT last_reviewed_sha FROM project WHERE id=?", String.class, projectId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='PROJECT_REVIEW_BRANCH_CORRECTED'", Long.class)).isEqualTo(1);
        assertThat(history()).isEqualTo(before);
    }

    @Test void lockedProjectStateIsRecheckedAfterTheAdvisoryLeaseWasAcquired() throws Exception {
        try (var blocker = source.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT id FROM project WHERE id=? FOR UPDATE")) {
                    statement.setLong(1, projectId);
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var pending = workers.submit(() -> attempt(service, "main"));
                awaitLockedQuery("SELECT status,review_branch,last_reviewed_sha FROM project");
                assertThat(locks().tryAcquire(projectId)).isEmpty();
                try (var update = blocker.prepareStatement("UPDATE project SET status='APPROVED' WHERE id=?")) {
                    update.setLong(1, projectId);
                    update.executeUpdate();
                }
                blocker.commit();
                assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo("conflict");
            } finally { blocker.rollback(); workers.shutdownNow(); }
        }
        assertThat(jdbc.queryForObject("SELECT review_branch FROM project WHERE id=?", String.class, projectId)).isEqualTo("mian");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void requestLockRecheckRejectsAnActiveRequestThatAppearsWhileCorrectionWaits() throws Exception {
        try (var blocker = source.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("SELECT project_id FROM review_request WHERE project_id=? FOR UPDATE")) {
                    statement.setLong(1, projectId);
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var pending = workers.submit(() -> attempt(service, "main"));
                awaitLockedQuery("SELECT state FROM review_request");
                assertThat(locks().tryAcquire(projectId)).isEmpty();
                try (var update = blocker.prepareStatement("UPDATE review_request SET state='QUEUED',claim_token=NULL,run_id=NULL,finished_at=NULL WHERE project_id=?")) {
                    update.setLong(1, projectId);
                    update.executeUpdate();
                }
                blocker.commit();
                assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo("conflict");
            } finally { blocker.rollback(); workers.shutdownNow(); }
        }
        assertThat(jdbc.queryForObject("SELECT review_branch FROM project WHERE id=?", String.class, projectId)).isEqualTo("mian");
        assertThat(jdbc.queryForObject("SELECT state FROM review_request WHERE project_id=?", String.class, projectId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void auditFailureRollsBackConfigurationAndPreservesAllHistoryThenReleasesLease() {
        var project = jdbc.queryForMap("SELECT * FROM project WHERE id=?", projectId);
        var history = history();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT fail_branch_audit CHECK(action <> 'PROJECT_REVIEW_BRANCH_CORRECTED')");
        assertThatThrownBy(() -> correct(service)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM project WHERE id=?", projectId)).isEqualTo(project);
        assertThat(history()).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
        try (var lease = locks().tryAcquire(projectId).orElseThrow()) { assertThat(lease).isNotNull(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void administratorDisabledDuringEitherRowLockWaitCannotCorrectOrAudit(boolean requestLock) throws Exception {
        var project = jdbc.queryForMap("SELECT * FROM project WHERE id=?", projectId);
        var history = history();
        try (var blocker = source.getConnection(); var workers = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                String lockSql = requestLock ? "SELECT project_id FROM review_request WHERE project_id=? FOR UPDATE"
                        : "SELECT id FROM project WHERE id=? FOR UPDATE";
                try (var statement = blocker.prepareStatement(lockSql)) {
                    statement.setLong(1, projectId);
                    try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var pending = workers.submit(() -> {
                    try { correct(service); return 200; }
                    catch (ResponseStatusException denied) { return denied.getStatusCode().value(); }
                });
                awaitLockedQuery(requestLock ? "SELECT state FROM review_request"
                        : "SELECT status,review_branch,last_reviewed_sha FROM project");
                assertThat(locks().tryAcquire(projectId)).isEmpty();
                jdbc.update("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=3");
                blocker.commit();
                assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(401);
            } finally { blocker.rollback(); workers.shutdownNow(); }
        }
        assertThat(jdbc.queryForMap("SELECT * FROM project WHERE id=?", projectId)).isEqualTo(project);
        assertThat(history()).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
        try (var lease = locks().tryAcquire(projectId).orElseThrow()) { assertThat(lease).isNotNull(); }
    }

    private void awaitLockedQuery(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = admin.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() " +
                    "AND application_name=? AND wait_event_type='Lock' AND query LIKE ?)", Boolean.class, schema, prefix + "%");
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(20);
        }
        fail("The isolated correction did not reach its expected row-lock boundary");
    }
    private String attempt(BranchCorrectionService correction, String branch) {
        try {
            correction.correct("admin", projectId, "mian", CURSOR, branch, "확인한 브랜치 오타를 정정합니다", true);
            return "corrected";
        } catch (BranchCorrectionException expected) {
            assertThat(expected.getStatusCode().value()).isEqualTo(409);
            return "conflict";
        }
    }
    private void correct(BranchCorrectionService correction) { correction.correct("admin", projectId, "mian", CURSOR, "main", "확인한 브랜치 오타를 정정합니다", true); }
    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    }
    private Map<String, List<Map<String, Object>>> history() {
        return Map.of("runs", jdbc.queryForList("SELECT * FROM review_run ORDER BY id"), "commits", jdbc.queryForList("SELECT * FROM reviewed_commit ORDER BY id"),
                "issues", jdbc.queryForList("SELECT * FROM review_issue ORDER BY id"), "requests", jdbc.queryForList("SELECT * FROM review_request ORDER BY project_id"));
    }
    private PostgresProjectReviewLock locks() { return new PostgresProjectReviewLock(source, 5); }
    private BranchCorrectionService service() {
        var audit = new AuditEventWriter(jdbc);
        return new BranchCorrectionService(jdbc, new UserAccountService(jdbc, new BCryptPasswordEncoder(4), audit), audit, locks(), new DataSourceTransactionManager(source));
    }
    private DriverManagerDataSource dataSource(String scope) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        dataSource.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        if (scope != null) { properties.setProperty("currentSchema", scope); properties.setProperty("ApplicationName", scope); }
        dataSource.setConnectionProperties(properties);
        return dataSource;
    }

    private SchemaIdentity schemaIdentity() {
        return admin.query("SELECT n.oid, pg_get_userbyid(n.nspowner) AS owner, obj_description(n.oid,'pg_namespace') AS marker " +
                        "FROM pg_namespace n WHERE n.nspname=?",
                (rs, row) -> new SchemaIdentity(rs.getLong("oid"), rs.getString("owner"), rs.getString("marker")), schema)
                .stream().findFirst().orElse(null);
    }

    private record SchemaIdentity(long oid, String owner, String marker) { }
}
