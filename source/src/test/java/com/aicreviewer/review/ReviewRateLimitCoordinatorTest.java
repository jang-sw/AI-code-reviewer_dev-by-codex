package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.RateLimitedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.aicreviewer.git.RateLimitedException.Service.AI;
import static com.aicreviewer.git.RateLimitedException.Service.GIT;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewRateLimitCoordinatorTest {
    private static final GitCommit FIRST = new GitCommit("a".repeat(40), null, "First", "diff first");
    private static final GitCommit SECOND = new GitCommit("b".repeat(40), null, "Second", "diff second");
    private ReviewTestDatabase db;
    private ReviewRepository reviews;
    private ReviewRequestRepository requests;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ProjectReviewLock locks;
    private ProjectReviewLock.Lease lease;
    private ReviewCoordinator coordinator;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        reviews = new ReviewRepository(db.jdbc);
        requests = new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager);
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        locks = mock(ProjectReviewLock.class);
        lease = mock(ProjectReviewLock.Lease.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(lease));
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> stored = invocation.getArgument(3);
            return new GitReviewBatch(stored.contains(FIRST.sha()) ? List.of(SECOND) : List.of(FIRST, SECOND), SECOND.sha());
        });
        when(ai.review(any())).thenReturn(result());
        coordinator = new ReviewCoordinator(reviews, locks, git, ai, db.transactionManager, 100, requests);
        requests.enqueueManual(10, "owner", Instant.now().minusSeconds(1));
    }

    @AfterEach void close() { db.close(); }

    @Test void providerWaitSurvivesReconstructionAndReusesOnlyAtomicallySavedCommits() {
        var original = request();
        when(ai.review(SECOND)).thenThrow(new RateLimitedException(AI, Instant.now().plusSeconds(300), true));
        assertThat(coordinator.processRequest(original)).isEqualTo(ReviewCoordinator.Outcome.DEFERRED);
        var waiting = request();
        assertThat(waiting.state()).isEqualTo("QUEUED");
        assertThat(waiting.requestId()).isEqualTo(original.requestId());
        assertThat(waiting.rateLimitCount()).isEqualTo(1);
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(1);
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run", Integer.class)).isEqualTo(1);
        assertThat(coordinator.processRequest(original)).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        verify(git, times(1)).batch(any(), any(), any(), anySet(), anyInt());
        var restarted = new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager);
        assertThat(restarted.find(10)).contains(waiting);
        // Represent the provider wait elapsing without a multi-minute test sleep.
        db.jdbc.update("update review_request set available_at = ? where project_id = 10", Timestamp.from(Instant.now().minusSeconds(1)));
        doReturn(result()).when(ai).review(SECOND);
        var newWorker = new ReviewCoordinator(reviews, locks, git, ai, db.transactionManager, 100, restarted);
        assertThat(newWorker.processRequest(original)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(request().requestId()).isEqualTo(original.requestId());
        assertThat(request().attemptCount()).isEqualTo(2);
        assertThat(request().rateLimitCount()).isEqualTo(1);
        assertThat(db.count("reviewed_commit")).isEqualTo(2);
        assertThat(db.count("review_issue")).isEqualTo(2);
        assertThat(db.cursor()).isEqualTo(SECOND.sha());
        verify(ai, times(1)).review(FIRST);
        verify(ai, times(2)).review(SECOND);
        verify(git).batch(any(), any(), isNull(), eq(Set.of(FIRST.sha())), anyInt());
        verify(lease, times(3)).close();
    }

    @Test void gitCooldownWaitMakesNoAiCallsAndReleasesTheWorkerLease() {
        when(git.batch(any(), any(), any(), anySet(), anyInt()))
                .thenThrow(new RateLimitedException(GIT, Instant.now().plusSeconds(120), false));
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.DEFERRED);
        assertThat(request().resultCode()).isEqualTo("GIT_RATE_LIMITED");
        assertThat(request().rateLimitCount()).isZero();
        assertThat(db.count("reviewed_commit")).isZero();
        verifyNoInteractions(ai);
        verify(lease).close();
    }

    @Test void deferralDatabaseErrorRetainsRecoverableRunningStateAndSavedSha() {
        when(ai.review(SECOND)).thenThrow(new RateLimitedException(AI, Instant.now().plusSeconds(120), true));
        db.jdbc.execute("alter table audit_event add constraint reject_rate_limit_audit check(action <> 'REVIEW_RATE_LIMITED')");
        assertThatThrownBy(() -> coordinator.processRequest(request())).isInstanceOf(DataAccessException.class);
        assertThat(request().state()).isEqualTo("RUNNING");
        assertThat(request().rateLimitCount()).isZero();
        assertThat(request().rateLimitedAt()).isNull();
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(1);
        assertThat(db.cursor()).isNull();
        verify(lease).close();
    }

    @Test void lateRateLimitCannotRequeueTheNewOwnersAttempt() {
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            var current = request();
            requests.claim(current, current.availableAt());
            throw new RateLimitedException(AI, Instant.now().plusSeconds(120), true);
        });
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(request().state()).isEqualTo("RUNNING");
        assertThat(request().attemptCount()).isEqualTo(2);
        assertThat(request().rateLimitCount()).isZero();
        assertThat(db.count("reviewed_commit")).isZero();
    }

    @Test void changedAuthorityCancelsRatherThanSchedulingAProviderRetry() {
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            db.jdbc.update("update app_user set enabled = false where id = 1");
            throw new RateLimitedException(AI, Instant.now().plusSeconds(120), true);
        });
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.CANCELLED);
        assertThat(request().resultCode()).isEqualTo("REQUESTER_INELIGIBLE");
        assertThat(request().rateLimitCount()).isZero();
        assertThat(db.count("reviewed_commit")).isZero();
    }

    @Test void nonAutomaticRetryHintBecomesExplicitTerminalFailure() {
        when(ai.review(FIRST)).thenThrow(new RateLimitedException(AI, null, true));
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(request().resultCode()).isEqualTo("RATE_LIMIT_EXHAUSTED");
        assertThat(request().state()).isEqualTo("FAILED");
        assertThat(db.cursor()).isNull();
        assertThat(db.count("review_issue")).isZero();
    }

    private ReviewRequestRepository.Request request() { return requests.find(10).orElseThrow(); }
    private static ReviewResult result() {
        return new ReviewResult("Reviewed", List.of(new ReviewFinding("LOW", "Inspect", "A.java", 1, "Evidence", "Recommendation")));
    }
}
