package com.aicreviewer.review;

import com.aicreviewer.git.RateLimitedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static com.aicreviewer.git.RateLimitedException.Service.AI;
import static com.aicreviewer.git.RateLimitedException.Service.GIT;
import static org.assertj.core.api.Assertions.*;

class ReviewRateLimitRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private ReviewTestDatabase db;
    private ReviewRepository reviews;
    private ReviewRequestRepository requests;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        reviews = new ReviewRepository(db.jdbc);
        requests = new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager);
        tx = new TransactionTemplate(db.transactionManager);
        requests.enqueueManual(10, "owner", NOW);
    }

    @AfterEach void close() { db.close(); }

    @Test void actualResponsePreservesRequestIdentityAndCommitsWhileClosingOnlyItsAttempt() {
        var original = request();
        var claim = requests.claim(original, NOW);
        String sha = "a".repeat(40);
        db.jdbc.update("insert into reviewed_commit(project_id, commit_sha, summary) values (10, ?, 'Stored')", sha);
        assertThat(defer(claim, new RateLimitedException(AI, NOW.plusSeconds(300), true), NOW))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.DEFERRED);
        var waiting = request();
        assertThat(waiting.state()).isEqualTo("QUEUED");
        assertThat(waiting.requestId()).isEqualTo(original.requestId());
        assertThat(waiting.requestedAt()).isEqualTo(original.requestedAt());
        assertThat(waiting.requestedBy()).isEqualTo(original.requestedBy());
        assertThat(waiting.source()).isEqualTo("MANUAL");
        assertThat(waiting.runId()).isNull();
        assertThat(waiting.finishedAt()).isNull();
        assertThat(waiting.availableAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(waiting.rateLimitCount()).isEqualTo(1);
        assertThat(waiting.rateLimitedAt()).isEqualTo(NOW);
        assertThat(waiting.resultCode()).isEqualTo("AI_RATE_LIMITED");
        assertThat(db.jdbc.queryForObject("select claim_token from review_request", String.class)).isNull();
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("FAILED");
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.cursor()).isNull();
        assertThat(reviews.reviewedShas(10)).containsExactly(sha);
    }

    @Test void staleSnapshotAndDuplicateSubmissionsCannotBypassThePersistedWait() {
        var stale = request();
        var claim = requests.claim(stale, NOW);
        defer(claim, new RateLimitedException(GIT, NOW.plusSeconds(180), true), NOW);
        var waiting = request();
        assertThat(requests.claim(stale, NOW.plusSeconds(179))).isNull();
        assertThat(requests.enqueueManual(10, "admin", NOW.plusSeconds(1)))
                .isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        assertThat(requests.enqueueScheduled(10, NOW.plusSeconds(2), NOW.plusSeconds(3600)))
                .isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        assertThat(request()).isEqualTo(waiting);
        assertThat(requests.candidates(NOW.plusSeconds(179), 1)).isEmpty();
        var reclaimed = requests.claim(stale, NOW.plusSeconds(180));
        assertThat(reclaimed.requestId()).isEqualTo(claim.requestId());
        assertThat(reclaimed.claimToken()).isNotEqualTo(claim.claimToken());
        assertThat(request().attemptCount()).isEqualTo(2);
    }

    @Test void sharedCooldownDoesNotConsumeActualResponseBudgetAndPreservesFirstWaitTime() {
        Instant at = NOW;
        for (int index = 0; index < 8; index++) {
            var claim = requests.claim(request(), at);
            assertThat(defer(claim, new RateLimitedException(GIT, at.plusSeconds(1), false), at))
                    .isEqualTo(ReviewRequestRepository.RateLimitOutcome.DEFERRED);
            assertThat(request().availableAt()).isEqualTo(at.plusSeconds(30));
            assertThat(request().rateLimitCount()).isZero();
            assertThat(request().rateLimitedAt()).isEqualTo(NOW);
            at = request().availableAt();
        }
        var claim = requests.claim(request(), at);
        defer(claim, new RateLimitedException(AI, at, true), at);
        assertThat(request().rateLimitCount()).isEqualTo(1);
        assertThat(request().availableAt()).isEqualTo(at.plusSeconds(60));
        assertThat(request().rateLimitedAt()).isEqualTo(NOW);
    }

    @Test void sixthActualResponseStopsAutomaticSchedulingUntilExplicitManualReplacement() {
        var original = request();
        Instant at = NOW;
        for (int count = 1; count <= 6; count++) {
            var claim = requests.claim(request(), at);
            var outcome = defer(claim, new RateLimitedException(AI, at.plusSeconds(1), true), at);
            assertThat(request().rateLimitCount()).isEqualTo(count);
            assertThat(outcome).isEqualTo(count < 6 ? ReviewRequestRepository.RateLimitOutcome.DEFERRED
                    : ReviewRequestRepository.RateLimitOutcome.EXHAUSTED);
            if (count < 6) {
                assertThat(request().availableAt()).isEqualTo(at.plusSeconds(30L << count));
                at = request().availableAt();
            }
        }
        var exhausted = request();
        assertThat(exhausted.state()).isEqualTo("FAILED");
        assertThat(exhausted.resultCode()).isEqualTo("RATE_LIMIT_EXHAUSTED");
        assertThat(exhausted.finishedAt()).isEqualTo(at);
        assertThat(requests.enqueueScheduled(10, at, at.plusSeconds(3600))).isEqualTo(ReviewRequestRepository.EnqueueResult.SKIPPED);
        assertThat(request()).isEqualTo(exhausted);
        assertThat(db.jdbc.queryForObject("select next_review_at from project where id = 10", Timestamp.class).toInstant())
                .isEqualTo(at.plusSeconds(3600));
        assertThat(requests.enqueueManual(10, "owner", at.plusSeconds(1))).isEqualTo(ReviewRequestRepository.EnqueueResult.QUEUED);
        assertThat(request().requestId()).isNotEqualTo(original.requestId());
        assertThat(request().rateLimitCount()).isZero();
        assertThat(request().rateLimitedAt()).isNull();
        assertThat(request().attemptCount()).isZero();
        assertThat(request().resultCode()).isNull();
    }

    @Test void expiredWaitIsClosedBeforeClaimCanStartAnotherRun() {
        var claim = requests.claim(request(), NOW);
        defer(claim, new RateLimitedException(AI, NOW.plusSeconds(60), false), NOW);
        assertThat(requests.claim(request(), NOW.plusSeconds(86400))).isNull();
        assertThat(request().state()).isEqualTo("FAILED");
        assertThat(request().resultCode()).isEqualTo("RATE_LIMIT_EXHAUSTED");
        assertThat(db.count("review_run")).isEqualTo(1);
    }

    @Test void retryHintBeyondRemainingDayIsNeverShortenedToMakeAnotherCall() {
        var first = requests.claim(request(), NOW);
        defer(first, new RateLimitedException(AI, NOW.plusSeconds(60), false), NOW);
        var next = requests.claim(request(), NOW.plusSeconds(86300));
        assertThat(defer(next, new RateLimitedException(AI, NOW.plusSeconds(86401), true), NOW.plusSeconds(86300)))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.EXHAUSTED);
        assertThat(request().state()).isEqualTo("FAILED");
        assertThat(request().rateLimitedAt()).isEqualTo(NOW);
    }

    @Test void unsupportedAutomaticRetryHintStopsWithoutInventingAnEarlierTime() {
        var claim = requests.claim(request(), NOW);
        assertThat(defer(claim, new RateLimitedException(GIT, null, true), NOW))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.EXHAUSTED);
        assertThat(request().rateLimitCount()).isEqualTo(1);
        assertThat(requests.candidates(NOW.plusSeconds(86400), 1)).isEmpty();
    }

    @Test void pauseOrAccountDisableWinsOverRateLimitDeferral() {
        var claim = requests.claim(request(), NOW);
        db.jdbc.update("update project set status = 'PAUSED' where id = 10");
        assertThat(defer(claim, new RateLimitedException(AI, NOW.plusSeconds(60), true), NOW))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.CANCELLED);
        assertThat(request().resultCode()).isEqualTo("PROJECT_INELIGIBLE");
        assertThat(request().rateLimitCount()).isZero();
        db.jdbc.update("update project set status = 'APPROVED' where id = 10");
        requests.enqueueManual(10, "owner", NOW);
        var next = requests.claim(request(), NOW);
        db.jdbc.update("update app_user set enabled = false where id = 1");
        assertThat(defer(next, new RateLimitedException(GIT, NOW.plusSeconds(60), true), NOW))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.CANCELLED);
        assertThat(request().resultCode()).isEqualTo("REQUESTER_INELIGIBLE");
    }

    @Test void lateWorkerCannotDeferOrExhaustItsReplacement() {
        var old = requests.claim(request(), NOW);
        var next = requests.claim(request(), NOW.plusSeconds(30));
        var original = request();
        assertThat(defer(old, new RateLimitedException(AI, null, true), NOW.plusSeconds(31)))
                .isEqualTo(ReviewRequestRepository.RateLimitOutcome.STALE);
        assertThat(request()).isEqualTo(original);
        assertThat(request().runId()).isEqualTo(next.runId());
        assertThat(db.jdbc.queryForObject("select status from review_run where id = ?", String.class, next.runId())).isEqualTo("RUNNING");
    }

    @Test void failedAuditRollsBackDeferralRunClosureAndCounterTogether() {
        var claim = requests.claim(request(), NOW);
        var original = request();
        db.jdbc.execute("alter table audit_event add constraint reject_rate_limit_fixture check(action <> 'REVIEW_RATE_LIMITED')");
        assertThatThrownBy(() -> defer(claim, new RateLimitedException(AI, NOW.plusSeconds(60), true), NOW))
                .isInstanceOf(DataAccessException.class);
        assertThat(request()).isEqualTo(original);
        assertThat(db.jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select error_message from review_run", String.class)).isNull();
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'REVIEW_FAILED'", Integer.class)).isZero();
        assertThatThrownBy(() -> requests.deferRateLimited(claim, new RateLimitedException(AI, NOW, true), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    private ReviewRequestRepository.Request request() { return requests.find(10).orElseThrow(); }
    private ReviewRequestRepository.RateLimitOutcome defer(ReviewRequestRepository.Claim claim, RateLimitedException exception, Instant at) {
        return tx.execute(status -> requests.deferRateLimited(claim, exception, at));
    }
}
