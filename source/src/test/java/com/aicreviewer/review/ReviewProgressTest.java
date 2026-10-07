package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.ManualReviewFile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewProgressTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final GitCommit FIRST = new GitCommit("a".repeat(40), null, "First", "first diff");
    private static final GitCommit SECOND = new GitCommit("b".repeat(40), null, "Second", "second diff");
    private ReviewTestDatabase db;
    private ReviewRepository reviews;
    private ReviewRequestRepository requests;
    private TransactionTemplate tx;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ReviewCoordinator coordinator;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        reviews = new ReviewRepository(db.jdbc);
        requests = spy(new ReviewRequestRepository(db.jdbc, reviews, db.transactionManager));
        tx = new TransactionTemplate(db.transactionManager);
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        var locks = mock(ProjectReviewLock.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(mock(ProjectReviewLock.Lease.class)));
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(FIRST), FIRST.sha()));
        when(ai.review(any())).thenReturn(result());
        coordinator = new ReviewCoordinator(reviews, locks, git, ai, db.transactionManager, 100, requests);
        requests.enqueueManual(10, "owner", NOW);
    }

    @AfterEach void close() { Thread.interrupted(); db.close(); }

    @Test void claimedAttemptStartsWithKnownPreparationAndNoInventedSave() {
        assertThat(requests.progress(request())).isEmpty();
        claim();
        assertThat(progress()).isEqualTo(new ReviewProgress("PREPARING", NOW, 0, null));
        assertThat(progress().stageLabel()).isEqualTo("저장된 리뷰 기록 확인");
        assertThat(progress().recordedAtLabel()).isEqualTo("2026-01-01 00:00:00");
        assertThat(progress().lastSavedAtLabel()).isEmpty();
    }

    @Test void stagesPrecedeExternalCallsAndFinalizationWithoutExposingUnstoredResults() {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            assertThat(progress().stage()).isEqualTo("GIT_LOADING");
            assertThat(progress().savedCommits()).isZero();
            assertThat(progress().lastSavedAt()).isNull();
            return new GitReviewBatch(List.of(FIRST), FIRST.sha());
        });
        when(ai.review(FIRST)).thenAnswer(invocation -> {
            assertThat(progress().stage()).isEqualTo("REVIEWING");
            assertThat(progress().savedCommits()).isZero();
            assertThat(progress().lastSavedAt()).isNull();
            return result();
        });
        doAnswer(invocation -> {
            assertThat(progress().stage()).isEqualTo("FINALIZING");
            assertThat(progress().savedCommits()).isEqualTo(1);
            assertThat(progress().lastSavedAt()).isNotNull();
            return invocation.callRealMethod();
        }).when(requests).complete(any(), any(), any(), any());

        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(progress().savedCommits()).isEqualTo(1);
        assertThat(progress().lastSavedAt()).isEqualTo(db.jdbc.queryForObject("select reviewed_at from reviewed_commit", Timestamp.class).toInstant());
        assertThat(request().state()).isEqualTo("SUCCEEDED");
    }

    @Test void partialMergeCountsEmptyAndManualSavedCommitsWithoutClaimingCheckpointOrHumanCompletion() {
        var empty = new GitCommit(FIRST.sha(), null, null, "Empty", "", "EMPTY", "Verified no changed files");
        var manual = new GitCommit(SECOND.sha(), null, null, "Binary", "", "MANUAL_ONLY", "Verified complete change manifest",
                List.of(new ManualReviewFile("asset.bin", null, "c".repeat(40), null, "100644", "GIT_DIFF_BUDGET")));
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(empty, manual), null));
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(progress().savedCommits()).isEqualTo(2);
        assertThat(progress().lastSavedAt()).isNotNull();
        assertThat(db.cursor()).isNull();
        assertThat(db.jdbc.queryForObject("select status from review_issue", String.class)).isEqualTo("OPEN");
        verifyNoInteractions(ai);
    }

    @Test void failedCommitTransactionRollsBackIssueCountAndLastSavedTimeTogether() {
        var claim = claim();
        assertThat(persist(claim, FIRST, NOW.plusSeconds(1))).isTrue();
        var before = progress();
        db.jdbc.execute("alter table review_run add constraint reject_second_progress check (reviewed_commits <= 1)");
        assertThatThrownBy(() -> persist(claim, SECOND, NOW.plusSeconds(2))).isInstanceOf(DataAccessException.class);
        assertThat(progress()).isEqualTo(before);
        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.count("review_issue")).isEqualTo(1);
    }

    @Test void duplicateSavedCommitNeverRefreshesCountOrTimestamp() {
        var claim = claim();
        assertThat(persist(claim, FIRST, NOW.plusSeconds(1))).isTrue();
        var before = progress();
        assertThat(persist(claim, FIRST, NOW.plusSeconds(100))).isFalse();
        assertThat(progress()).isEqualTo(before);
        assertThat(db.count("review_issue")).isEqualTo(1);
    }

    @Test void stageDatabaseFailureLeavesDurableRequestRecoverableBeforeAi() {
        db.jdbc.execute("alter table review_run add constraint reject_reviewing_progress check (progress_stage <> 'REVIEWING')");
        assertThatThrownBy(() -> coordinator.processRequest(request())).isInstanceOf(DataAccessException.class);
        assertThat(request().state()).isEqualTo("RUNNING");
        assertThat(progress().stage()).isEqualTo("GIT_LOADING");
        assertThat(progress().savedCommits()).isZero();
        assertThat(progress().lastSavedAt()).isNull();
        verifyNoInteractions(ai);
    }

    @Test void replacementClaimRejectsLateStageUpdateAndStartsFreshAttemptCounts() {
        var oldClaim = claim();
        persist(oldClaim, FIRST, NOW.plusSeconds(1));
        var oldRequest = request();
        var oldProgress = progress();
        var replacement = requests.claim(oldRequest, oldRequest.availableAt());
        assertThat(replacement.runId()).isNotEqualTo(oldClaim.runId());
        assertThat(requests.progress(oldRequest)).isEmpty();
        assertThat(progress()).isEqualTo(new ReviewProgress("PREPARING", oldRequest.availableAt(), 0, null));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            requests.guard(oldClaim);
            reviews.recordProgressStage(oldClaim.runId(), 10, "FINALIZING", NOW.plusSeconds(3));
        })).isInstanceOf(ReviewRequestRepository.StaleClaimException.class);
        assertThat(db.jdbc.queryForObject("select reviewed_commits from review_run where id = ?", Integer.class, oldClaim.runId())).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select last_saved_at from review_run where id = ?", Timestamp.class, oldClaim.runId()).toInstant())
                .isEqualTo(oldProgress.lastSavedAt());
        assertThat(progress().stage()).isEqualTo("PREPARING");
    }

    @Test void lateGitResultCannotOverwriteReplacementPreparationOrCallAi() {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            var current = request();
            requests.claim(current, current.availableAt());
            return new GitReviewBatch(List.of(FIRST), FIRST.sha());
        });
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SKIPPED);
        assertThat(progress()).isEqualTo(new ReviewProgress("PREPARING", request().lastAttemptAt(), 0, null));
        assertThat(db.jdbc.queryForList("select progress_stage from review_run order by id", String.class))
                .containsExactly("GIT_LOADING", "PREPARING");
        verifyNoInteractions(ai);
    }

    @Test void pauseDuringGitLoadingCancelsWithoutRecordingLaterStage() {
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            db.jdbc.update("update project set status = 'PAUSED' where id = 10");
            return new GitReviewBatch(List.of(FIRST), FIRST.sha());
        });
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.CANCELLED);
        assertThat(request().state()).isEqualTo("CANCELLED");
        assertThat(progress().stage()).isEqualTo("GIT_LOADING");
        assertThat(progress().savedCommits()).isZero();
        verifyNoInteractions(ai);
    }

    @Test void restartedAcknowledgementCanFinishSavedCheckpointWithZeroNewResults() {
        db.jdbc.execute("alter table review_request add constraint reject_progress_completion check (state <> 'SUCCEEDED')");
        assertThatThrownBy(() -> coordinator.processRequest(request())).isInstanceOf(DataAccessException.class);
        var firstRun = request().runId();
        var before = progress();
        assertThat(before.stage()).isEqualTo("FINALIZING");
        assertThat(before.savedCommits()).isEqualTo(1);
        assertThat(before.lastSavedAt()).isNotNull();
        db.jdbc.execute("alter table review_request drop constraint reject_progress_completion");
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(), FIRST.sha()));
        // Advance the fixture's polling eligibility; production waits for available_at.
        db.jdbc.update("update review_request set available_at = ? where project_id = 10", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(progress().savedCommits()).isZero();
        assertThat(progress().lastSavedAt()).isNull();
        assertThat(db.cursor()).isEqualTo(FIRST.sha());
        assertThat(db.jdbc.queryForObject("select last_saved_at from review_run where id = ?", Timestamp.class, firstRun).toInstant())
                .isEqualTo(before.lastSavedAt());
        verify(ai, times(1)).review(FIRST);
    }

    @Test void queuedReplacementAndMismatchedProjectRunNeverExposeOldProgress() {
        assertThat(coordinator.processRequest(request())).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        var old = request();
        requests.enqueueManual(10, "owner", NOW.plusSeconds(100));
        assertThat(requests.progress(old)).isEmpty();
        assertThat(requests.progress(request())).isEmpty();
        claim();
        db.jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) " +
                "values(11,'Other','https://github.com/other/repo','GITHUB','github.com','other/repo',4,'APPROVED')");
        db.jdbc.update("insert into review_run(id,project_id,status,reviewed_commits) values(999,11,'RUNNING',99)");
        db.jdbc.update("update review_request set run_id = 999 where project_id = 10");
        assertThat(requests.progress(request())).isEmpty();
    }

    @Test void legacyUnknownProgressRetainsKnownCountAndStageValidationFailsClosed() {
        var claim = claim();
        db.jdbc.update("update review_run set progress_stage = null, progress_updated_at = null, last_saved_at = null, reviewed_commits = 7");
        assertThat(progress()).isEqualTo(new ReviewProgress(null, null, 7, null));
        assertThat(progress().stageLabel()).isEqualTo("단계 기록 없음");
        assertThatThrownBy(() -> reviews.recordProgressStage(claim.runId(), 10, "REVIEWING", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            requests.guard(claim);
            reviews.recordProgressStage(claim.runId(), 10, "UNKNOWN", NOW);
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.jdbc.update("update review_run set progress_stage = 'UNKNOWN'")).isInstanceOf(DataAccessException.class);
    }

    private ReviewRequestRepository.Request request() { return requests.find(10).orElseThrow(); }
    private ReviewRequestRepository.Claim claim() {
        var current = request();
        return requests.claim(current, current.availableAt());
    }
    private ReviewProgress progress() { return requests.progress(request()).orElseThrow(); }
    private boolean persist(ReviewRequestRepository.Claim claim, GitCommit commit, Instant at) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var project = requests.guard(claim);
            return reviews.persistCommit(claim.runId(), project, project.lastReviewedSha(), commit, result(), at);
        }));
    }
    private static ReviewResult result() {
        return new ReviewResult("Reviewed change", List.of(new ReviewFinding("LOW", "Check change", "A.java", 1, "Description", "Suggestion")));
    }
}
