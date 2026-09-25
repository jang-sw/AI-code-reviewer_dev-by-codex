package com.aicreviewer.identity;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Destructive fixture reset is restricted to the explicitly named local, isolated test database. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_IDENTITY_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/identity_security")
class IdentityPostgresTest {
    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_IDENTITY_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        registry.add("app.bootstrap.enabled", () -> false);
        registry.add("app.review.enabled", () -> false);
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountService users;

    @Test
    void concurrentDisablesPreserveOneActiveAdministrator() throws Exception {
        // This database belongs exclusively to this test; no shared integration/UI fixtures are touched.
        jdbc.execute("TRUNCATE TABLE audit_event, review_issue, reviewed_commit, review_run, project, git_author_mapping, app_user RESTART IDENTITY");
        jdbc.update("""
                INSERT INTO app_user(username,password_hash,git_username,role,enabled)
                VALUES ('firstadmin','unused-hash','firstadmin','ADMIN',TRUE),
                       ('secondadmin','unused-hash','secondadmin','ADMIN',TRUE)
                """);
        long first = users.requireAccount("firstadmin").id();
        long second = users.requireAccount("secondadmin").id();

        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement(); var rows = statement.executeQuery(
                        "SELECT id FROM app_user WHERE role = 'ADMIN' ORDER BY id FOR UPDATE")) {
                    while (rows.next()) { rows.getLong(1); }
                }
                var firstDisable = executor.submit(disable("firstadmin", second));
                var secondDisable = executor.submit(disable("secondadmin", first));
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                int waiters = 0;
                while (System.nanoTime() < deadline && waiters < 2) {
                    waiters = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'
                              AND query LIKE '%FROM app_user WHERE role%'
                            """, Integer.class);
                    if (waiters < 2) Thread.sleep(25);
                }
                assertThat(waiters).as("both transactions passed authorization and are waiting for the same administrator locks").isEqualTo(2);
                blocker.commit();
                List<String> outcomes = List.of(firstDisable.get(5, TimeUnit.SECONDS), secondDisable.get(5, TimeUnit.SECONDS));
                assertThat(outcomes).containsExactlyInAnyOrder("disabled", "last-administrator-protected");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE", Integer.class)).isEqualTo(1);
            } finally {
                blocker.rollback();
                executor.shutdownNow();
            }
        }
    }

    private Callable<String> disable(String actor, long targetId) {
        return () -> {
            try {
                users.setEnabled(actor, targetId, false);
                return "disabled";
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("마지막 활성 관리자");
                return "last-administrator-protected";
            }
        };
    }
}
