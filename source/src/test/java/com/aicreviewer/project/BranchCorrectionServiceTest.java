package com.aicreviewer.project;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ProjectReviewLock;
import com.aicreviewer.review.ReviewTestDatabase;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class BranchCorrectionServiceTest {
    private static final String CURSOR = "a".repeat(40);
    private static final String REASON = "등록 시 브랜치 이름을 잘못 입력했습니다";
    private ReviewTestDatabase db;
    private BranchCorrectionService service;
    private ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        var audit = new AuditEventWriter(db.jdbc);
        var users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), audit);
        locks = mock(ProjectReviewLock.class);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(anyLong())).thenReturn(Optional.of(lease));
        service = new BranchCorrectionService(db.jdbc, users, audit, locks, db.transactionManager);
        db.jdbc.update("UPDATE project SET status='PENDING',review_branch='mian',next_review_at=CURRENT_TIMESTAMP WHERE id=10");
    }

    @AfterEach void cleanup() { db.close(); }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void correctsOnlyBranchAndDueTimeBeforeExecutionWithoutApproving(String state) {
        db.jdbc.update("UPDATE project SET status=? WHERE id=10", state);
        var original = db.jdbc.queryForMap("SELECT * FROM project WHERE id=10");
        for (String mutable : List.of("review_branch", "last_reviewed_sha", "next_review_at", "updated_at")) original.remove(mutable);
        correct("mian", "", " main ");
        assertThat(db.jdbc.queryForMap("SELECT * FROM project WHERE id=10"))
                .containsAllEntriesOf(original).containsEntry("review_branch", "main")
                .containsEntry("last_reviewed_sha", null).containsEntry("next_review_at", null);
        assertThat(db.count("review_run")).isZero();
        assertThat(db.count("review_request")).isZero();
        assertThat(db.jdbc.queryForMap("SELECT actor_id,action,target_type,target_id,detail FROM audit_event"))
                .containsEntry("actor_id", 3L).containsEntry("action", "PROJECT_REVIEW_BRANCH_CORRECTED")
                .containsEntry("target_type", "PROJECT").containsEntry("target_id", 10L)
                .containsEntry("detail", "oldBranch=mian; newBranch=main; oldSha=(none); reason=" + REASON);
        verify(lease).close();
    }

    @Test void pausedCorrectionPreservesEveryHistoryRowAndTerminalRequestWhileResettingCheckpoint() {
        pausedHistory();
        var before = history();
        var original = db.jdbc.queryForMap("SELECT * FROM project WHERE id=10");
        for (String mutable : List.of("review_branch", "last_reviewed_sha", "next_review_at", "updated_at")) original.remove(mutable);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.cursor()).isNull();
            assertThat(db.count("audit_event")).isEqualTo(1);
            return null;
        }).when(lease).close();
        correct("mian", CURSOR, "release/v2");
        assertThat(db.jdbc.queryForMap("SELECT * FROM project WHERE id=10"))
                .containsAllEntriesOf(original).containsEntry("review_branch", "release/v2").containsEntry("next_review_at", null);
        assertThat(history()).isEqualTo(before);
        assertThat(db.jdbc.queryForObject("SELECT detail FROM audit_event", String.class)).contains("oldSha=" + CURSOR);
        verify(lease).close();
    }

    @Test void emptyBranchSelectsTheRepositoryDefaultAndCanLaterBeCorrectedToAnExplicitBranch() {
        correct("mian", "", "   ");
        assertThat(branch()).isNull();
        correct("", "", "main");
        assertThat(branch()).isEqualTo("main");
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class))
                .containsExactly("oldBranch=mian; newBranch=(none); oldSha=(none); reason=" + REASON,
                        "oldBranch=(none); newBranch=main; oldSha=(none); reason=" + REASON);
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "author", "other"})
    void ordinaryAccountsCannotCorrectOrDiscoverInputValidation(String username) {
        assertStatus(403, () -> service.correct(username, 10, "mian", "", "bad branch", "", false));
        assertThat(branch()).isEqualTo("mian");
        verifyNoInteractions(locks, lease);
    }

    @Test void busyLeaseCannotChangeProjectOrHistory() {
        pausedHistory();
        var project = db.jdbc.queryForMap("SELECT * FROM project WHERE id=10");
        var history = history();
        when(locks.tryAcquire(10)).thenReturn(Optional.empty());
        assertStatus(409, () -> correct("mian", CURSOR, "main"));
        assertThat(db.jdbc.queryForMap("SELECT * FROM project WHERE id=10")).isEqualTo(project);
        assertThat(history()).isEqualTo(history);
        verifyNoInteractions(lease);
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "RUNNING"})
    void activeRequestBlocksCorrectionEvenWithAFreeAdvisoryLease(String state) {
        db.jdbc.update("UPDATE project SET status='PAUSED' WHERE id=10");
        db.jdbc.update("INSERT INTO review_run(id,project_id,status) VALUES(40,10,'RUNNING')");
        if ("QUEUED".equals(state)) {
            db.jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at) " +
                    "VALUES(10,'00000000-0000-0000-0000-000000000010','QUEUED','MANUAL',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        } else {
            db.jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at,claim_token,run_id) " +
                    "VALUES(10,'00000000-0000-0000-0000-000000000010','RUNNING','MANUAL',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'old-token',40)");
        }
        var before = history();
        assertThatThrownBy(() -> correct("mian", "", "main")).isInstanceOfSatisfying(BranchCorrectionException.class,
                error -> assertThat(error.safeMessage()).contains("요청이 남아"));
        assertThat(branch()).isEqualTo("mian");
        assertThat(history()).isEqualTo(before);
        assertThat(db.count("audit_event")).isZero();
        verify(lease).close();
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED", "APPROVED"})
    void executedOrApprovedProjectsMustBePausedBeforeCorrection(String state) {
        pausedHistory();
        db.jdbc.update("UPDATE project SET status=? WHERE id=10", state);
        assertStatus(409, () -> correct("mian", CURSOR, "main"));
        assertThat(branch()).isEqualTo("mian");
        assertThat(db.cursor()).isEqualTo(CURSOR);
        assertThat(db.count("audit_event")).isZero();
    }

    @Test void evenFailedExecutionWithoutSavedCommitMakesPendingProjectIneligible() {
        db.jdbc.update("INSERT INTO review_run(project_id,status) VALUES(10,'FAILED')");
        assertStatus(409, () -> correct("mian", "", "main"));
        assertThat(branch()).isEqualTo("mian");
    }

    @Test void staleBranchCursorAndRepeatedSubmissionNeverResetNewConfiguration() {
        pausedHistory();
        assertStatus(409, () -> correct("old", CURSOR, "main"));
        assertStatus(409, () -> correct("mian", "b".repeat(40), "main"));
        assertStatus(409, () -> correct("mian", CURSOR, "mian"));
        assertThat(db.cursor()).isEqualTo(CURSOR);
        correct("mian", CURSOR, "main");
        assertStatus(409, () -> correct("mian", CURSOR, "release"));
        assertThat(branch()).isEqualTo("main");
        assertThat(db.count("audit_event")).isEqualTo(1);
    }

    @Test void inputErrorsAreBoundedSafeAndDoNotTakeTheLease() {
        for (String branch : new String[] {"bad branch", "a..b", ".hidden", "refs/a.lock", "x".repeat(256), "refs//x", "@", "main\nprivate"}) {
            assertStatus(400, () -> correct("mian", "", branch));
        }
        for (String reason : new String[] {null, "four", "  four  ", "x".repeat(501), "private\nreason"}) {
            assertStatus(400, () -> service.correct("admin", 10, "mian", "", "main", reason, true));
        }
        assertStatus(400, () -> service.correct("admin", 10, "mian", "", "main", REASON, false));
        assertStatus(400, () -> correct("mian", "private-value", "main"));
        assertStatus(400, () -> correct("x".repeat(256), "", "main"));
        assertThat(branch()).isEqualTo("mian");
        assertThat(db.count("audit_event")).isZero();
        verifyNoInteractions(locks, lease);
    }

    @Test void maximumBranchesShaAndReasonFitTheAuditWithoutTruncation() {
        String old = "a".repeat(255), next = "b".repeat(255), cursor = "c".repeat(64), reason = "가".repeat(500);
        db.jdbc.update("UPDATE project SET status='PAUSED',review_branch=?,last_reviewed_sha=? WHERE id=10", old, cursor);
        service.correct("admin", 10, old, cursor, next, reason, true);
        assertThat(db.jdbc.queryForObject("SELECT detail FROM audit_event", String.class))
                .isEqualTo("oldBranch=" + old + "; newBranch=" + next + "; oldSha=" + cursor + "; reason=" + reason);
    }

    @Test void auditFailureRollsBackBranchCursorAndScheduleBeforeReleasingLease() {
        pausedHistory();
        var before = db.jdbc.queryForMap("SELECT * FROM project WHERE id=10");
        var history = history();
        db.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_branch_fixture CHECK(action <> 'PROJECT_REVIEW_BRANCH_CORRECTED')");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(db.jdbc.queryForMap("SELECT * FROM project WHERE id=10")).isEqualTo(before);
            return null;
        }).when(lease).close();
        assertThatThrownBy(() -> correct("mian", CURSOR, "main")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(history()).isEqualTo(history);
        assertThat(db.count("audit_event")).isZero();
        verify(lease).close();
    }

    @Test void projectAndAdminAreRecheckedAfterAcquiringTheLease() {
        when(locks.tryAcquire(10)).thenAnswer(invocation -> {
            db.jdbc.update("UPDATE project SET status='APPROVED' WHERE id=10");
            return Optional.of(lease);
        });
        assertStatus(409, () -> correct("mian", "", "main"));
        when(locks.tryAcquire(10)).thenAnswer(invocation -> {
            db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=3");
            return Optional.of(lease);
        });
        assertStatus(401, () -> correct("mian", "", "main"));
        assertThat(branch()).isEqualTo("mian");
        assertThat(db.count("audit_event")).isZero();
        verify(lease, times(2)).close();
    }

    @Test void missingProjectAndAmbientTransactionsCannotMutateOrInvertTheLockOrder() {
        assertStatus(404, () -> service.correct("admin", 99999, "mian", "", "main", REASON, true));
        verify(lease).close();
        clearInvocations(locks, lease);
        new TransactionTemplate(db.transactionManager).executeWithoutResult(tx ->
                assertThatThrownBy(() -> correct("mian", "", "main")).isInstanceOf(IllegalStateException.class));
        verifyNoInteractions(locks, lease);
    }

    private void pausedHistory() {
        db.jdbc.update("UPDATE project SET status='PAUSED',last_reviewed_sha=?,approved_at=CURRENT_TIMESTAMP WHERE id=10", CURSOR);
        db.jdbc.update("INSERT INTO review_run(id,project_id,status,reviewed_commits) VALUES(40,10,'FAILED',1)");
        db.jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,summary) VALUES(50,10,?,'Original review')", CURSOR);
        db.jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) " +
                "VALUES(100,10,50,2,'HIGH','Original issue','A.java','Original description','Original suggestion','RESOLVED')");
        db.jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_by,requested_at,available_at,run_id,finished_at,result_code) " +
                "VALUES(10,'00000000-0000-0000-0000-000000000010','FAILED','MANUAL',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,40,CURRENT_TIMESTAMP,'RATE_LIMIT_EXHAUSTED')");
    }
    private Map<String, List<Map<String, Object>>> history() {
        return Map.of("runs", db.jdbc.queryForList("SELECT * FROM review_run ORDER BY id"),
                "commits", db.jdbc.queryForList("SELECT * FROM reviewed_commit ORDER BY id"),
                "issues", db.jdbc.queryForList("SELECT * FROM review_issue ORDER BY id"),
                "requests", db.jdbc.queryForList("SELECT * FROM review_request ORDER BY project_id"));
    }
    private String branch() { return db.jdbc.queryForObject("SELECT review_branch FROM project WHERE id=10", String.class); }
    private void correct(String old, String cursor, String branch) { service.correct("admin", 10, old, cursor, branch, REASON, true); }
    private static void assertStatus(int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expected));
    }
}
