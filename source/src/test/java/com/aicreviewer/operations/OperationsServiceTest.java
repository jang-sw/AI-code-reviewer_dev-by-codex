package com.aicreviewer.operations;

import com.aicreviewer.identity.AuditEventWriter;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ReviewTestDatabase;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-26T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private ReviewTestDatabase db;
    private UserAccountService users;
    private OperationsService operations;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), new AuditEventWriter(db.jdbc));
        operations = new OperationsService(db.jdbc, users, 120, CLOCK);
        db.jdbc.update("UPDATE project SET status='PAUSED' WHERE id=10");
    }

    @AfterEach void close() { db.close(); }

    @Test
    void currentFailedRunIsReportedButOlderFailureDisappearsAfterSuccessOrNewAttempt() {
        project(20, "APPROVED");
        run(20, "FAILED", NOW.minusSeconds(3600));
        assertThat(ids("FAILED")).containsExactly(20L);
        run(20, "SUCCEEDED", NOW.minusSeconds(600));
        assertThat(ids("FAILED")).isEmpty();
        assertThat(ids("ATTENTION")).isEmpty();
        run(20, "FAILED", NOW.minusSeconds(500));
        assertThat(ids("FAILED")).containsExactly(20L);
        run(20, "RUNNING", NOW.minusSeconds(100));
        assertThat(ids("FAILED")).isEmpty();
        assertThat(ids("ATTENTION")).isEmpty();
    }

    @Test
    void latestMeansNewestRunIdConsistentWithReviewHistoryEvenIfClockMovedBackward() {
        project(20, "APPROVED");
        run(20, "FAILED", NOW.minusSeconds(60));
        run(20, "SUCCEEDED", NOW.minusSeconds(8000));
        assertThat(ids("FAILED")).isEmpty();
        var observation = operations.list("admin", "STALE", 0).projects().getFirst();
        assertThat(observation.runStatus()).isEqualTo("SUCCEEDED");
        assertThat(observation.elapsedMinutes()).isEqualTo(133);
    }

    @Test
    void neverRunAndElapsedFiltersOnlyIncludeApprovedProjectsButFailuresKeepPausedProjectsVisible() {
        project(20, "APPROVED");
        project(21, "PENDING");
        project(22, "REJECTED");
        project(23, "PAUSED");
        assertThat(ids("NEVER_RUN")).containsExactly(20L);
        assertThat(ids("ATTENTION")).containsExactly(20L);
        for (long id : new long[] {20, 21, 22, 23}) run(id, "SUCCEEDED", NOW.minusSeconds(9000));
        assertThat(ids("NEVER_RUN")).isEmpty();
        assertThat(ids("STALE")).containsExactly(20L);
        run(23, "FAILED", NOW.minusSeconds(30));
        assertThat(ids("FAILED")).containsExactly(23L);
        assertThat(ids("ATTENTION")).containsExactly(20L, 23L);
    }

    @Test
    void elapsedThresholdIncludesExactBoundaryAndLongRunningRecordsWithoutDeclaringThemInterrupted() {
        project(20, "APPROVED");
        project(21, "APPROVED");
        project(22, "APPROVED");
        run(20, "SUCCEEDED", NOW.minusSeconds(7200));
        run(21, "SUCCEEDED", NOW.minusSeconds(7199));
        run(22, "RUNNING", NOW.minusSeconds(7201));
        var page = operations.list("admin", "STALE", 0);
        assertThat(page.observedAt()).isEqualTo(NOW);
        assertThat(page.staleAfterMinutes()).isEqualTo(120);
        assertThat(page.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(22L, 20L);
        assertThat(page.projects()).allSatisfy(row -> {
            assertThat(row.stale()).isTrue();
            assertThat(row.elapsedMinutes()).isEqualTo(120);
            assertThat(row.failed()).isFalse();
        });
        assertThat(page.projects().getFirst().runStatus()).isEqualTo("RUNNING");
    }

    @Test
    void absentOrFutureRunTimestampsDoNotInventElapsedTime() {
        project(20, "APPROVED");
        project(21, "APPROVED");
        run(21, "FAILED", NOW.plusSeconds(600));
        var rows = operations.list("admin", "ATTENTION", 0).projects();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().neverRun()).isTrue();
        assertThat(rows.getFirst().elapsedMinutes()).isNull();
        assertThat(rows.get(1).elapsedMinutes()).isZero();
        assertThat(rows.get(1).stale()).isFalse();
        assertThat(ids("STALE")).isEmpty();
    }

    @Test
    void pageContainsFiftyRowsPlusLookaheadWithoutDuplicatesAndKeepsFilter() {
        for (long id = 100; id < 152; id++) project(id, "APPROVED");
        project(152, "PAUSED");
        var first = operations.list("admin", "NEVER_RUN", 0);
        var second = operations.list("admin", "NEVER_RUN", 1);
        assertThat(first.projects()).hasSize(50);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(150L, 151L);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.filter()).isEqualTo("NEVER_RUN");
        assertThat(second.page()).isEqualTo(1);
        assertThat(first.projects()).extracting(OperationsService.ProjectObservation::projectId)
                .doesNotContainAnyElementsOf(second.projects().stream().map(OperationsService.ProjectObservation::projectId).toList());
        assertThatThrownBy(() -> first.projects().clear()).isInstanceOf(UnsupportedOperationException.class);
        var maximum = operations.list("admin", "NEVER_RUN", 10000);
        assertThat(maximum.projects()).isEmpty();
        assertThat(maximum.hasNext()).isFalse();
    }

    @Test
    void resultContainsNoErrorSourceProviderOrRepositoryAddressAndDoesNotMutateRows() {
        project(20, "APPROVED");
        run(20, "FAILED", NOW.minusSeconds(10));
        db.jdbc.update("UPDATE review_run SET error_message='private error fixture' WHERE project_id=20");
        var projects = db.jdbc.queryForList("SELECT * FROM project ORDER BY id");
        var runs = db.jdbc.queryForList("SELECT * FROM review_run ORDER BY id");
        var result = operations.list("admin", "FAILED", 0);
        assertThat(result.toString()).doesNotContain("private error fixture", "github.com", "GITHUB", "password", "repositoryUrl");
        assertThat(db.jdbc.queryForList("SELECT * FROM project ORDER BY id")).isEqualTo(projects);
        assertThat(db.jdbc.queryForList("SELECT * FROM review_run ORDER BY id")).isEqualTo(runs);
        assertThat(db.count("audit_event")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "author", "other"})
    void serviceRejectsOrdinaryUsersBeforeReadingOperations(String username) {
        JdbcTemplate forbidden = mock(JdbcTemplate.class);
        var secured = new OperationsService(forbidden, users, 120, CLOCK);
        assertThatThrownBy(() -> secured.list(username, "ATTENTION", 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(forbidden);
    }

    @Test
    void disabledAdminCannotReadOperations() {
        db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE username='admin'");
        assertThatThrownBy(() -> operations.list("admin", "ATTENTION", 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void invalidFiltersAndPageBoundsAreRejected() {
        for (String filter : new String[] {null, "", "failed", "ALL", "FAILED' OR 1=1 --", "STALE\n"}) {
            assertBadRequest(() -> operations.list("admin", filter, 0));
        }
        for (int page : new int[] {-1, 10001, Integer.MAX_VALUE}) assertBadRequest(() -> operations.list("admin", "ATTENTION", page));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 10081, Integer.MAX_VALUE})
    void invalidElapsedThresholdCannotStart(int threshold) {
        assertThatThrownBy(() -> new OperationsService(db.jdbc, users, threshold, CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseFailurePropagatesInsteadOfReportingAnEmptyHealthyList() {
        db.jdbc.execute("ALTER TABLE project DROP COLUMN name");
        assertThatThrownBy(() -> operations.list("admin", "ATTENTION", 0)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void durableQueueHasIndependentAgeAndIncludesPausedRequestsUntilWorkerCancellation() {
        project(20, "APPROVED");
        project(21, "APPROVED");
        project(22, "PAUSED");
        run(20, "SUCCEEDED", NOW.minusSeconds(30));
        run(21, "FAILED", NOW.minusSeconds(10));
        queued(20, NOW.minusSeconds(7200));
        queued(21, NOW.plusSeconds(60));
        queued(22, NOW.minusSeconds(7201));
        assertThat(ids("QUEUED")).containsExactly(22L, 20L, 21L);
        assertThat(ids("REQUEST_DELAYED")).containsExactly(22L, 20L);
        var late = operations.list("admin", "REQUEST_DELAYED", 0).projects().get(1);
        assertThat(late.requestState()).isEqualTo("QUEUED");
        assertThat(late.requestElapsedMinutes()).isEqualTo(120);
        assertThat(late.elapsedMinutes()).isZero();
        assertThat(late.requestDelayed()).isTrue();
        assertThat(late.stale()).isFalse();
        assertThat(ids("ATTENTION")).contains(20L, 21L, 22L);
        var future = operations.list("admin", "QUEUED", 0).projects().getLast();
        assertThat(future.requestElapsedMinutes()).isZero();
        assertThat(future.requestDelayed()).isFalse();
        assertThat(future.failed()).isTrue(); // Earlier failed execution is distinct from the new queue request.
        db.jdbc.update("UPDATE review_request SET state='CANCELLED',finished_at=?,result_code='PROJECT_INELIGIBLE' WHERE project_id=22", Timestamp.from(NOW));
        assertThat(ids("QUEUED")).containsExactly(20L, 21L);
        assertThat(ids("REQUEST_DELAYED")).containsExactly(20L);
    }

    @Test
    void oldRunningRequestStaysVisibleDespiteRecentRecoveryAttemptAndIsNotReportedAsQueued() {
        project(20, "APPROVED");
        queued(20, NOW.minusSeconds(7200));
        var queued = operations.list("admin", "QUEUED", 0).projects().getFirst();
        assertThat(queued.neverRun()).isTrue();
        assertThat(queued.elapsedMinutes()).isNull();
        assertThat(queued.requestElapsedMinutes()).isEqualTo(120);
        run(20, "RUNNING", NOW.minusSeconds(10));
        Long runId = db.jdbc.queryForObject("SELECT id FROM review_run WHERE project_id=20", Long.class);
        db.jdbc.update("UPDATE review_request SET state='RUNNING',claim_token='private-token-fixture',run_id=?,last_attempt_at=?,attempt_count=2 WHERE project_id=20", runId, Timestamp.from(NOW.minusSeconds(10)));
        assertThat(ids("QUEUED")).isEmpty();
        assertThat(ids("REQUEST_DELAYED")).containsExactly(20L);
        assertThat(ids("ATTENTION")).containsExactly(20L);
        var delayed = operations.list("admin", "REQUEST_DELAYED", 0).projects().getFirst();
        assertThat(delayed.requestState()).isEqualTo("RUNNING");
        assertThat(delayed.requestDelayed()).isTrue();
        assertThat(delayed.elapsedMinutes()).isZero();
        assertThat(delayed.requestElapsedMinutes()).isEqualTo(120);
        assertThat(delayed.stale()).isFalse();
        var result = operations.list("admin", "STALE", 0);
        assertThat(result.projects()).isEmpty();
        db.jdbc.update("UPDATE review_run SET started_at=? WHERE id=?", Timestamp.from(NOW.minusSeconds(7200)), runId);
        var running = operations.list("admin", "STALE", 0).projects().getFirst();
        assertThat(running.requestState()).isEqualTo("RUNNING");
        assertThat(running.requestDelayed()).isTrue();
        assertThat(running.toString()).doesNotContain("private-token-fixture", "claim_token", "requested_by");
    }

    @Test
    void queueFiltersKeepFiftyRowBoundAndStablePages() {
        for (long id = 100; id < 152; id++) {
            project(id, "APPROVED");
            queued(id, NOW.minusSeconds(7200));
        }
        for (String filter : new String[] {"QUEUED", "REQUEST_DELAYED"}) {
            var first = operations.list("admin", filter, 0);
            var second = operations.list("admin", filter, 1);
            assertThat(first.projects()).hasSize(50);
            assertThat(first.hasNext()).isTrue();
            assertThat(second.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(150L, 151L);
            assertThat(second.hasNext()).isFalse();
            assertThat(second.filter()).isEqualTo(filter);
            assertThat(operations.list("admin", filter, 10000).projects()).isEmpty();
        }
    }

    @Test
    void delayedFilterCombinesQueuedAndRecentlyReclaimedRunningRequestsWithStablePaging() {
        for (long id = 100; id < 152; id++) {
            project(id, "APPROVED");
            queued(id, NOW.minusSeconds(7200));
            if (id % 2 == 1) {
                run(id, "RUNNING", NOW.minusSeconds(1));
                Long runId = db.jdbc.queryForObject("SELECT id FROM review_run WHERE project_id=?", Long.class, id);
                db.jdbc.update("UPDATE review_request SET state='RUNNING',claim_token=?,run_id=?,attempt_count=3,last_attempt_at=? WHERE project_id=?",
                        java.util.UUID.randomUUID().toString(), runId, Timestamp.from(NOW.minusSeconds(1)), id);
            }
        }
        var first = operations.list("admin", "REQUEST_DELAYED", 0);
        var second = operations.list("admin", "REQUEST_DELAYED", 1);
        assertThat(first.projects()).hasSize(50).allSatisfy(row -> assertThat(row.requestDelayed()).isTrue());
        assertThat(first.hasNext()).isTrue();
        assertThat(second.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(150L, 151L);
        assertThat(second.projects()).extracting(OperationsService.ProjectObservation::requestState).containsExactly("QUEUED", "RUNNING");
        assertThat(second.hasNext()).isFalse();
        assertThat(operations.list("admin", "QUEUED", 0).projects()).hasSize(26);
        db.jdbc.update("UPDATE review_request SET requested_at=? WHERE project_id=151", Timestamp.from(NOW.minusSeconds(7199)));
        assertThat(operations.list("admin", "REQUEST_DELAYED", 1).projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(150L);
        assertThat(ids("ATTENTION")).doesNotContain(151L);
    }

    @Test
    void rateLimitFilterKeepsPastDueRequestsButExcludesNormalQueueAndRunningRecovery() {
        for (long id = 20; id <= 24; id++) {
            project(id, "APPROVED");
            queued(id, NOW.minusSeconds(120));
        }
        rateLimit(20, "GIT_RATE_LIMITED", NOW.plusSeconds(300));
        rateLimit(21, "AI_RATE_LIMITED", NOW.minusSeconds(30));
        rateLimit(22, "private-provider-error", NOW.plusSeconds(300));
        rateLimit(23, "AI_RATE_LIMITED", NOW.plusSeconds(300));
        run(23, "RUNNING", NOW.minusSeconds(10));
        Long runningId = db.jdbc.queryForObject("SELECT id FROM review_run WHERE project_id=23", Long.class);
        db.jdbc.update("UPDATE review_request SET state='RUNNING',claim_token='private-token',run_id=? WHERE project_id=23", runningId);
        rateLimit(24, "RATE_LIMIT_EXHAUSTED", NOW.plusSeconds(300));
        db.jdbc.update("UPDATE review_request SET state='FAILED',finished_at=? WHERE project_id=24", Timestamp.from(NOW));
        var limited = operations.list("admin", "RATE_LIMITED", 0);
        assertThat(limited.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(20L, 21L);
        assertThat(limited.projects()).allSatisfy(row -> {
            assertThat(row.rateLimited()).isTrue();
            assertThat(row.requestState()).isEqualTo("QUEUED");
            assertThat(row.rateLimitExhausted()).isFalse();
        });
        assertThat(limited.projects().getFirst().rateLimitLabel()).isEqualTo("Git 서버 호출 제한");
        assertThat(limited.projects().getFirst().retryAtLabel()).isEqualTo("2026-09-26 02:05:00");
        assertThat(limited.projects().getLast().rateLimitLabel()).isEqualTo("AI 서비스 호출 제한");
        assertThat(limited.projects().getLast().retryAt()).isEqualTo(NOW.minusSeconds(30));
        var normal = operations.list("admin", "QUEUED", 0).projects().getLast();
        assertThat(normal.projectId()).isEqualTo(22L);
        assertThat(normal.rateLimited()).isFalse();
        assertThat(normal.retryAt()).isNull();
        assertThat(normal.retryAtLabel()).isEmpty();
        assertThat(normal.toString()).doesNotContain("private-provider-error");
        var exhausted = operations.list("admin", "NEVER_RUN", 0).projects().stream().filter(row -> row.projectId() == 24).findFirst().orElseThrow();
        assertThat(exhausted.rateLimitExhausted()).isTrue();
        assertThat(exhausted.rateLimited()).isFalse();
        assertThat(exhausted.retryAt()).isNull();
        // Starting the next claim clears the rate-limit marker; available_at is still a recovery hint.
        db.jdbc.update("UPDATE review_request SET result_code=NULL WHERE project_id=20");
        assertThat(ids("RATE_LIMITED")).containsExactly(21L);
    }

    @Test
    void rateLimitedPagesKeepFiftyRowsAndOriginalDelayAndAttentionSemantics() {
        for (long id = 100; id < 152; id++) {
            project(id, "APPROVED");
            queued(id, NOW.minusSeconds(7200));
            rateLimit(id, id % 2 == 0 ? "GIT_RATE_LIMITED" : "AI_RATE_LIMITED", NOW.plusSeconds(600));
            run(id, "FAILED", NOW.minusSeconds(30));
        }
        var first = operations.list("admin", "RATE_LIMITED", 0);
        var second = operations.list("admin", "RATE_LIMITED", 1);
        assertThat(first.projects()).hasSize(50).allSatisfy(row -> {
            assertThat(row.rateLimited()).isTrue();
            assertThat(row.failed()).isTrue();
            assertThat(row.requestDelayed()).isTrue();
        });
        assertThat(first.hasNext()).isTrue();
        assertThat(second.projects()).extracting(OperationsService.ProjectObservation::projectId).containsExactly(150L, 151L);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.filter()).isEqualTo("RATE_LIMITED");
        assertThat(operations.list("admin", "RATE_LIMITED", 10000).projects()).isEmpty();
        for (String filter : new String[] {"QUEUED", "REQUEST_DELAYED", "FAILED", "ATTENTION"}) {
            assertThat(ids(filter)).hasSize(50);
        }
        assertBadRequest(() -> operations.list("admin", "RATE_LIMITED", -1));
        assertBadRequest(() -> operations.list("admin", "RATE_LIMITED", 10001));
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "author", "other"})
    void ordinaryUsersCannotQueryRateLimitStatusAtTheServiceBoundary(String username) {
        JdbcTemplate forbidden = mock(JdbcTemplate.class);
        var secured = new OperationsService(forbidden, users, 120, CLOCK);
        assertThatThrownBy(() -> secured.list(username, "RATE_LIMITED", 0))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(forbidden);
    }

    private void rateLimit(long projectId, String code, Instant retryAt) {
        db.jdbc.update("UPDATE review_request SET result_code=?,available_at=? WHERE project_id=?", code, Timestamp.from(retryAt), projectId);
    }

    private void queued(long projectId, Instant requested) {
        db.jdbc.update("INSERT INTO review_request(project_id,request_id,state,source,requested_at,available_at) VALUES(?,?,'QUEUED','SCHEDULED',?,?)",
                projectId, java.util.UUID.randomUUID().toString(), Timestamp.from(requested), Timestamp.from(requested));
    }

    private List<Long> ids(String filter) {
        return operations.list("admin", filter, 0).projects().stream().map(OperationsService.ProjectObservation::projectId).toList();
    }
    private void project(long id, String status) {
        db.jdbc.update("INSERT INTO project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) VALUES(?,? ,?,'GITHUB','github.com',?,1,?)",
                id, "Project " + id, "https://github.com/org/operations-" + id, "org/operations-" + id, status);
    }
    private void run(long projectId, String status, Instant started) {
        db.jdbc.update("INSERT INTO review_run(project_id,status,started_at) VALUES(?,?,?)", projectId, status, Timestamp.from(started));
    }
    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
