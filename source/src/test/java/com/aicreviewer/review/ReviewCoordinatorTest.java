package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewCoordinatorTest {
    private static final GitCommit FIRST = new GitCommit("a".repeat(40), "AUTHOR-GIT", "First", "diff first");
    private static final GitCommit SIDE_BRANCH = new GitCommit("b".repeat(40), null, "Branch", "diff branch");
    private static final GitCommit MERGE = new GitCommit("c".repeat(40), "unknown", "Merge", "diff merge");
    private ReviewTestDatabase db;
    private ReviewRepository repository;
    private ProjectReviewLock lock;
    private ProjectReviewLock.Lease lease;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ReviewCoordinator coordinator;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        repository = spy(new ReviewRepository(db.jdbc));
        lock = mock(ProjectReviewLock.class);
        lease = mock(ProjectReviewLock.Lease.class);
        when(lock.tryAcquire(10)).thenReturn(Optional.of(lease));
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        when(ai.review(any())).thenReturn(review("Suggested change"));
        coordinator = new ReviewCoordinator(repository, lock, git, ai, db.transactionManager, 100);
    }

    @AfterEach
    void cleanup() { db.close(); }

    @Test
    void fullHistoryBatchAssignsMatchedAuthorAndAuditsOwnerFallback() {
        when(git.batch(any(), isNull(), isNull(), eq(Set.of()), eq(100))).thenReturn(new GitReviewBatch(List.of(FIRST, MERGE), MERGE.sha()));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        assertThat(db.cursor()).isEqualTo(MERGE.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(db.jdbc.queryForList("select assignee_id from review_issue order by id", Long.class)).containsExactly(2L, 1L);
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'ISSUE_ASSIGNEE_FALLBACK'", Long.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run", Integer.class)).isEqualTo(2);
        verify(git).batch(any(), isNull(), isNull(), eq(Set.of()), eq(100));
        verify(lease).close();
    }

    @Test
    void failedSideBranchKeepsBatchCheckpointAndRetryDoesNotDuplicateAlreadyReviewedCommits() {
        when(git.batch(any(), isNull(), isNull(), anySet(), eq(100)))
                .thenReturn(new GitReviewBatch(List.of(FIRST, SIDE_BRANCH, MERGE), MERGE.sha()))
                .thenReturn(new GitReviewBatch(List.of(SIDE_BRANCH, MERGE), MERGE.sha()));
        when(ai.review(SIDE_BRANCH)).thenThrow(new IllegalStateException("secret-token must not be recorded"))
                .thenReturn(review("Side branch fix"));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select error_message from review_run", String.class)).doesNotContain("secret-token");

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(MERGE.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(3);
        assertThat(db.count("review_issue")).isEqualTo(3);
        assertThat(db.jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "SUCCEEDED");
        assertThat(db.jdbc.queryForList("select reviewed_commits from review_run order by id", Integer.class)).containsExactly(1, 2);
        verify(ai, times(1)).review(FIRST);
        verify(ai, times(2)).review(SIDE_BRANCH);
        verify(ai, times(1)).review(MERGE);
        verify(git).batch(any(), isNull(), isNull(), eq(Set.of()), eq(100));
        verify(git).batch(any(), isNull(), isNull(), eq(Set.of(FIRST.sha())), eq(100));
        verify(lease, times(2)).close();
    }

    @Test
    void databaseFailureRollsBackCommitEveryIssueAndProgressCount() {
        db.jdbc.execute("alter table review_issue add constraint reject_fixture check (title <> 'rollback-me')");
        stubBatch(List.of(FIRST), FIRST.sha());
        when(ai.review(FIRST)).thenReturn(new ReviewResult("Two findings", List.of(finding("good"), finding("rollback-me"))));

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run", Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("FAILED");
        verify(lease).close();
    }

    @Test
    void checkpointAndRunSuccessRollbackTogetherThenStoredBatchCanResume() {
        when(git.batch(any(), any(), any(), anySet(), anyInt()))
                .thenReturn(new GitReviewBatch(List.of(FIRST, SIDE_BRANCH, MERGE), MERGE.sha()))
                .thenReturn(new GitReviewBatch(List.of(), MERGE.sha()));
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("checkpoint fixture failure"); })
                .doCallRealMethod().when(repository).completeBatch(anyLong(), any(), anyString(), any());

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isEqualTo(3);
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("FAILED");
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'REVIEW_SUCCEEDED'", Long.class)).isZero();

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(MERGE.sha());
        assertThat(db.count("review_issue")).isEqualTo(3);
        assertThat(db.jdbc.queryForList("select reviewed_commits from review_run order by id", Integer.class)).containsExactly(3, 0);
        verify(git).batch(any(), isNull(), isNull(), eq(Set.of(FIRST.sha(), SIDE_BRANCH.sha(), MERGE.sha())), eq(100));
        verify(ai, times(3)).review(any());
    }

    @Test
    void partialMergePersistsAcrossSmallBatchesWithoutAdvancingToSideBranch() {
        coordinator = new ReviewCoordinator(repository, lock, git, ai, db.transactionManager, 1);
        when(git.batch(any(), isNull(), isNull(), eq(Set.of()), eq(1)))
                .thenReturn(new GitReviewBatch(List.of(FIRST), FIRST.sha()));
        when(git.batch(any(), isNull(), eq(FIRST.sha()), eq(Set.of(FIRST.sha())), eq(1)))
                .thenReturn(new GitReviewBatch(List.of(SIDE_BRANCH), null));
        when(git.batch(any(), isNull(), eq(FIRST.sha()), eq(Set.of(FIRST.sha(), SIDE_BRANCH.sha())), eq(1)))
                .thenReturn(new GitReviewBatch(List.of(MERGE), MERGE.sha()));

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(FIRST.sha());
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(FIRST.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(MERGE.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(3);
        assertThat(db.jdbc.queryForList("select reviewed_commits from review_run order by id", Integer.class)).containsExactly(1, 1, 1);
        verify(ai).review(FIRST);
        verify(ai).review(SIDE_BRANCH);
        verify(ai).review(MERGE);
    }

    @Test
    void plannedCheckpointCanBeAnAlreadyPersistedCommitBeyondLastSelectedCommit() {
        db.jdbc.update("insert into reviewed_commit(project_id, commit_sha, summary) values (10, ?, 'Previously stored review')", MERGE.sha());
        stubBatch(List.of(FIRST), MERGE.sha());

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        assertThat(db.cursor()).isEqualTo(MERGE.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run", Integer.class)).isEqualTo(1);
        verify(ai).review(FIRST);
        verify(ai, never()).review(MERGE);
        verify(git).batch(any(), isNull(), isNull(), eq(Set.of(MERGE.sha())), eq(100));
    }

    @Test
    void unknownCheckpointFailsBeforeAiOrAnyCommitPersistence() {
        stubBatch(List.of(FIRST), MERGE.sha());

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isZero();
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = { "invalid", "javascript:alert(1)", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
    void invalidCheckpointFormatFailsBeforeAi(String checkpoint) {
        stubBatch(List.of(FIRST), checkpoint);

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isZero();
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = { "NULL_BATCH", "NULL_COMMITS", "TOO_MANY" })
    void invalidBatchShapeOrSizeFailsBeforeAi(String kind) {
        GitReviewBatch batch = switch (kind) {
            case "NULL_BATCH" -> null;
            case "NULL_COMMITS" -> new GitReviewBatch(null, null);
            default -> new GitReviewBatch(java.util.Collections.nCopies(101, FIRST), FIRST.sha());
        };
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(batch);

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isZero();
        verifyNoInteractions(ai);
    }

    @Test
    void persistedSetFailureIsRecordedInsideLeaseAndDoesNotCallExternalProviders() {
        doThrow(new IntegrationException("Stored review history exceeds the memory safety budget"))
                .when(repository).reviewedShas(10);

        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        var order = inOrder(lock, repository, lease);
        order.verify(lock).tryAcquire(10);
        order.verify(repository).startRun(eq(10L), isNull(), any());
        order.verify(repository).reviewedShas(10);
        order.verify(repository).finishRun(anyLong(), eq(false), any(), contains("memory safety budget"));
        order.verify(lease).close();
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("FAILED");
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(git, ai);
    }

    @Test
    void invalidOrDuplicatedBatchFailsBeforeAnyAiCall() {
        stubBatch(List.of(FIRST, FIRST), FIRST.sha());
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
    }

    @Test
    void invalidAiResultNeverMarksCommitReviewed() {
        stubBatch(List.of(FIRST), FIRST.sha());
        when(ai.review(FIRST)).thenReturn(new ReviewResult("", List.of()));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
    }

    @Test
    void safeIntegrationFailureExplainsRequiredOperatorAction() {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenThrow(new IntegrationException("Review cursor is no longer on the branch first-parent history; manual history reconciliation is required"));
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.jdbc.queryForObject("select error_message from review_run", String.class))
                .contains("manual history reconciliation is required");
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
    }

    @Test
    void anotherOwnerCannotQueueOrStartReviewAndDisabledUserCannotRun() {
        assertThatThrownBy(() -> coordinator.authorizeManual(10, "other")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> coordinator.reviewProject(10, "other")).isInstanceOf(AccessDeniedException.class);
        db.jdbc.update("update app_user set enabled = false where id = 1");
        assertThatThrownBy(() -> coordinator.reviewProject(10, "owner")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(lock, git, ai);
    }

    @Test
    void unapprovedProjectCannotBeReviewedEvenByAdminOrScheduler() {
        db.jdbc.update("update project set status = 'PENDING' where id = 10");
        assertThatThrownBy(() -> coordinator.reviewProject(10, "admin")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> coordinator.reviewProject(10, null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(lock, git, ai);
    }

    @Test
    void lockContentionDoesNotCreateRunOrCallProviders() {
        when(lock.tryAcquire(10)).thenReturn(Optional.empty());
        assertThat(coordinator.reviewProject(10, "admin")).isEqualTo(ReviewCoordinator.Outcome.BUSY);
        assertThat(db.count("review_run")).isZero();
        verifyNoInteractions(git, ai, lease);
    }

    @Test
    void interruptedRunIsFailedOnlyAfterExclusiveLeaseAcquired() {
        db.jdbc.update("insert into review_run(project_id, status) values (10, 'RUNNING')");
        stubBatch(List.of(), null);
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    void pauseDuringAiCallPreventsPersistenceAndCheckpoint() {
        stubBatch(List.of(FIRST), FIRST.sha());
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            db.jdbc.update("update project set status = 'PAUSED' where id = 10");
            return review("Change");
        });
        assertThat(coordinator.reviewProject(10, null)).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        verify(lease).close();
    }

    private static ReviewResult review(String title) { return new ReviewResult("Review summary", List.of(finding(title))); }
    private static ReviewFinding finding(String title) { return new ReviewFinding("HIGH", title, "src/Main.java", 7, "Missing validation", "Validate before use"); }

    private void stubBatch(List<GitCommit> commits, String checkpoint) {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(commits, checkpoint));
    }
}
