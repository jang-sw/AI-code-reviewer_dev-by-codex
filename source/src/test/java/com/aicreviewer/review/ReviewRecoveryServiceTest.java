package com.aicreviewer.review;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ReviewRecoveryServiceTest {
    private static final String URL = "https://github.com/org/sample";
    private static final String CURSOR = "a".repeat(40);
    private static final String REASON = "강제 푸시로 브랜치 이력 재작성 확인";
    private ReviewTestDatabase db;
    private ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;
    private ReviewRecoveryService recovery;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        var audit = new AuditEventWriter(db.jdbc);
        var users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), audit);
        locks = mock(ProjectReviewLock.class);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(anyLong())).thenReturn(Optional.of(lease));
        recovery = new ReviewRecoveryService(db.jdbc, users, audit, locks, db.transactionManager);
        db.jdbc.update("UPDATE project SET status='PAUSED',last_reviewed_sha=? WHERE id=10", CURSOR);
        db.jdbc.update("INSERT INTO review_run(id,project_id,status,reviewed_commits,error_message) VALUES(40,10,'FAILED',1,'Prior failure')");
        db.jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,author_login,summary) VALUES(50,10,?,'author-git','Existing review')", CURSOR);
        db.jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) VALUES(100,10,50,2,'HIGH','Existing issue','A.java','Original description','Original suggestion','RESOLVED')");
    }

    @AfterEach void cleanup() { db.close(); }

    @Test
    void clearsOnlyCheckpointAndPreservesAllReviewIssueAndProjectData() {
        var history = history();
        var project = db.jdbc.queryForMap("SELECT * FROM project WHERE id=10");
        project.remove("last_reviewed_sha");
        project.remove("updated_at");
        reset();
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForMap("SELECT * FROM project WHERE id=10")).containsAllEntriesOf(project);
        assertThat(history()).isEqualTo(history);
        assertThat(db.jdbc.queryForMap("SELECT actor_id, action, target_type, target_id, detail FROM audit_event"))
                .containsEntry("actor_id", 3L).containsEntry("action", "PROJECT_REVIEW_PROGRESS_RESET")
                .containsEntry("target_type", "PROJECT").containsEntry("target_id", 10L)
                .containsEntry("detail", "oldSha=" + CURSOR + "; reason=" + REASON);
        verify(lease).close();
    }

    @Test
    void commitCompletesBeforeTheReviewLeaseIsReleased() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.cursor()).isNull();
            assertThat(db.count("audit_event")).isEqualTo(1);
            return null;
        }).when(lease).close();
        reset();
        verify(lease).close();
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "author", "other"})
    void onlyAnAdministratorCanUseTheServiceEvenWithCorrectConfirmation(String username) {
        assertStatus(403, () -> recovery.resetProgress(username, 10, URL, CURSOR, REASON));
        unchanged();
        verifyNoInteractions(locks, lease);
    }

    @Test
    void busyReviewReturnsConflictWithoutChangingAnyPersistedData() {
        when(locks.tryAcquire(10)).thenReturn(Optional.empty());
        var before = history();
        assertStatus(409, this::reset);
        unchanged();
        assertThat(history()).isEqualTo(before);
        verifyNoInteractions(lease);
    }

    @ParameterizedTest @ValueSource(strings = {"APPROVED", "PENDING", "REJECTED"})
    void projectMustBePausedAtTheLockedRecheck(String status) {
        db.jdbc.update("UPDATE project SET status=? WHERE id=10", status);
        assertStatus(409, this::reset);
        assertThat(db.cursor()).isEqualTo(CURSOR);
        assertThat(db.jdbc.queryForObject("SELECT status FROM project WHERE id=10", String.class)).isEqualTo(status);
        assertThat(db.count("audit_event")).isZero();
        verify(lease).close();
    }

    @Test
    void staleOrMissingCheckpointCannotBeReset() {
        db.jdbc.update("UPDATE project SET last_reviewed_sha=NULL WHERE id=10");
        assertStatus(409, this::reset);
        assertThat(db.cursor()).isNull();
        db.jdbc.update("UPDATE project SET last_reviewed_sha=? WHERE id=10", "b".repeat(40));
        assertStatus(409, this::reset);
        assertThat(db.cursor()).isEqualTo("b".repeat(40));
        assertThat(db.count("audit_event")).isZero();
        verify(lease, times(2)).close();
    }

    @ParameterizedTest @ValueSource(strings = {"https://github.com/org/sample.git", " https://github.com/org/sample", "https://github.com/Org/sample", "https://secret-fixture@github.com/org/sample"})
    void repositoryConfirmationMustMatchStoredUrlExactlyAndErrorsDoNotEchoIt(String url) {
        assertThatThrownBy(() -> recovery.resetProgress("admin", 10, url, CURSOR, REASON))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(400);
                    assertThat(error.getMessage()).doesNotContain(url, "secret-fixture");
                });
        unchanged();
        verify(lease).close();
    }

    @Test
    void malformedInputIsRejectedBeforeTakingTheLock() {
        for (String reason : new String[] {null, "", "    ", "four", "  four  ", "valid\nreason", "valid\u0000reason", "valid\u007freason", "x".repeat(501)}) {
            assertStatus(400, () -> recovery.resetProgress("admin", 10, URL, CURSOR, reason));
        }
        for (String cursor : new String[] {null, "", "main", "A".repeat(40), "a".repeat(41), CURSOR + "\n"}) {
            assertStatus(400, () -> recovery.resetProgress("admin", 10, URL, cursor, REASON));
        }
        for (String url : new String[] {null, "", URL + "\n", "x".repeat(2049)}) {
            assertStatus(400, () -> recovery.resetProgress("admin", 10, url, CURSOR, REASON));
        }
        assertStatus(400, () -> recovery.resetProgress("admin", 0, URL, CURSOR, REASON));
        unchanged();
        verifyNoInteractions(locks, lease);
    }

    @ParameterizedTest @ValueSource(ints = {5, 500})
    void acceptsTheDocumentedReasonBoundaries(int length) {
        recovery.resetProgress("admin", 10, URL, CURSOR, "가".repeat(length));
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForObject("SELECT detail FROM audit_event", String.class))
                .isEqualTo("oldSha=" + CURSOR + "; reason=" + "가".repeat(length));
    }

    @Test
    void supportsFullSha256CheckpointsWithoutAbbreviatingTheAudit() {
        String sha256 = "c".repeat(64);
        db.jdbc.update("UPDATE project SET last_reviewed_sha=? WHERE id=10", sha256);
        recovery.resetProgress("admin", 10, URL, sha256, REASON);
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForObject("SELECT detail FROM audit_event", String.class)).contains(sha256);
    }

    @Test
    void auditFailureRollsBackTheCheckpointAndClosesLeaseAfterRollback() {
        var before = history();
        db.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_reset_audit CHECK(action <> 'PROJECT_REVIEW_PROGRESS_RESET')");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            unchanged();
            return null;
        }).when(lease).close();
        assertThatThrownBy(this::reset).isInstanceOf(DataIntegrityViolationException.class);
        unchanged();
        assertThat(history()).isEqualTo(before);
        verify(lease).close();
    }

    @Test
    void concurrentResumeBeforeLeaseAcquisitionIsRechecked() {
        when(locks.tryAcquire(10)).thenAnswer(invocation -> {
            db.jdbc.update("UPDATE project SET status='APPROVED' WHERE id=10");
            return Optional.of(lease);
        });
        assertStatus(409, this::reset);
        assertThat(db.cursor()).isEqualTo(CURSOR);
        assertThat(db.count("audit_event")).isZero();
        verify(lease).close();
    }

    @Test
    void administratorDisabledDuringLockAcquisitionCannotPerformRecovery() {
        when(locks.tryAcquire(10)).thenAnswer(invocation -> {
            db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='admin'");
            return Optional.of(lease);
        });
        assertStatus(401, this::reset);
        unchanged();
        verify(lease).close();
    }

    @Test
    void nonexistentProjectReturnsNotFoundAndReleasesLease() {
        assertStatus(404, () -> recovery.resetProgress("admin", 99999, URL, CURSOR, REASON));
        unchanged();
        verify(lease).close();
    }

    @Test
    void refusesAnOuterTransactionBeforeItCanInvertAdvisoryAndRowLockOrder() {
        new TransactionTemplate(db.transactionManager).executeWithoutResult(transaction ->
                assertThatThrownBy(this::reset).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Review recovery must start outside an existing transaction"));
        unchanged();
        verifyNoInteractions(locks, lease);
    }

    private void reset() { recovery.resetProgress("admin", 10, URL, CURSOR, REASON); }
    private void unchanged() {
        assertThat(db.cursor()).isEqualTo(CURSOR);
        assertThat(db.jdbc.queryForObject("SELECT status FROM project WHERE id=10", String.class)).isEqualTo("PAUSED");
        assertThat(db.count("audit_event")).isZero();
    }
    private Map<String, List<Map<String, Object>>> history() {
        return Map.of("runs", db.jdbc.queryForList("SELECT * FROM review_run ORDER BY id"),
                "commits", db.jdbc.queryForList("SELECT * FROM reviewed_commit ORDER BY id"),
                "issues", db.jdbc.queryForList("SELECT * FROM review_issue ORDER BY id"));
    }
    private static void assertStatus(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
