package com.aicreviewer.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRequestRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");
    private ReviewTestDatabase db;
    private ReviewRepository reviews;
    private ReviewRequestRepository requests;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        reviews = new ReviewRepository(db.jdbc);
        requests = new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager);
        tx = new TransactionTemplate(db.transactionManager);
    }
    @AfterEach void close() { db.close(); }

    @Test void duplicateManualRequestsKeepOriginalIdentityAndSurviveRepositoryRecreation() {
        assertThat(requests.enqueueManual(10, "owner", NOW)).isEqualTo(ReviewRequestRepository.EnqueueResult.QUEUED);
        var original = requests.find(10).orElseThrow();
        assertThat(requests.enqueueManual(10, "admin", NOW.plusSeconds(2))).isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        var restarted = new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager);
        assertThat(restarted.find(10)).contains(original);
        assertThat(restarted.candidates(NOW, 1)).containsExactly(original);
        assertThat(original.requestedBy()).isEqualTo(1L);
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'REVIEW_REQUESTED'", Integer.class)).isEqualTo(1);
    }

    @Test void unauthorizedOrUnapprovedManualRequestsNeverPersist() {
        assertThatThrownBy(() -> requests.enqueueManual(10, "other", NOW)).isInstanceOf(AccessDeniedException.class);
        db.jdbc.update("update project set status = 'PAUSED' where id = 10");
        assertThatThrownBy(() -> requests.enqueueManual(10, "admin", NOW)).isInstanceOf(ResponseStatusException.class);
        assertThat(db.count("review_request")).isZero();
        assertThat(db.count("audit_event")).isZero();
    }

    @Test void enqueueAuditFailureRollsBackTheAcceptanceAndScheduleAdvance() {
        db.jdbc.execute("alter table audit_event add constraint reject_request_fixture check (action <> 'REVIEW_REQUESTED')");
        assertThatThrownBy(() -> requests.enqueueScheduled(10, NOW, NOW.plusSeconds(3600))).isInstanceOf(RuntimeException.class);
        assertThat(db.count("review_request")).isZero();
        assertThat(db.jdbc.queryForObject("select next_review_at from project where id = 10", Timestamp.class)).isNull();
    }

    @Test void overdueScheduleCoalescesActiveRequestAndAdvancesOnlyOneFutureTick() {
        assertThat(requests.scheduledCandidates(NOW, 1)).containsExactly(10L);
        requests.enqueueManual(10, "owner", NOW.minusSeconds(10));
        var original = requests.find(10).orElseThrow();
        assertThat(requests.enqueueScheduled(10, NOW, NOW.plusSeconds(3600))).isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        assertThat(requests.find(10)).contains(original);
        assertThat(requests.scheduledCandidates(NOW, 1)).isEmpty();
        assertThat(requests.enqueueScheduled(10, NOW.plusSeconds(1), NOW.plusSeconds(7200))).isEqualTo(ReviewRequestRepository.EnqueueResult.SKIPPED);
        assertThat(db.jdbc.queryForObject("select next_review_at from project where id = 10", Timestamp.class).toInstant()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test void dueSelectionAndQueuePollingAreBoundedAndPreserveFairOrdering() {
        addProject(11, "APPROVED");
        addProject(12, "PAUSED");
        requests.enqueueManual(11, "owner", NOW.minusSeconds(5));
        requests.enqueueManual(10, "owner", NOW);
        assertThat(requests.candidates(NOW, 1)).extracting(ReviewRequestRepository.Request::projectId).containsExactly(11L);
        requests.deferBusy(requests.find(11).orElseThrow(), NOW.plusSeconds(30));
        assertThat(requests.candidates(NOW, 10)).extracting(ReviewRequestRepository.Request::projectId).containsExactly(10L);
        assertThat(requests.scheduledCandidates(NOW, 10)).containsExactly(10L, 11L);
        for (int invalid : new int[] {0, -1, ReviewRequestRepository.MAX_CANDIDATES + 1}) {
            assertThatThrownBy(() -> requests.candidates(NOW, invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> requests.scheduledCandidates(NOW, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void coalescingAFullPageOfActiveProjectsMakesLaterDueProjectsReachable() {
        var projectRows = new java.util.ArrayList<Object[]>();
        var requestRows = new java.util.ArrayList<Object[]>();
        for (long id = 10; id <= 1010; id++) {
            if (id != 10) projectRows.add(new Object[] {id, "Project " + id, "https://github.com/org/p" + id, "org/p" + id});
            if (id < 1010) requestRows.add(new Object[] {id, new java.util.UUID(0, id).toString(), Timestamp.from(NOW), Timestamp.from(NOW)});
        }
        db.jdbc.batchUpdate("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values (?,?,?,'GITHUB','github.com',?,1,'APPROVED')", projectRows);
        db.jdbc.batchUpdate("insert into review_request(project_id,request_id,state,source,requested_at,available_at) values (?,?,'QUEUED','SCHEDULED',?,?)", requestRows);
        var firstPage = requests.scheduledCandidates(NOW, 1000);
        assertThat(firstPage).hasSize(1000).doesNotContain(1010L);
        for (long project : firstPage) {
            assertThat(requests.enqueueScheduled(project, NOW, NOW.plusSeconds(3600)))
                    .isEqualTo(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        }
        assertThat(requests.scheduledCandidates(NOW, 1000)).containsExactly(1010L);
    }

    @Test void scheduledCandidatesPreserveNullFirstAndDueTimeOrderAcrossLimitBoundaries() {
        // Insert out of order so the contract cannot rely on insertion or project ID alone.
        for (long id : new long[] {18, 17, 16, 15, 14, 13, 12, 11}) {
            addProject(id, id == 15 || id == 16 ? "PAUSED" : "APPROVED");
        }
        db.jdbc.update("update project set next_review_at = ? where id in (11, 12)", Timestamp.from(NOW.minusSeconds(30)));
        db.jdbc.update("update project set next_review_at = ? where id = 13", Timestamp.from(NOW.minusSeconds(60)));
        db.jdbc.update("update project set next_review_at = ? where id = 14", Timestamp.from(NOW.plusSeconds(1)));
        db.jdbc.update("update project set next_review_at = ? where id = 16", Timestamp.from(NOW.minusSeconds(120)));
        db.jdbc.update("update project set next_review_at = ? where id = 18", Timestamp.from(NOW));

        // NULL schedules precede even the oldest due time; equal due times use ID.
        // Future and paused projects are absent, including paused NULL schedules.
        var expected = java.util.List.of(10L, 17L, 13L, 11L, 12L, 18L);
        for (int limit = 1; limit <= expected.size() + 1; limit++) {
            assertThat(requests.scheduledCandidates(NOW, limit))
                    .containsExactlyElementsOf(expected.subList(0, Math.min(limit, expected.size())));
        }

        // Once initial schedules move into the future, the due range alone still
        // respects the limit, its date order, and the tie boundary between 11/12.
        db.jdbc.update("update project set next_review_at = ? where id in (10, 17)", Timestamp.from(NOW.plusSeconds(3600)));
        assertThat(requests.scheduledCandidates(NOW, 1)).containsExactly(13L);
        assertThat(requests.scheduledCandidates(NOW, 2)).containsExactly(13L, 11L);
        assertThat(requests.scheduledCandidates(NOW, 10)).containsExactly(13L, 11L, 12L, 18L);
    }

    @Test void recoveringRunningRequestFencesEveryLateMutationOfPreviousAttempt() {
        requests.enqueueManual(10, "owner", NOW);
        var snapshot = requests.find(10).orElseThrow();
        var first = requests.claim(snapshot, NOW);
        var replacement = requests.claim(snapshot, NOW.plusSeconds(1));
        assertThat(first.claimToken()).isNotEqualTo(replacement.claimToken());
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.guard(first)))
                .isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.complete(first, reviews.project(10, false), null, NOW)))
                .isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
        Boolean obsoleteAcknowledged = tx.execute(status -> requests.fail(first, "Old worker failed", NOW));
        assertThat(obsoleteAcknowledged).isFalse();
        assertThat(requests.find(10).orElseThrow().runId()).isEqualTo(replacement.runId());
        assertThat(requests.find(10).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(db.jdbc.queryForList("select status from review_run order by id", String.class)).containsExactly("FAILED", "RUNNING");
    }

    @Test void readyCandidatesMergeRunningAndQueuedStatesInOneGlobalOrder() {
        addProject(11, "APPROVED");
        addProject(12, "APPROVED");
        requests.enqueueManual(10, "owner", NOW.minusSeconds(40));
        requests.enqueueManual(11, "owner", NOW);
        requests.enqueueManual(12, "owner", NOW.minusSeconds(1));
        // Both queue states are eligible at the exact retry boundary. Equal times
        // keep original reception order and project ID as deterministic tie breakers.
        requests.claim(requests.find(10).orElseThrow(), NOW.minusSeconds(30));
        assertThat(requests.candidates(NOW, 2)).extracting(ReviewRequestRepository.Request::projectId)
                .containsExactly(12L, 10L);
        assertThat(requests.candidates(NOW, 3)).extracting(ReviewRequestRepository.Request::projectId)
                .containsExactly(12L, 10L, 11L);
    }

    @Test void readyCandidatesRebindBothStateLimitsWhenTheRequestedPageSizeChanges() {
        for (long id = 10; id <= 15; id++) {
            if (id != 10) addProject(id, "APPROVED");
            Instant available = NOW.minusSeconds(60).plusSeconds(id - 10);
            if (id % 2 == 0) {
                requests.enqueueManual(id, "owner", available);
            } else {
                requests.enqueueManual(id, "owner", available.minusSeconds(60));
                requests.claim(requests.find(id).orElseThrow(), available.minusSeconds(30));
            }
        }
        var expected = java.util.List.of(10L, 11L, 12L, 13L, 14L, 15L);
        for (int limit : new int[] {1, 2, 3, 4, 5, 6, 7, 2, 1, 6}) {
            assertThat(requests.candidates(NOW, limit)).extracting(ReviewRequestRepository.Request::projectId)
                    .containsExactlyElementsOf(expected.subList(0, Math.min(limit, expected.size())));
        }
    }

    @Test void repeatedRecoveryBacksOffToOneHourWithoutBusyPollsShorteningTheDelay() {
        requests.enqueueManual(10, "owner", NOW);
        long[] delays = {30, 60, 120, 240, 480, 960, 1920, 3600, 3600};
        for (int attempt = 0; attempt < delays.length; attempt++) {
            Instant started = NOW.plusSeconds(attempt);
            requests.claim(requests.find(10).orElseThrow(), started);
            var request = requests.find(10).orElseThrow();
            assertThat(request.attemptCount()).isEqualTo(attempt + 1);
            assertThat(request.availableAt()).isEqualTo(started.plusSeconds(delays[attempt]));
            assertThat(requests.candidates(request.availableAt().minusSeconds(1), 1)).isEmpty();
            requests.deferBusy(request, started.plusSeconds(30));
            assertThat(requests.find(10).orElseThrow().availableAt()).isEqualTo(request.availableAt());
            assertThat(requests.candidates(request.availableAt(), 1)).hasSize(1);
        }
    }

    @Test void finalRequestRunAndCheckpointAcknowledgementRollbackTogether() {
        requests.enqueueManual(10, "owner", NOW);
        var claim = requests.claim(requests.find(10).orElseThrow(), NOW);
        String sha = "a".repeat(40);
        db.jdbc.update("insert into reviewed_commit(project_id, commit_sha, summary) values (10, ?, 'Stored result')", sha);
        db.jdbc.execute("alter table review_request add constraint reject_completion_fixture check (state <> 'SUCCEEDED')");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.complete(claim, reviews.project(10, false), sha, NOW)))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.cursor()).isNull();
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select status from review_run where id = ?", String.class, claim.runId())).isEqualTo("RUNNING");
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'REVIEW_SUCCEEDED'", Integer.class)).isZero();
    }

    @Test void completedRequestCanBeReplacedButItsOldSnapshotCannotClaimOrDeferNewRequest() {
        requests.enqueueManual(10, "owner", NOW);
        var first = requests.find(10).orElseThrow();
        var claim = requests.claim(first, NOW);
        tx.executeWithoutResult(status -> requests.complete(claim, reviews.project(10, false), null, NOW));
        assertThat(requests.isActive(10)).isFalse();
        assertThat(requests.enqueueManual(10, "owner", NOW.plusSeconds(10))).isEqualTo(ReviewRequestRepository.EnqueueResult.QUEUED);
        var next = requests.find(10).orElseThrow();
        assertThat(next.requestId()).isNotEqualTo(first.requestId());
        assertThat(requests.claim(first, NOW)).isNull();
        requests.deferBusy(first, NOW.plusSeconds(999));
        assertThat(requests.find(10)).contains(next);
    }

    @Test void changedProjectOrDisabledRequesterCancelsBeforeAnyRunStarts() {
        requests.enqueueManual(10, "owner", NOW);
        db.jdbc.update("update app_user set enabled = false where id = 1");
        assertThat(requests.claim(requests.find(10).orElseThrow(), NOW)).isNull();
        assertThat(requests.find(10).orElseThrow()).satisfies(request -> {
            assertThat(request.state()).isEqualTo("CANCELLED");
            assertThat(request.resultCode()).isEqualTo("REQUESTER_INELIGIBLE");
        });
        assertThat(db.count("review_run")).isZero();
        db.jdbc.update("update app_user set enabled = true where id = 1");
        requests.enqueueManual(10, "owner", NOW);
        db.jdbc.update("update project set status = 'PAUSED' where id = 10");
        assertThat(requests.claim(requests.find(10).orElseThrow(), NOW)).isNull();
        assertThat(requests.find(10).orElseThrow().resultCode()).isEqualTo("PROJECT_INELIGIBLE");
    }

    @Test void requesterRoleAndOwnershipAreRecheckedForAnAlreadyRunningAttempt() {
        requests.enqueueManual(10, "admin", NOW);
        var claim = requests.claim(requests.find(10).orElseThrow(), NOW);
        db.jdbc.update("update app_user set role = 'USER' where id = 3");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.guard(claim)))
                .isInstanceOf(ReviewRequestRepository.RequestCancelledException.class);
        tx.executeWithoutResult(status -> requests.fail(claim, "Request cancelled", NOW));
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(db.jdbc.queryForObject("select status from review_run where id = ?", String.class, claim.runId())).isEqualTo("FAILED");
    }

    @Test void guardAndAcknowledgementsCannotBeSeparatedFromTheirDatabaseTransaction() {
        requests.enqueueManual(10, "owner", NOW);
        var request = requests.find(10).orElseThrow();
        var claim = requests.claim(request, NOW);
        assertThatThrownBy(() -> requests.guard(claim)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requests.complete(claim, reviews.project(10, false), null, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requests.fail(claim, "Failure", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.claim(request, NOW))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> requests.enqueueManual(10, "owner", NOW))).isInstanceOf(IllegalStateException.class);
        assertThat(requests.find(10).orElseThrow().state()).isEqualTo("RUNNING");
    }

    private void addProject(long id, String state) {
        db.jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values (?, ?, ?, 'GITHUB','github.com',?,1,?)",
                id, "Project " + id, "https://github.com/org/p" + id, "org/p" + id, state);
    }
}
