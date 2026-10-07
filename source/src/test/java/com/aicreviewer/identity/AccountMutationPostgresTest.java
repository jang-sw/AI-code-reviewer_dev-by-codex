package com.aicreviewer.identity;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real independent transactions, restricted to a newly owned schema in the local test database. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class AccountMutationPostgresTest {
    private static final String PASSWORD = "Synthetic-current-password-8194!";
    private static final String REPLACEMENT = "Synthetic-replacement-password-7012!";
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(4);
    private static final String HASH = PASSWORDS.encode(PASSWORD);
    private DriverManagerDataSource source;
    private JdbcTemplate admin, jdbc;
    private String schema, marker;
    private SchemaIdentity identity;
    private UserAccountService users;
    private GitAuthorMappingService mappings;
    private ValidatorFactory validation;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        schema = "account_mutation_test_" + UUID.randomUUID().toString().replace("-", "");
        marker = "account-mutation-owned-" + UUID.randomUUID();
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
        var audit = new AuditEventWriter(jdbc);
        users = new UserAccountService(jdbc, PASSWORDS, audit);
        validation = Validation.buildDefaultValidatorFactory();
        mappings = new GitAuthorMappingService(jdbc, users, audit, validation.getValidator(), "github.com");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role) VALUES " +
                "(1,'firstadmin',?,'first-admin-git','ADMIN'),(2,'actingadmin',?,'acting-admin-git','ADMIN')," +
                "(3,'member',?,'member-git','USER')", HASH, HASH, HASH);
        jdbc.update("INSERT INTO git_author_mapping(id,user_id,repository_origin,author_email) " +
                "VALUES(10,3,'https://github.com','synthetic@example.com')");
    }

    @AfterEach void cleanup() {
        try {
            if (identity != null && schema != null && schema.matches("account_mutation_test_[0-9a-f]{32}")) {
                assertThat(schemaIdentity()).as("Refuse to remove a schema whose identity changed").isEqualTo(identity);
                admin.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally {
            if (validation != null) validation.close();
        }
    }

    @ParameterizedTest @EnumSource(Mutation.class)
    void revocationCommittedDuringTheObservedRowLockWaitPreventsMutationAndAudit(Mutation mutation) throws Exception {
        prepare(mutation);
        Map<String, Object> expectedTarget = new LinkedHashMap<>(target());
        var mappingBefore = jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id");
        try (var blocker = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                lock(blocker, mutation);
                var pending = executor.submit(() -> {
                    try {
                        mutate(mutation);
                        return 200;
                    } catch (ResponseStatusException denied) {
                        return denied.getStatusCode().value();
                    }
                });
                awaitWaiter();
                if (mutation == Mutation.CHANGE_PASSWORD) {
                    // The actor is also the locked target. Commit the disabling write on the
                    // blocking connection; commit publishes revocation before releasing its lock.
                    try (var statement = blocker.prepareStatement("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=3")) {
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    expectedTarget.put("enabled", false);
                    expectedTarget.put("security_version", 1L);
                } else {
                    // Every administrative mutation waits before locking actor 2.
                    assertThat(jdbc.update("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=2"))
                            .isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT enabled FROM app_user WHERE id=2", Boolean.class)).isFalse();
                }
                blocker.commit();
                assertThat(pending.get(5, TimeUnit.SECONDS)).as("Revoked actor cannot finish the pending mutation").isEqualTo(401);
                assertThat(target()).isEqualTo(expectedTarget);
                assertThat(jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id")).isEqualTo(mappingBefore);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE", Long.class))
                        .isEqualTo(mutation == Mutation.CHANGE_PASSWORD ? 2L : 1L);
            } finally {
                blocker.rollback();
                executor.shutdownNow();
            }
        }
    }

    @ParameterizedTest @EnumSource(Mutation.class)
    void authorizedMutationCommitsItsIntendedChangeAndOneSafeAudit(Mutation mutation) {
        prepare(mutation);
        var expected = new LinkedHashMap<>(target());
        mutate(mutation);
        if (mutation == Mutation.SET_ENABLED) {
            expected.put("enabled", false);
            expected.put("security_version", 1L);
        } else if (mutation == Mutation.RESET_PASSWORD || mutation == Mutation.CHANGE_PASSWORD) {
            String changedHash = (String) target().get("password_hash");
            assertThat(PASSWORDS.matches(REPLACEMENT, changedHash)).isTrue();
            assertThat(PASSWORDS.matches(PASSWORD, changedHash)).isFalse();
            expected.put("password_hash", changedHash);
            expected.put("security_version", 1L);
        } else if (mutation == Mutation.CHANGE_GIT_USERNAME) {
            expected.put("git_username", "corrected-member");
        } else if (mutation == Mutation.APPROVE) {
            assertThat(target().get("approval_decided_at")).isNotNull();
            expected.put("approval_status", "APPROVED");
            expected.put("enabled", true);
            expected.put("approval_decided_at", target().get("approval_decided_at"));
            expected.put("security_version", 1L);
        }
        assertThat(target()).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM git_author_mapping", Long.class))
                .isEqualTo(mutation == Mutation.DELETE_MAPPING ? 0L : 1L);
        var events = jdbc.queryForList("SELECT actor_id,action,target_type,target_id,detail FROM audit_event");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).containsEntry("actor_id", mutation == Mutation.CHANGE_PASSWORD ? 3L : 2L)
                .containsEntry("action", mutation.action)
                .containsEntry("target_type", mutation == Mutation.DELETE_MAPPING ? "GIT_AUTHOR_MAPPING" : "USER")
                .containsEntry("target_id", mutation == Mutation.DELETE_MAPPING ? 10L : 3L);
        assertThat((String) events.getFirst().get("detail")).doesNotContain(PASSWORD, REPLACEMENT, HASH, "synthetic@example.com");
    }

    @ParameterizedTest @EnumSource(Mutation.class)
    void auditFailureRollsBackAllAccountAndMappingFields(Mutation mutation) {
        prepare(mutation);
        var accountsBefore = jdbc.queryForList("SELECT * FROM app_user ORDER BY id");
        var mappingBefore = jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id");
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_synthetic_audit CHECK (action <> '" + mutation.action + "')");
        assertThatThrownBy(() -> mutate(mutation)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT * FROM app_user ORDER BY id")).isEqualTo(accountsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id")).isEqualTo(mappingBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
    }

    @ParameterizedTest @EnumSource(value = Mutation.class, names = {"RESET_PASSWORD", "CHANGE_GIT_USERNAME", "APPROVE"})
    void administrativeMutationAndDisableUseTheSameLockOrderAndBothAuditWithoutDeadlocking(Mutation mutation) throws Exception {
        prepare(mutation);
        CountDownLatch beforeAudit = new CountDownLatch(1), releaseAudit = new CountDownLatch(1);
        var heldAudit = new AuditEventWriter(jdbc) {
            @Override public void write(Long actorId, String action, String targetType, Long targetId, String detail) {
                if (mutation.action.equals(action)) {
                    beforeAudit.countDown();
                    try {
                        if (!releaseAudit.await(8, TimeUnit.SECONDS)) throw new AssertionError("Synthetic audit release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Synthetic audit release was interrupted", interrupted);
                    }
                }
                super.write(actorId, action, targetType, targetId, detail);
            }
        };
        var firstService = new UserAccountService(jdbc, PASSWORDS, heldAudit);
        var expected = new LinkedHashMap<>(target());
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> {
                    mutate(mutation, firstService);
                    return 200;
                });
                assertThat(beforeAudit.await(5, TimeUnit.SECONDS)).as("The first mutation holds its target before the audit actor FK check").isTrue();
                var disable = executor.submit(() -> {
                    mutate(Mutation.SET_ENABLED);
                    return 200;
                });
                awaitWaiter();
                releaseAudit.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(disable.get(5, TimeUnit.SECONDS)).isEqualTo(200);
                if (mutation == Mutation.RESET_PASSWORD) {
                    String changedHash = (String) target().get("password_hash");
                    assertThat(PASSWORDS.matches(REPLACEMENT, changedHash)).isTrue();
                    expected.put("password_hash", changedHash);
                } else if (mutation == Mutation.CHANGE_GIT_USERNAME) {
                    expected.put("git_username", "corrected-member");
                } else {
                    assertThat(target().get("approval_decided_at")).isNotNull();
                    expected.put("approval_status", "APPROVED");
                    expected.put("approval_decided_at", target().get("approval_decided_at"));
                }
                expected.put("enabled", false);
                expected.put("security_version", mutation == Mutation.CHANGE_GIT_USERNAME ? 1L : 2L);
                assertThat(target()).isEqualTo(expected);
                assertThat(jdbc.queryForList("SELECT action FROM audit_event ORDER BY id", String.class))
                        .containsExactly(mutation.action, "USER_DISABLED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id=2 AND target_id=3 AND target_type='USER'", Long.class))
                        .isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role='ADMIN' AND enabled=TRUE", Long.class)).isEqualTo(2);
            } finally {
                releaseAudit.countDown();
                executor.shutdownNow();
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void creationRevokedWhileWaitingForItsFirstAdministratorCannotInsertOrAudit(boolean mapping) throws Exception {
        var accountsBefore = jdbc.queryForList("SELECT * FROM app_user WHERE id<>2 ORDER BY id");
        var mappingsBefore = jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id");
        try (var blocker = source.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT id FROM app_user WHERE id=1 FOR UPDATE")) {
                    assertThat(rows.next()).isTrue();
                }
                var pending = executor.submit(() -> {
                    try {
                        transactions.executeWithoutResult(status -> {
                            if (mapping) mappings.create("actingadmin", 3, "https://github.com", "new-synthetic@example.com");
                            else users.create("actingadmin", "newmember", REPLACEMENT, "newmember-git", "USER");
                        });
                        return 200;
                    } catch (ResponseStatusException denied) { return denied.getStatusCode().value(); }
                });
                awaitWaiter();
                assertThat(jdbc.update("UPDATE app_user SET enabled=FALSE,security_version=security_version+1 WHERE id=2")).isEqualTo(1);
                blocker.commit();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(401);
                assertThat(jdbc.queryForList("SELECT * FROM app_user WHERE id<>2 ORDER BY id")).isEqualTo(accountsBefore);
                assertThat(jdbc.queryForList("SELECT * FROM git_author_mapping ORDER BY id")).isEqualTo(mappingsBefore);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isZero();
            } finally {
                blocker.rollback();
                executor.shutdownNow();
            }
        }
    }

    @Test void mappingCreationAndAccountDisableFollowOneLockOrderAndBothFinish() throws Exception {
        CountDownLatch beforeAudit = new CountDownLatch(1), releaseAudit = new CountDownLatch(1);
        var heldAudit = heldAudit("GIT_AUTHOR_MAPPING_CREATED", beforeAudit, releaseAudit);
        var creating = new GitAuthorMappingService(jdbc, users, heldAudit, validation.getValidator(), "github.com");
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var creation = executor.submit(() -> transactions.execute(status ->
                        creating.create("actingadmin", 3, "https://github.com", "new-synthetic@example.com")));
                assertThat(beforeAudit.await(5, TimeUnit.SECONDS)).isTrue();
                var disable = executor.submit(() -> { mutate(Mutation.SET_ENABLED); return 200; });
                awaitWaiter();
                releaseAudit.countDown();
                long created = creation.get(5, TimeUnit.SECONDS);
                assertThat(disable.get(5, TimeUnit.SECONDS)).isEqualTo(200);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM git_author_mapping WHERE id IN (10,?)", Long.class, created)).isEqualTo(2);
                assertThat(target()).containsEntry("enabled", false).containsEntry("security_version", 1L).containsEntry("password_hash", HASH);
                assertThat(jdbc.queryForList("SELECT action FROM audit_event ORDER BY id", String.class))
                        .containsExactly("GIT_AUTHOR_MAPPING_CREATED", "USER_DISABLED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id=2", Long.class)).isEqualTo(2);
            } finally { releaseAudit.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void deletingAndRecreatingTheSameUniqueMappingCannotReverseTheAuditActorLock() throws Exception {
        CountDownLatch beforeAudit = new CountDownLatch(1), releaseAudit = new CountDownLatch(1);
        var deleting = new GitAuthorMappingService(jdbc, users,
                heldAudit("GIT_AUTHOR_MAPPING_DELETED", beforeAudit, releaseAudit), validation.getValidator(), "github.com");
        var accountBefore = target();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var deletion = executor.submit(() -> {
                    transactions.executeWithoutResult(status -> deleting.delete("actingadmin", 10));
                    return 200;
                });
                assertThat(beforeAudit.await(5, TimeUnit.SECONDS)).isTrue();
                var creation = executor.submit(() -> transactions.execute(status ->
                        mappings.create("actingadmin", 3, "https://github.com", "synthetic@example.com")));
                awaitWaiter();
                releaseAudit.countDown();
                assertThat(deletion.get(5, TimeUnit.SECONDS)).isEqualTo(200);
                long created = creation.get(5, TimeUnit.SECONDS);
                assertThat(created).isNotEqualTo(10L);
                assertThat(jdbc.queryForList("SELECT id FROM git_author_mapping", Long.class)).containsExactly(created);
                assertThat(target()).isEqualTo(accountBefore);
                assertThat(jdbc.queryForList("SELECT action FROM audit_event ORDER BY id", String.class))
                        .containsExactly("GIT_AUTHOR_MAPPING_DELETED", "GIT_AUTHOR_MAPPING_CREATED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE actor_id=2 AND target_type='GIT_AUTHOR_MAPPING'", Long.class)).isEqualTo(2);
            } finally { releaseAudit.countDown(); executor.shutdownNow(); }
        }
    }

    private AuditEventWriter heldAudit(String expectedAction, CountDownLatch reached, CountDownLatch release) {
        return new AuditEventWriter(jdbc) {
            @Override public void write(Long actorId, String action, String targetType, Long targetId, String detail) {
                if (expectedAction.equals(action)) {
                    reached.countDown();
                    try {
                        if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("Synthetic audit release timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Synthetic audit release was interrupted", interrupted);
                    }
                }
                super.write(actorId, action, targetType, targetId, detail);
            }
        };
    }

    private void prepare(Mutation mutation) {
        if (mutation == Mutation.APPROVE) {
            jdbc.update("UPDATE app_user SET enabled=FALSE,approval_status='PENDING' WHERE id=3");
        }
    }

    private void mutate(Mutation mutation) {
        mutate(mutation, users);
    }

    private void mutate(Mutation mutation, UserAccountService accountService) {
        transactions.executeWithoutResult(status -> {
            switch (mutation) {
                case SET_ENABLED -> accountService.setEnabled("actingadmin", 3, false);
                case RESET_PASSWORD -> accountService.resetPassword("actingadmin", 3, REPLACEMENT);
                case CHANGE_PASSWORD -> accountService.changePassword("member", PASSWORD, REPLACEMENT);
                case CHANGE_GIT_USERNAME -> accountService.changeGitUsername("actingadmin", 3, "member-git", "corrected-member");
                case APPROVE -> accountService.decideApproval("actingadmin", 3, "approve", "");
                case DELETE_MAPPING -> mappings.delete("actingadmin", 10);
            }
        });
    }

    private void lock(Connection connection, Mutation mutation) throws Exception {
        String sql = switch (mutation) {
            case SET_ENABLED, RESET_PASSWORD, CHANGE_GIT_USERNAME, APPROVE, DELETE_MAPPING -> "SELECT id FROM app_user WHERE id=1 FOR UPDATE";
            case CHANGE_PASSWORD -> "SELECT id FROM app_user WHERE id=3 FOR UPDATE";
        };
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.next()).isFalse();
        }
    }

    private void awaitWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int waiters = 0;
        while (System.nanoTime() < deadline && waiters == 0) {
            waiters = admin.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name=? " +
                    "AND wait_event_type='Lock' AND state='active'", Integer.class, schema);
            if (waiters == 0) Thread.sleep(20);
        }
        assertThat(waiters).as("Independent mutation has passed initial authorization and reached its row lock").isEqualTo(1);
    }

    private Map<String, Object> target() { return jdbc.queryForMap("SELECT * FROM app_user WHERE id=3"); }

    private DriverManagerDataSource dataSource(String namespace) {
        var result = new DriverManagerDataSource();
        result.setUrl(System.getenv("TEST_DATABASE_URL"));
        result.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        result.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=8000");
        if (namespace != null) {
            properties.setProperty("currentSchema", namespace);
            properties.setProperty("ApplicationName", namespace);
        }
        result.setConnectionProperties(properties);
        return result;
    }

    private SchemaIdentity schemaIdentity() {
        return admin.query("SELECT n.oid,pg_get_userbyid(n.nspowner) AS owner,obj_description(n.oid,'pg_namespace') AS marker " +
                        "FROM pg_namespace n WHERE n.nspname=?",
                (rs, row) -> new SchemaIdentity(rs.getLong("oid"), rs.getString("owner"), rs.getString("marker")), schema)
                .stream().findFirst().orElse(null);
    }

    private record SchemaIdentity(long oid, String owner, String marker) { }

    enum Mutation {
        SET_ENABLED("USER_DISABLED"), RESET_PASSWORD("USER_PASSWORD_RESET"),
        CHANGE_PASSWORD("PASSWORD_CHANGED"), DELETE_MAPPING("GIT_AUTHOR_MAPPING_DELETED"),
        CHANGE_GIT_USERNAME("USER_GIT_USERNAME_CHANGED"), APPROVE("USER_APPROVAL_APPROVED");
        private final String action;
        Mutation(String action) { this.action = action; }
    }
}
