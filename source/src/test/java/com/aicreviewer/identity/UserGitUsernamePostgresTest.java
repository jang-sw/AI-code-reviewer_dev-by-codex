package com.aicreviewer.identity;

import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.review.ReviewRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Only a new UUID schema in the explicitly selected local test DB is mutated. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class UserGitUsernamePostgresTest {
    private DriverManagerDataSource source;
    private JdbcTemplate admin, jdbc;
    private String schema, marker;
    private SchemaIdentity identity;
    private UserAccountService users;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        schema = "gitname_test_" + UUID.randomUUID().toString().replace("-", "");
        marker = "gitname-owned-" + UUID.randomUUID();
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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setTimeout(10);
        users = new UserAccountService(jdbc, new BCryptPasswordEncoder(4), new AuditEventWriter(jdbc));
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES " +
                "(1,'owner','fixture','owner-git','USER'),(2,'author','fixture','author-git','USER')," +
                "(3,'admin','fixture','admin-git','ADMIN'),(4,'other','fixture','other-git','USER')");
        jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) " +
                "VALUES(10,'Synthetic','https://github.com/fixture/example','GITHUB','github.com','fixture/example',1,'APPROVED')");
    }

    @AfterEach void cleanup() {
        if (identity != null && schema != null && schema.matches("gitname_test_[0-9a-f]{32}")) {
            assertThat(schemaIdentity()).as("Refuse cleanup if the fixture schema identity changed").isEqualTo(identity);
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentCorrectionsProtectExpectedValueAndUniqueOwnership(boolean sameTarget) throws Exception {
        try (var blocker = source.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                try (var lock = blocker.createStatement(); var rows = lock.executeQuery("SELECT id FROM app_user WHERE id IN (2,4) ORDER BY id FOR UPDATE")) {
                    while (rows.next()) rows.getLong(1);
                }
                var first = executor.submit(() -> correct(2, "author-git", "shared-new-name"));
                var second = executor.submit(() -> correct(sameTarget ? 2 : 4, sameTarget ? "author-git" : "other-git",
                        sameTarget ? "second-new-name" : "shared-new-name"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waiters = 0;
                while (System.nanoTime() < deadline && waiters < 2) {
                    waiters = admin.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name=? " +
                            "AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM app_user WHERE id = %FOR UPDATE'", Integer.class, schema);
                    if (waiters < 2) Thread.sleep(20);
                }
                assertThat(waiters).as("Both independent correction transactions reached the target row locks").isEqualTo(2);
                blocker.commit();
                assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='USER_GIT_USERNAME_CHANGED'", Long.class)).isEqualTo(1);
                if (sameTarget) {
                    assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=2", String.class))
                            .isIn("shared-new-name", "second-new-name");
                    assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=4", String.class)).isEqualTo("other-git");
                } else {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE git_username='shared-new-name'", Long.class)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE git_username IN ('author-git','other-git')", Long.class)).isEqualTo(1);
                }
            } finally { blocker.rollback(); executor.shutdownNow(); }
        }
    }

    @Test void auditConstraintFailureRollsBackTheGitNameAndEveryAccountField() {
        var before = jdbc.queryForMap("SELECT * FROM app_user WHERE id=2");
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT refuse_correction CHECK(action <> 'USER_GIT_USERNAME_CHANGED')");
        assertThatThrownBy(() -> correct(2, "author-git", "corrected")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM app_user WHERE id=2")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @Test void administratorDisabledWhileWaitingForTheTargetLockCannotWriteOrAudit() throws Exception {
        try (var blocker = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var lock = blocker.createStatement(); var rows = lock.executeQuery("SELECT id FROM app_user WHERE id=2 FOR UPDATE")) {
                    assertThat(rows.next()).isTrue();
                }
                var pending = executor.submit(() -> {
                    try { return correct(2, "author-git", "forbidden-correction"); }
                    catch (ResponseStatusException denied) { return denied.getStatusCode().value(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waiters = 0;
                while (System.nanoTime() < deadline && waiters == 0) {
                    waiters = admin.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name=? " +
                            "AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM app_user WHERE id = %FOR UPDATE'", Integer.class, schema);
                    if (waiters == 0) Thread.sleep(20);
                }
                assertThat(waiters).as("The correction passed its initial role check and is waiting for the target").isEqualTo(1);
                jdbc.update("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=3");
                blocker.commit();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(401);
                assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=2", String.class)).isEqualTo("author-git");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
            } finally { blocker.rollback(); executor.shutdownNow(); }
        }
    }

    @Test void assignmentResolvedBeforeCorrectionCommitsKeepsItsChosenUserButLaterReadsSeeNewName() throws Exception {
        CountDownLatch matched = new CountDownLatch(1), release = new CountDownLatch(1);
        JdbcTemplate held = new JdbcTemplate(source) {
            @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... arguments) {
                List<T> result = super.queryForList(sql, type, arguments);
                if (sql.contains("lower(git_username)")) {
                    matched.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic assignment release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Synthetic assignment was interrupted", interrupted);
                    }
                }
                return result;
            }
        };
        held.setQueryTimeout(10);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var pending = executor.submit(() -> save(new ReviewRepository(held), "a", "author-git"));
                assertThat(matched.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(correct(2, "author-git", "corrected")).isEqualTo(200);
                release.countDown();
                pending.get(5, TimeUnit.SECONDS);
                save(new ReviewRepository(jdbc), "b", "author-git");
                save(new ReviewRepository(jdbc), "c", "corrected");
                assertThat(jdbc.queryForList("SELECT assignee_id FROM review_issue ORDER BY id", Long.class)).containsExactly(2L, 1L, 2L);
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    private int correct(long target, String expected, String replacement) {
        try {
            transactions.executeWithoutResult(status -> users.changeGitUsername("admin", target, expected, replacement));
            return 200;
        } catch (UserAccountService.GitUsernameChangeException conflict) { return conflict.status().value(); }
    }

    private void save(ReviewRepository reviews, String digit, String author) {
        transactions.executeWithoutResult(status -> {
            long run = reviews.startRun(10, null, Instant.now());
            reviews.persistCommit(run, reviews.project(10, false), null,
                    new GitCommit(digit.repeat(40), author, "fixture", "diff"),
                    new ReviewResult("fixture", List.of(new ReviewFinding("LOW", "fixture", "a.txt", 1, "fixture", "fixture"))), Instant.now());
            reviews.finishRun(run, true, Instant.now(), null);
        });
    }

    private DriverManagerDataSource dataSource(String namespace) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        dataSource.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=8000");
        if (namespace != null) {
            properties.setProperty("currentSchema", namespace);
            properties.setProperty("ApplicationName", namespace);
        }
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
