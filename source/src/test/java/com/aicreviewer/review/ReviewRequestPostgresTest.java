package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL queue recovery/fencing, using a disposable schema and independent
 * sessions. Restart means reconstructing workers against committed state; this is
 * not a process-manager or operating-system crash test. Git and AI are always mocks.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class ReviewRequestPostgresTest {
    private static final GitCommit FIRST = new GitCommit("a".repeat(40), "owner", "first", "diff first");
    private static final GitCommit SECOND = new GitCommit("b".repeat(40), "owner", "second", "diff second");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private DriverManagerDataSource source;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String schema;
    private long projectId;
    private ReviewRepository reviews;
    private ReviewRequestRepository requests;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactions;
    private GitRepositoryClient git;
    private AiReviewClient ai;

    @BeforeEach void setup() {
        schema = "queue_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(dataSource(null));
        admin.setQueryTimeout(10);
        admin.execute("create schema " + schema);
        source = dataSource(schema);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        transactionManager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(10);
        reviews = new ReviewRepository(jdbc);
        requests = newRequests();
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        when(ai.review(any())).thenReturn(result("Verified worker result"));
        // Advisory keys are database-wide, so avoid IDs used by public-schema fixtures.
        projectId = 4_294_967_296L + Integer.toUnsignedLong(UUID.randomUUID().hashCode());
        jdbc.update("insert into app_user(id,username,password_hash,git_username,role,enabled) values " +
                "(1,'owner','fixture','owner','USER',true),(2,'other','fixture','other','USER',true),(3,'admin','fixture','admin','ADMIN',true)");
        createProject(projectId, "first");
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null && schema.matches("queue_test_[0-9a-f]{32}")) {
            // Only the generated schema owned by this fixture is removed.
            admin.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test void committedQueueSurvivesWorkerReconstructionAndKeepsItsManualActor() {
        assertThat(requests.enqueueManual(projectId, "owner", NOW)).isEqualTo(ReviewRequestRepository.EnqueueResult.QUEUED);
        var original = requests.find(projectId).orElseThrow();
        var restartedRequests = newRequests();
        assertThat(restartedRequests.find(projectId)).contains(original);
        assertThat(restartedRequests.candidates(NOW.plusSeconds(1), 10)).containsExactly(original);
        assertThat(original.source()).isEqualTo("MANUAL");
        assertThat(original.requestedBy()).isEqualTo(1L);
        stubBatch(List.of(FIRST), FIRST.sha());

        assertThat(coordinator(restartedRequests, new PostgresProjectReviewLock(source)).processRequest(original))
                .isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(cursor()).isEqualTo(FIRST.sha());
        assertThat(count("review_issue")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor_id from audit_event where action='REVIEW_STARTED'", Long.class)).isEqualTo(1L);
        verify(ai, times(1)).review(FIRST);
    }

    @Test void simultaneousEnqueueFromIndependentConnectionsCreatesOneDurableRequest() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return requests.enqueueManual(projectId, "owner", NOW); });
            var second = workers.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return newRequests().enqueueManual(projectId, "owner", NOW); });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ReviewRequestRepository.EnqueueResult.QUEUED, ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        }
        assertThat(count("review_request")).isEqualTo(1);
        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(count("review_run")).isZero();
        verifyNoInteractions(git, ai);
    }

    @Test void liveAdvisoryLeasePreventsDuplicateWorkerButDoesNotBlockAnotherProject() {
        createProject(projectId + 1, "second");
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        var firstLocks = new PostgresProjectReviewLock(source);
        var secondLocks = new PostgresProjectReviewLock(dataSource(schema));
        try (var firstLease = firstLocks.tryAcquire(projectId).orElseThrow()) {
            assertThat(requests.claim(request, NOW)).isNotNull();
            assertThat(secondLocks.tryAcquire(projectId)).isEmpty();
            assertThat(coordinator(newRequests(), secondLocks).processRequest(request)).isEqualTo(ReviewCoordinator.Outcome.BUSY);
            try (var otherLease = secondLocks.tryAcquire(projectId + 1).orElseThrow()) {
                assertThat(otherLease).isNotNull();
            }
            assertThat(count("review_run")).isEqualTo(1);
            verifyNoInteractions(git, ai);
        }
    }

    @Test void lostAdvisorySessionFencesOldCommitFinalizationAndFailureTokens() throws Exception {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        var tracked = new CapturedSessionDataSource(source);
        var abandonedLease = new PostgresProjectReviewLock(tracked).tryAcquire(projectId).orElseThrow();
        var oldClaim = requests.claim(request, NOW);
        var original = reviews.project(projectId, false);
        transactions.executeWithoutResult(status -> {
            requests.guard(oldClaim);
            reviews.persistCommit(oldClaim.runId(), original, null, FIRST, result("Before lost session"), NOW);
        });
        terminateOwnedSession(tracked);

        try (var recoveredLease = new PostgresProjectReviewLock(source).tryAcquire(projectId).orElseThrow()) {
            var recovered = newRequests().claim(request, NOW.plusSeconds(1));
            assertThat(recovered.claimToken()).isNotEqualTo(oldClaim.claimToken());
            assertThat(recovered.requestId()).isEqualTo(oldClaim.requestId());
            assertThat(recovered.runId()).isNotEqualTo(oldClaim.runId());
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                requests.guard(oldClaim);
                reviews.persistCommit(oldClaim.runId(), original, null, SECOND, result("Stale result"), NOW);
            })).isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> requests.complete(oldClaim, original, FIRST.sha(), NOW)))
                    .isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
            Boolean obsoleteAcknowledged = transactions.execute(status -> requests.fail(oldClaim, "stale failure", NOW));
            assertThat(obsoleteAcknowledged).isFalse();
            assertThat(requests.find(projectId).orElseThrow().runId()).isEqualTo(recovered.runId());
            assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("RUNNING");
            assertThat(count("reviewed_commit")).isEqualTo(1);
            assertThat(count("review_issue")).isEqualTo(1);
            assertThat(cursor()).isNull();
            transactions.executeWithoutResult(status -> requests.complete(recovered, original, FIRST.sha(), NOW.plusSeconds(2)));
            assertThat(cursor()).isEqualTo(FIRST.sha());
        } finally {
            assertThatThrownBy(abandonedLease::close).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot release project review lock");
        }
    }

    @Test void lateAiWorkerCannotOverwriteRecoveredWorkersSuccessfulResult() throws Exception {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        stubBatch(List.of(FIRST), FIRST.sha());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invocationCount = new AtomicInteger();
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            if (invocationCount.incrementAndGet() == 1) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture barrier expired");
                return result("Stale worker result");
            }
            return result("Recovered worker result");
        });
        var tracked = new CapturedSessionDataSource(source);
        var oldWorker = coordinator(requests, new PostgresProjectReviewLock(tracked));
        try (var workers = Executors.newSingleThreadExecutor()) {
            var oldCompletion = workers.submit(() -> oldWorker.processRequest(request));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                terminateOwnedSession(tracked);
                assertThat(coordinator(newRequests(), new PostgresProjectReviewLock(source)).processRequest(request))
                        .isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
            } finally { release.countDown(); }
            // The dead advisory connection must also fail its final unlock.
            assertThatThrownBy(() -> oldCompletion.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(count("reviewed_commit")).isEqualTo(1);
        assertThat(count("review_issue")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from review_issue", String.class)).isEqualTo("Recovered worker result");
        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(cursor()).isEqualTo(FIRST.sha());
        assertThat(jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "SUCCEEDED");
    }

    @Test void failedFinalizationTransactionKeepsRunningRequestAndReusesPersistedShaAfterRestart() {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> stored = invocation.getArgument(3);
            return new GitReviewBatch(stored.isEmpty() ? List.of(FIRST) : List.of(), FIRST.sha());
        });
        jdbc.execute("alter table review_request add constraint queue_fixture_finalization_failure check(state <> 'SUCCEEDED')");
        try {
            assertThatThrownBy(() -> coordinator(requests, new PostgresProjectReviewLock(source)).processRequest(request))
                    .isInstanceOf(DataAccessException.class);
            assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("RUNNING");
            assertThat(cursor()).isNull();
            assertThat(count("reviewed_commit")).isEqualTo(1);
            assertThat(count("review_issue")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
        } finally { jdbc.execute("alter table review_request drop constraint queue_fixture_finalization_failure"); }

        assertThat(coordinator(newRequests(), new PostgresProjectReviewLock(source)).processRequest(request))
                .isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(cursor()).isEqualTo(FIRST.sha());
        assertThat(count("reviewed_commit")).isEqualTo(1);
        assertThat(count("review_issue")).isEqualTo(1);
        verify(ai, times(1)).review(FIRST);
        verify(git).batch(any(), any(), isNull(), eq(Set.of(FIRST.sha())), anyInt());
    }

    @Test void enqueueDatabaseFailureCannotReportSuccessOrLeavePartialQueueState() {
        jdbc.execute("alter table review_request add constraint queue_fixture_enqueue_failure check(state <> 'QUEUED')");
        try {
            assertThatThrownBy(() -> requests.enqueueManual(projectId, "owner", NOW)).isInstanceOf(DataAccessException.class);
            assertThat(count("review_request")).isZero();
            assertThat(count("review_run")).isZero();
            assertThat(count("audit_event")).isZero();
        } finally { jdbc.execute("alter table review_request drop constraint queue_fixture_enqueue_failure"); }
    }

    @Test void claimDatabaseFailureRollsBackNewRunAndRetainsQueuedRequest() {
        requests.enqueueManual(projectId, "owner", NOW);
        var original = requests.find(projectId).orElseThrow();
        jdbc.execute("alter table review_request add constraint queue_fixture_claim_failure check(state <> 'RUNNING')");
        try {
            assertThatThrownBy(() -> coordinator(requests, new PostgresProjectReviewLock(source)).processRequest(original))
                    .isInstanceOf(DataAccessException.class);
            assertThat(requests.find(projectId)).contains(original);
            assertThat(count("review_run")).isZero();
            assertThat(jdbc.queryForObject("select count(*) from audit_event where action='REVIEW_STARTED'", Integer.class)).isZero();
            verifyNoInteractions(git, ai);
        } finally { jdbc.execute("alter table review_request drop constraint queue_fixture_claim_failure"); }
    }

    @Test void failureAcknowledgementDatabaseErrorKeepsRecoverableRequestInsteadOfLosingIt() {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenThrow(new IllegalStateException("fixture-secret-never-persist"));
        jdbc.execute("alter table review_request add constraint queue_fixture_failure_ack check(state <> 'FAILED')");
        try {
            assertThatThrownBy(() -> coordinator(requests, new PostgresProjectReviewLock(source)).processRequest(request))
                    .isInstanceOf(DataAccessException.class);
            assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("select status from review_run", String.class)).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("select error_message from review_run", String.class)).isNull();
            assertThat(jdbc.queryForObject("select count(*) from audit_event where action='REVIEW_FAILED'", Integer.class)).isZero();
        } finally { jdbc.execute("alter table review_request drop constraint queue_fixture_failure_ack"); }
        doReturn(new GitReviewBatch(List.of(FIRST), FIRST.sha())).when(git).batch(any(), any(), any(), anySet(), anyInt());
        assertThat(coordinator(newRequests(), new PostgresProjectReviewLock(source)).processRequest(request))
                .isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(count("review_issue")).isEqualTo(1);
        assertThat(jdbc.queryForList("select detail from audit_event", String.class))
                .allSatisfy(detail -> assertThat(detail).doesNotContain("fixture-secret-never-persist"));
    }

    @Test void interruptedWorkerLeavesDurablePartialProgressForItsReplacement() throws Exception {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> stored = invocation.getArgument(3);
            return new GitReviewBatch(stored.isEmpty() ? List.of(FIRST, SECOND) : List.of(SECOND), SECOND.sha());
        });
        when(ai.review(SECOND)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Synthetic interrupted review");
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertThat(worker.submit(() -> coordinator(requests, new PostgresProjectReviewLock(source)).processRequest(request))
                    .get(10, TimeUnit.SECONDS)).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        }
        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(count("reviewed_commit")).isEqualTo(1);
        assertThat(cursor()).isNull();
        doReturn(result("Resumed result")).when(ai).review(SECOND);
        assertThat(coordinator(newRequests(), new PostgresProjectReviewLock(source)).processRequest(request))
                .isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(cursor()).isEqualTo(SECOND.sha());
        assertThat(count("review_issue")).isEqualTo(2);
        verify(ai, times(1)).review(FIRST);
        verify(ai, times(2)).review(SECOND);
        verify(git).batch(any(), any(), isNull(), eq(Set.of(FIRST.sha())), anyInt());
    }

    @Test void scheduleTickCannotReplacePendingManualIdentityOrQueueTheSamePeriodAgain() {
        requests.enqueueManual(projectId, "owner", NOW);
        var original = requests.find(projectId).orElseThrow();
        assertThat(newRequests().enqueueScheduled(projectId, NOW, NOW.plusSeconds(3600)))
                .isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        assertThat(requests.find(projectId)).contains(original);
        assertThat(newRequests().enqueueScheduled(projectId, NOW, NOW.plusSeconds(3600)))
                .isEqualTo(ReviewRequestRepository.EnqueueResult.SKIPPED);
        assertThat(requests.scheduledCandidates(NOW, 10)).isEmpty();
        assertThat(requests.scheduledCandidates(NOW.plusSeconds(3600), 10)).containsExactly(projectId);
        assertThat(count("review_request")).isEqualTo(1);
    }

    @Test void boundedUnionCandidatesKeepGlobalOrderAcrossChangingLimitsAndExactDueTimes() {
        for (int offset = 1; offset <= 8; offset++) createProject(projectId + offset, "ordering-" + offset);
        // Real persisted QUEUED/RUNNING/terminal rows, without an executor or external provider.
        long[] receptionSeconds = {-30, -70, -80, -80, 0, 1, -100, -100, -50};
        for (int offset = 0; offset <= 8; offset++) {
            requests.enqueueManual(projectId + offset, "owner", NOW.plusSeconds(receptionSeconds[offset]));
        }
        requests.deferBusy(requests.find(projectId + 2).orElseThrow(), NOW.minusSeconds(10));
        var locks = new PostgresProjectReviewLock(source);
        int[] runningOffsets = {1, 3, 8};
        long[] claimSeconds = {-60, -40, -29};
        for (int index = 0; index < runningOffsets.length; index++) {
            long id = projectId + runningOffsets[index];
            try (var lease = locks.tryAcquire(id).orElseThrow()) {
                assertThat(requests.claim(requests.find(id).orElseThrow(), NOW.plusSeconds(claimSeconds[index]))).isNotNull();
            }
        }
        for (int offset : new int[] {6, 7}) {
            long id = projectId + offset;
            try (var lease = locks.tryAcquire(id).orElseThrow()) {
                var claim = requests.claim(requests.find(id).orElseThrow(), NOW.minusSeconds(90));
                if (offset == 6) {
                    transactions.executeWithoutResult(status -> requests.complete(claim, reviews.project(id, false), null, NOW.minusSeconds(80)));
                } else {
                    transactions.executeWithoutResult(status -> requests.fail(claim, "Synthetic terminal fixture", NOW.minusSeconds(80)));
                }
            }
        }
        // Two NULLs, two equal past times, one exact boundary, one future, and paused
        // NULL/past rows. Reception/ID order deliberately differs from schedule order.
        jdbc.update("update project set next_review_at=? where id in (?,?)", Timestamp.from(NOW.minusSeconds(60)), projectId + 2, projectId + 3);
        jdbc.update("update project set next_review_at=? where id=?", Timestamp.from(NOW), projectId + 4);
        jdbc.update("update project set next_review_at=? where id=?", Timestamp.from(NOW.plusSeconds(1)), projectId + 5);
        jdbc.update("update project set next_review_at=? where id in (?,?)", Timestamp.from(NOW.minusSeconds(120)), projectId + 7, projectId + 8);
        jdbc.update("update project set status='PAUSED' where id in (?,?)", projectId + 6, projectId + 7);

        List<Long> scheduled = List.of(projectId, projectId + 1, projectId + 8, projectId + 2, projectId + 3, projectId + 4);
        List<Long> ready = List.of(projectId + 1, projectId, projectId + 2, projectId + 3, projectId + 4);
        // Reuse the exact SQL/repository with alternating K values. This catches a
        // cached inner LIMIT as well as missing outer LIMIT and incorrect merge order.
        for (int limit : new int[] {1, 4, 2, 6, 3, 10, 1, 5, 2, 1016}) {
            assertThat(requests.scheduledCandidates(NOW, limit)).as("scheduled limit=%s", limit)
                    .containsExactlyElementsOf(scheduled.subList(0, Math.min(limit, scheduled.size())));
            assertThat(requests.candidates(NOW, limit)).as("ready limit=%s", limit)
                    .extracting(ReviewRequestRepository.Request::projectId)
                    .containsExactlyElementsOf(ready.subList(0, Math.min(limit, ready.size())));
        }
        assertThat(requests.scheduledCandidates(NOW.minusSeconds(1), 10))
                .containsExactlyElementsOf(scheduled.subList(0, 5));
        assertThat(requests.scheduledCandidates(NOW.plusSeconds(1), 10))
                .containsExactly(projectId, projectId + 1, projectId + 8, projectId + 2, projectId + 3, projectId + 4, projectId + 5);
        assertThat(requests.candidates(NOW.minusSeconds(1), 10)).extracting(ReviewRequestRepository.Request::projectId)
                .containsExactlyElementsOf(ready.subList(0, 4));
        assertThat(requests.candidates(NOW.plusSeconds(1), 10)).extracting(ReviewRequestRepository.Request::projectId)
                .containsExactly(projectId + 1, projectId, projectId + 2, projectId + 3, projectId + 4, projectId + 8, projectId + 5);
        assertThat(requests.candidates(NOW, 10)).extracting(ReviewRequestRepository.Request::state)
                .containsExactly("RUNNING", "QUEUED", "QUEUED", "RUNNING", "QUEUED");
        verifyNoInteractions(git, ai);
    }

    @Test void committedCompletionAndFreshRequestAreProtectedFromOldAcknowledgement() {
        requests.enqueueManual(projectId, "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        try (var lease = new PostgresProjectReviewLock(source).tryAcquire(projectId).orElseThrow()) {
            var claim = requests.claim(request, NOW);
            var original = reviews.project(projectId, false);
            transactions.executeWithoutResult(status -> requests.complete(claim, original, null, NOW.plusSeconds(1)));
            // Caller could lose the successful response here; persisted completion remains authoritative.
            var restarted = newRequests();
            assertThat(restarted.find(projectId).orElseThrow().state()).isEqualTo("SUCCEEDED");
            assertThat(restarted.candidates(NOW.plusSeconds(100), 10)).isEmpty();
            Boolean completedAcknowledged = transactions.execute(status -> requests.fail(claim, "late transport failure", NOW));
            assertThat(completedAcknowledged).isFalse();
            assertThat(restarted.enqueueManual(projectId, "owner", NOW.plusSeconds(2)))
                    .isEqualTo(ReviewRequestRepository.EnqueueResult.QUEUED);
            var fresh = restarted.find(projectId).orElseThrow();
            assertThat(fresh.requestId()).isNotEqualTo(claim.requestId());
            Boolean replacedAcknowledged = transactions.execute(status -> requests.fail(claim, "old request failure", NOW));
            assertThat(replacedAcknowledged).isFalse();
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> requests.complete(claim, original, null, NOW)))
                    .isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
            assertThat(restarted.find(projectId)).contains(fresh);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "DISABLED", "ADMIN_DOWNGRADED", "OWNER_CHANGED", "PAUSED" })
    void queuedManualAuthorityIsRecheckedBeforeAnyExternalCall(String change) {
        requests.enqueueManual(projectId, "ADMIN_DOWNGRADED".equals(change) ? "admin" : "owner", NOW);
        var request = requests.find(projectId).orElseThrow();
        switch (change) {
            case "DISABLED" -> jdbc.update("update app_user set enabled=false where id=1");
            case "ADMIN_DOWNGRADED" -> jdbc.update("update app_user set role='USER' where id=3");
            case "OWNER_CHANGED" -> jdbc.update("update project set owner_id=2 where id=?", projectId);
            case "PAUSED" -> jdbc.update("update project set status='PAUSED' where id=?", projectId);
            default -> throw new AssertionError(change);
        }
        coordinator(newRequests(), new PostgresProjectReviewLock(source)).processRequest(request);
        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(requests.find(projectId).orElseThrow().requestedBy()).isEqualTo(request.requestedBy());
        assertThat(count("reviewed_commit")).isZero();
        assertThat(cursor()).isNull();
        verifyNoInteractions(git, ai);
    }

    @Test void disablingManualActorDuringAiCancelsWithoutPersistingItsResult() {
        requests.enqueueManual(projectId, "owner", NOW);
        stubBatch(List.of(FIRST), FIRST.sha());
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            jdbc.update("update app_user set enabled=false where id=1");
            return result("Unauthorized late result");
        });
        coordinator(requests, new PostgresProjectReviewLock(source)).processRequest(requests.find(projectId).orElseThrow());
        assertThat(requests.find(projectId).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(count("reviewed_commit")).isZero();
        assertThat(count("review_issue")).isZero();
        assertThat(cursor()).isNull();
    }

    private ReviewRequestRepository newRequests() { return new ReviewRequestRepository(jdbc, reviews, transactionManager); }

    private ReviewCoordinator coordinator(ReviewRequestRepository queue, ProjectReviewLock lock) {
        return new ReviewCoordinator(reviews, lock, git, ai, transactionManager, 100, queue);
    }

    private void stubBatch(List<GitCommit> commits, String checkpoint) {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(commits, checkpoint));
    }

    private static ReviewResult result(String title) {
        return new ReviewResult("Synthetic review", List.of(new ReviewFinding("HIGH", title, "A.java", 1, "Synthetic evidence", "Inspect the synthetic input")));
    }

    private void createProject(long id, String suffix) {
        jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values(?,? ,?,'GITHUB','github.com',?,1,'APPROVED')",
                id, "Queue fixture " + suffix, "https://github.com/fixture/" + suffix, "fixture/" + suffix);
    }

    private long count(String table) {
        if (!Set.of("review_request", "review_run", "reviewed_commit", "review_issue", "audit_event").contains(table)) throw new IllegalArgumentException("Fixture table");
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private String cursor() { return jdbc.queryForObject("select last_reviewed_sha from project where id=?", String.class, projectId); }

    private void terminateOwnedSession(CapturedSessionDataSource tracked) {
        int pid = tracked.pid.get();
        assertThat(pid).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where pid=? and datname=current_database() and application_name=?", Integer.class,
                pid, schema)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select pg_terminate_backend(?)", Boolean.class, pid)).isTrue();
        // A successful terminate signal can precede session teardown; wait for that exact PID only.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from pg_stat_activity where pid=?", Integer.class, pid)).isZero());
    }

    private DriverManagerDataSource dataSource(String selectedSchema) {
        var result = new DriverManagerDataSource();
        result.setUrl(System.getenv("TEST_DATABASE_URL"));
        result.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        result.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "15");
        if (selectedSchema != null) {
            properties.setProperty("currentSchema", selectedSchema);
            properties.setProperty("ApplicationName", selectedSchema);
        }
        result.setConnectionProperties(properties);
        return result;
    }

    private static final class CapturedSessionDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger pid = new AtomicInteger();
        private CapturedSessionDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select pg_backend_pid()")) {
                rows.next();
                pid.set(rows.getInt(1));
            }
            return connection;
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Explicit credentials are not accepted by the session fixture");
        }
    }
}
