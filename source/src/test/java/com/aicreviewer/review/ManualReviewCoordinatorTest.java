package com.aicreviewer.review;

import com.aicreviewer.ai.AiInputLimitException;
import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import com.aicreviewer.git.ManualReviewFile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManualReviewCoordinatorTest {
    private ReviewTestDatabase db;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ReviewCoordinator coordinator;
    private final GitCommit full = new GitCommit("a".repeat(40), "author-git", "changed", "diff");
    private final GitCommit next = new GitCommit("b".repeat(40), null, "next", "next diff");

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        var locks = mock(ProjectReviewLock.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(mock(ProjectReviewLock.Lease.class)));
        coordinator = new ReviewCoordinator(new ReviewRepository(db.jdbc), locks, git, ai, db.transactionManager, 100);
        when(ai.review(next)).thenReturn(new ReviewResult("Next review", List.of(new ReviewFinding("LOW", "Next finding", "next.java", 1, "description", "suggestion"))));
    }
    @AfterEach void close() { db.close(); }

    private GitCommit manual(String reason) {
        return new GitCommit(full.sha(), full.authorLogin(), full.authorEmail(), full.message(), "", "MANUAL_ONLY", "전체 변경 경로 증명; 본문 검토 없음",
                List.of(new ManualReviewFile("asset.bin", null, "c".repeat(40), null, "100644", reason),
                        new ManualReviewFile("also-needs-review.java", "d".repeat(40), "e".repeat(40), "100644", "100755", reason)));
    }
    private void batch(GitCommit first) {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(first, next), next.sha()));
    }

    @Test void manualCommitCreatesEveryEvidenceAndIssueThenReviewsNextCommitAndRetryIsIdempotent() {
        GitCommit manual = manual("SOURCE_DIFF_UNAVAILABLE");
        batch(manual);
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(next.sha());
        assertThat(db.count("manual_review_file")).isEqualTo(2);
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(db.count("review_issue")).isEqualTo(3);
        assertThat(db.jdbc.queryForList("select assignee_id from review_issue where issue_kind='MANUAL_REVIEW'", Long.class)).containsExactly(2L, 2L);
        assertThat(db.jdbc.queryForList("select severity from review_issue where issue_kind='MANUAL_REVIEW'", String.class)).containsOnlyNulls();
        assertThat(db.jdbc.queryForObject("select count(*) from review_issue where manual_file_id is not null and line_number is not null", Long.class)).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action='MANUAL_REVIEW_ASSIGNED'", Long.class)).isEqualTo(1);
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.count("manual_review_file")).isEqualTo(2);
        assertThat(db.count("review_issue")).isEqualTo(3);
        verify(ai, never()).review(manual);
        verify(ai, times(1)).review(next);
    }

    @ParameterizedTest @EnumSource(AiInputLimitException.Reason.class)
    void preflightInputLimitsUseIndependentlyProvenManualCoverage(AiInputLimitException.Reason reason) {
        batch(full);
        when(ai.review(full)).thenThrow(new AiInputLimitException(reason));
        when(git.manualFallback(any(), eq(full))).thenReturn(manual("AI_INPUT_LIMIT"));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(next.sha());
        assertThat(db.jdbc.queryForList("select reason_code from manual_review_file", String.class)).containsOnly("AI_INPUT_LIMIT");
        verify(git).manualFallback(any(), eq(full));
    }

    @Test void modelOutputOrTransportFailureCannotBecomeManualSuccess() {
        batch(full);
        when(ai.review(full)).thenThrow(new IntegrationException("AI returned invalid JSON"));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.cursor()).isNull();
        assertThat(db.count("manual_review_file")).isZero();
        verify(git, never()).manualFallback(any(), any());
        verify(ai, never()).review(next);
    }

    @Test void incompleteTreeProofStopsWithoutAdvancing() {
        batch(full);
        when(ai.review(full)).thenThrow(new AiInputLimitException(AiInputLimitException.Reason.FILE_CONTEXT_BUDGET));
        when(git.manualFallback(any(), any())).thenThrow(new IntegrationException("Git tree is incomplete"));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isZero();
        verify(ai, never()).review(next);
    }

    @Test void manualIssueInsertFailureRollsBackEvidenceCommitAssignmentAuditAndRunCount() {
        batch(manual("GIT_DIFF_BUDGET"));
        db.jdbc.execute("alter table review_issue add constraint reject_manual_fixture check (file_path <> 'also-needs-review.java')");
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.count("manual_review_file")).isZero();
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run", Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action='ISSUES_ASSIGNED'", Long.class)).isZero();
        assertThat(db.cursor()).isNull();
    }

    @Test void pausedDuringInputProofCannotPersistManualIssues() {
        batch(full);
        when(ai.review(full)).thenThrow(new AiInputLimitException(AiInputLimitException.Reason.TOTAL_DIFF_BYTES));
        when(git.manualFallback(any(), any())).thenAnswer(invocation -> {
            db.jdbc.update("update project set status='PAUSED' where id=10");
            return manual("AI_INPUT_LIMIT");
        });
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("manual_review_file")).isZero();
        assertThat(db.cursor()).isNull();
    }

    @Test void convertedCoverageCannotChangeCommitIdentity() {
        batch(full);
        when(ai.review(full)).thenThrow(new AiInputLimitException(AiInputLimitException.Reason.TOTAL_DIFF_BYTES));
        GitCommit proof = manual("AI_INPUT_LIMIT");
        when(git.manualFallback(any(), any())).thenReturn(new GitCommit("f".repeat(40), full.authorLogin(), null, full.message(), "", "MANUAL_ONLY", "proof", proof.manualFiles()));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
    }

    @Test void newMetadataCreatesManualWorkThenContinuesAndRetriesWithoutDuplicates() {
        GitCommit metadata = metadata();
        batch(metadata);
        when(git.manualMetadataFallback(any(), eq(metadata))).thenReturn(metadataProof());
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(next.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(db.count("manual_review_file")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("select reason_code from manual_review_file", String.class)).isEqualTo("METADATA_CHANGE");
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.count("manual_review_file")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(2);
        verify(git, times(1)).manualMetadataFallback(any(), eq(metadata));
        verify(git, never()).manualFallback(any(), any());
        verify(ai, never()).review(metadata);
        verify(ai, times(1)).review(next);
    }

    @Test void metadataProofHttpFailureStopsBeforeIssuesOrCursorAndDoesNotCallAi() {
        GitCommit metadata = metadata();
        batch(metadata);
        when(git.manualMetadataFallback(any(), eq(metadata))).thenThrow(new IntegrationException("Git returned HTTP 503"));
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.count("manual_review_file")).isZero();
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
    }

    @Test void metadataConversionCannotSubstituteADifferentManualReason() {
        GitCommit metadata = metadata();
        batch(metadata);
        when(git.manualMetadataFallback(any(), eq(metadata))).thenReturn(manual("AI_INPUT_LIMIT"));
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
    }

    private GitCommit metadata() {
        return new GitCommit(full.sha(), full.authorLogin(), full.authorEmail(), full.message(),
                "diff --git a/script.sh b/script.sh\nold mode 100644\nnew mode 100755\n", "METADATA_ONLY", "실행권한 변경");
    }

    private GitCommit metadataProof() {
        return new GitCommit(full.sha(), full.authorLogin(), full.authorEmail(), full.message(), "", "MANUAL_ONLY", "고정 tree로 실행권한 변경 확인",
                List.of(new ManualReviewFile("script.sh", "c".repeat(40), "c".repeat(40), "100644", "100755", "METADATA_CHANGE")));
    }

    @Test void evidenceCannotAttachToOtherProjectOrCloseWithoutHumanReason() {
        batch(manual("SOURCE_DIFF_UNAVAILABLE"));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThatThrownBy(() -> db.jdbc.update("update review_issue set status='RESOLVED' where issue_kind='MANUAL_REVIEW'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.jdbc.update("update manual_review_file set old_object_sha=?, old_mode=null", "e".repeat(40)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.jdbc.update("update review_issue set project_id=999 where issue_kind='MANUAL_REVIEW'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
