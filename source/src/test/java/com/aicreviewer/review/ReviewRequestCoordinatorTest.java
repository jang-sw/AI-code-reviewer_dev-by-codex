package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewRequestCoordinatorTest {
    private static final GitCommit COMMIT = new GitCommit("a".repeat(40), null, "Change", "diff");
    private ReviewTestDatabase db;
    private ReviewRequestRepository requests;
    private ReviewCoordinator coordinator;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        var reviews = new ReviewRepository(db.jdbc);
        requests = spy(new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager));
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        locks = mock(ProjectReviewLock.class);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(lease));
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(COMMIT), COMMIT.sha()));
        doReturn(new ReviewResult("Review summary", List.of())).when(ai).review(COMMIT);
        coordinator = new ReviewCoordinator(reviews, locks, git, ai, db.transactionManager, 100, requests);
        requests.enqueueManual(10, "owner", Instant.now());
    }
    @AfterEach void close() { Thread.interrupted(); db.close(); }

    @Test void successfulRequestPersistsResultCheckpointAndAcknowledgement() {
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(db.cursor()).isEqualTo(COMMIT.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        verify(lease).close();
    }

    @Test void busyLeaseDefersButNeverAcknowledgesOrStartsTheRequest() {
        when(locks.tryAcquire(10)).thenReturn(Optional.empty());
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.BUSY);
        assertThat(requests.find(10).orElseThrow()).satisfies(request -> {
            assertThat(request.state()).isEqualTo("QUEUED");
            assertThat(request.availableAt()).isAfterOrEqualTo(before.plusSeconds(30));
        });
        assertThat(db.count("review_run")).isZero();
        verifyNoInteractions(git, ai);
    }

    @Test void interruptionKeepsDurableWorkForRestart() {
        when(ai.review(COMMIT)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IntegrationException("AI request interrupted");
        });
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        Thread.interrupted();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.cursor()).isNull();
        doReturn(new ReviewResult("Review summary", List.of())).when(ai).review(COMMIT);
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "SUCCEEDED");
        assertThat(requests.find(10).orElseThrow().attemptCount()).isEqualTo(2);
    }

    @Test void ordinaryProviderFailureClosesRequestButNeverMarksUnreviewedCommitSuccessful() {
        when(ai.review(COMMIT)).thenThrow(new IntegrationException("AI returned HTTP 503"));
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("FAILED");
        assertThat(db.cursor()).isNull();
        assertThat(db.count("reviewed_commit")).isZero();
    }

    @Test void databaseFailureAcknowledgingTheFailureLeavesRunningRequestRecoverable() {
        when(ai.review(COMMIT)).thenThrow(new IntegrationException("AI unavailable"));
        doThrow(new IllegalStateException("Database unavailable")).when(requests).fail(any(), anyString(), any());
        assertThatThrownBy(() -> coordinator.processRequest(requests.find(10).orElseThrow())).isInstanceOf(IllegalStateException.class);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        verify(lease).close();
    }

    @Test void failedCompletionTransactionPreservesRunningRequestAndStoredCommitForRecovery() {
        db.jdbc.execute("alter table review_request add constraint reject_request_success check (state <> 'SUCCEEDED')");
        assertThatThrownBy(() -> coordinator.processRequest(requests.find(10).orElseThrow()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.cursor()).isNull();
        db.jdbc.execute("alter table review_request drop constraint reject_request_success");
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(), COMMIT.sha()));
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(db.cursor()).isEqualTo(COMMIT.sha());
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        verify(ai, times(1)).review(COMMIT);
        verify(git).batch(any(), any(), any(), eq(java.util.Set.of(COMMIT.sha())), anyInt());
    }

    @Test void wrappedInterruptPreservesRequestAndRestoresThreadFlag() {
        when(ai.review(COMMIT)).thenThrow(new IllegalStateException("Interrupted operation", new InterruptedException()));
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
    }

    @Test void aiReturningNormallyWithInterruptFlagCannotPersistOrAcknowledgeTheRequest() {
        when(ai.review(COMMIT)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new ReviewResult("Response arrived during shutdown", List.of());
        });
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        verify(lease).close();
    }

    @Test void emptyGitBatchReturningWithInterruptFlagCannotAdvanceStoredCheckpointOrAcknowledge() {
        db.jdbc.update("insert into reviewed_commit(project_id, commit_sha, summary) values (10, ?, 'Stored result')", COMMIT.sha());
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new GitReviewBatch(List.of(), COMMIT.sha());
        });
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
        verify(lease).close();
    }

    @Test void lateAiResultCannotPersistAfterReplacementHasClaimedTheRequest() {
        when(ai.review(COMMIT)).thenAnswer(invocation -> {
            requests.claim(requests.find(10).orElseThrow(), Instant.now());
            return new ReviewResult("Obsolete result", List.of());
        });
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(requests.find(10).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(db.jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "RUNNING");
    }

    @Test void disableDuringAiCallCancelsBeforePersistence() {
        when(ai.review(COMMIT)).thenAnswer(invocation -> {
            db.jdbc.update("update app_user set enabled = false where id = 1");
            return new ReviewResult("Late result", List.of());
        });
        assertThat(coordinator.processRequest(requests.find(10).orElseThrow())).isEqualTo(ReviewCoordinator.Outcome.CANCELLED);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
    }
}
