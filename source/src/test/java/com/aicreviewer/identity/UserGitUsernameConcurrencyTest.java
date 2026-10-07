package com.aicreviewer.identity;

import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.review.ReviewRepository;
import com.aicreviewer.review.ReviewTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Separate connections exercise actual row locks; the PostgreSQL HTTP suite is the vendor check. */
class UserGitUsernameConcurrencyTest {
    private ReviewTestDatabase db;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach void setup() throws Exception {
        db = new ReviewTestDatabase();
        try (var connection = db.jdbc.getDataSource().getConnection()) {
            source = new DriverManagerDataSource(connection.getMetaData().getURL(), "sa", "");
        }
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach void close() { db.close(); }

    @Test void twoAdministratorsUsingTheSameOldValueCannotOverwriteTheFirstCommittedCorrection() throws Exception {
        CountDownLatch changed = new CountDownLatch(1), release = new CountDownLatch(1), secondLock = new CountDownLatch(1);
        AuditEventWriter heldAudit = new AuditEventWriter(jdbc) {
            @Override public void write(Long actor, String action, String targetType, Long targetId, String detail) {
                changed.countDown();
                await(release);
                super.write(actor, action, targetType, targetId, detail);
            }
        };
        UserAccountService first = service(jdbc, heldAudit);
        JdbcTemplate secondJdbc = new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper) {
                if (sql.contains("FOR UPDATE")) secondLock.countDown();
                return super.query(sql, mapper);
            }
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                if (sql.contains("FOR UPDATE")) secondLock.countDown();
                return super.query(sql, mapper, arguments);
            }
        };
        UserAccountService second = service(secondJdbc, new AuditEventWriter(secondJdbc));
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var firstResult = executor.submit(() -> transactions.executeWithoutResult(status ->
                        first.changeGitUsername("admin", 2, "author-git", "first-correction")));
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                var secondResult = executor.submit(() -> {
                    try {
                        transactions.executeWithoutResult(status -> second.changeGitUsername("admin", 2, "author-git", "second-correction"));
                        return 200;
                    } catch (UserAccountService.GitUsernameChangeException conflict) { return conflict.status().value(); }
                });
                assertThat(secondLock.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> secondResult.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                firstResult.get(5, TimeUnit.SECONDS);
                assertThat(secondResult.get(5, TimeUnit.SECONDS)).isEqualTo(409);
                assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=2", String.class)).isEqualTo("first-correction");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='USER_GIT_USERNAME_CHANGED'", Long.class)).isEqualTo(1);
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    @Test void authorAlreadyMatchedBeforeCorrectionMayFinishWithItsOriginalAssignment() throws Exception {
        CountDownLatch matched = new CountDownLatch(1), release = new CountDownLatch(1);
        JdbcTemplate heldLookup = new JdbcTemplate(source) {
            @Override public <T> List<T> queryForList(String sql, Class<T> elementType, Object... arguments) {
                List<T> result = super.queryForList(sql, elementType, arguments);
                if (elementType == Long.class && arguments.length == 1 && "author-git".equals(arguments[0])) {
                    matched.countDown(); await(release);
                }
                return result;
            }
        };
        var reviews = new ReviewRepository(heldLookup);
        var users = service(jdbc, new AuditEventWriter(jdbc));
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var persistence = executor.submit(() -> save(reviews, "a", "author-git"));
                assertThat(matched.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.executeWithoutResult(status -> users.changeGitUsername("admin", 2, "author-git", "corrected"));
                assertThat(jdbc.queryForObject("SELECT git_username FROM app_user WHERE id=2", String.class)).isEqualTo("corrected");
                release.countDown();
                persistence.get(5, TimeUnit.SECONDS);
                save(new ReviewRepository(jdbc), "b", "author-git");
                save(new ReviewRepository(jdbc), "c", "corrected");
                assertThat(jdbc.queryForList("SELECT assignee_id FROM review_issue ORDER BY id", Long.class)).containsExactly(2L, 1L, 2L);
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    private UserAccountService service(JdbcTemplate template, AuditEventWriter audit) {
        return new UserAccountService(template, new BCryptPasswordEncoder(4), audit);
    }

    private void save(ReviewRepository reviews, String digit, String author) {
        transactions.executeWithoutResult(status -> {
            long run = reviews.startRun(10, null, Instant.now());
            reviews.persistCommit(run, reviews.project(10, false), null,
                    new GitCommit(digit.repeat(40), author, "synthetic", "diff"),
                    new ReviewResult("fixture", List.of(new ReviewFinding("LOW", "fixture", "a.txt", 1, "fixture", "fixture"))), Instant.now());
            reviews.finishRun(run, true, Instant.now(), null);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for the synthetic transaction boundary");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Synthetic boundary was interrupted", interrupted);
        }
    }
}
