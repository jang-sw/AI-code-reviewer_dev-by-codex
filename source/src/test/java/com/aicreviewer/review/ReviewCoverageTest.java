package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.IntegrationException;
import com.aicreviewer.git.ManualReviewFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewCoverageTest {
    private ReviewTestDatabase db;
    private ReviewRepository repository;
    private GitRepositoryClient git;
    private AiReviewClient ai;
    private ReviewCoordinator coordinator;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        repository = new ReviewRepository(db.jdbc);
        git = mock(GitRepositoryClient.class);
        ai = mock(AiReviewClient.class);
        when(ai.review(any())).thenReturn(new ReviewResult("AI가 본문을 검토한 결과", List.of()));
        var locks = mock(ProjectReviewLock.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(mock(ProjectReviewLock.Lease.class)));
        coordinator = new ReviewCoordinator(repository, locks, git, ai, db.transactionManager, 100);
    }

    @AfterEach
    void cleanup() { db.close(); }

    @Test
    void verifiedEmptyCommitPersistsExplicitCoverageWithoutCallingAi() {
        GitCommit commit = commit("EMPTY", "Git 파일 변경 없음", "");
        batch(commit);

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        verifyNoInteractions(ai);
        assertThat(db.cursor()).isEqualTo(commit.sha());
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.jdbc.queryForObject("select summary from reviewed_commit", String.class)).contains("AI 본문 검토 없음");
        assertThat(repository.reviewedCommits(10).getFirst()).containsEntry("coverage_type", "EMPTY")
                .containsEntry("coverage_details", "Git 파일 변경 없음");
    }

    @Test
    void metadataOnlyPersistsEveryManualPathInsteadOfAnUnassignedNotice() {
        String details = "경로: old.sh → <script>new.sh</script>\n실행권한: 100644 → 100755";
        GitCommit commit = commit("METADATA_ONLY", details, "diff --git a/old.sh b/new.sh\nold mode 100644\nnew mode 100755\n");
        batch(commit);
        metadataProof(commit, List.of(new ManualReviewFile("old.sh", "b".repeat(40), null, "100644", null, "METADATA_CHANGE"),
                new ManualReviewFile("new.sh", null, "b".repeat(40), null, "100755", "METADATA_CHANGE")));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        verifyNoInteractions(ai);
        assertThat(db.count("review_issue")).isEqualTo(2);
        assertThat(db.count("manual_review_file")).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("select summary from reviewed_commit", String.class))
                .contains("AI 본문 검토 없음", "수동 확인 이슈", "2개");
        assertThat(repository.reviewedCommits(10).getFirst()).containsEntry("coverage_type", "MANUAL_ONLY").containsEntry("coverage_details", details);
        assertThat(db.jdbc.queryForList("select reason_code from manual_review_file", String.class)).containsOnly("METADATA_CHANGE");
        assertThat(db.cursor()).isEqualTo(commit.sha());
    }

    @Test
    void sameBlobSymlinkConversionExplicitlyRequiresManualFileTypeReview() {
        String details = "이전 경로: config, 모드: 100644 → 새 경로: config, 모드: 120000 (동일 blob; 본문 변경 없음)";
        GitCommit commit = commit("METADATA_ONLY", details, "diff --git a/config b/config\nold mode 100644\nnew mode 120000\n");
        batch(commit);
        metadataProof(commit, List.of(new ManualReviewFile("config", "b".repeat(40), "b".repeat(40), "100644", "120000", "METADATA_CHANGE")));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        verifyNoInteractions(ai);
        assertThat(db.count("review_issue")).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("select summary from reviewed_commit", String.class))
                .contains("AI 본문 검토 없음", "수동 확인 이슈");
        assertThat(repository.reviewedCommits(10).getFirst()).containsEntry("coverage_type", "MANUAL_ONLY")
                .containsEntry("coverage_details", details);
        assertThat(db.cursor()).isEqualTo(commit.sha());
    }

    @Test
    void fullCommitWithMetadataStillCallsAiAndKeepsCoverageDetails() {
        GitCommit commit = commit("FULL", "rename a.sh → b.sh", "diff --git a/A.java b/A.java\n@@ -1 +1 @@\n-old();\n+new();\n");
        batch(commit);

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        verify(ai).review(commit);
        assertThat(db.jdbc.queryForObject("select summary from reviewed_commit", String.class)).isEqualTo("AI가 본문을 검토한 결과");
        assertThat(repository.reviewedCommits(10).getFirst()).containsEntry("coverage_type", "FULL").containsEntry("coverage_details", "rename a.sh → b.sh");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "PARTIAL", "SKIPPED", "full" })
    void unrecognizedCoverageCannotSilentlySkipAi(String coverage) {
        batch(commit(coverage, "Details", "diff"));

        assertFailedWithoutPersistence();
    }

    @Test
    void nullOrOversizedCoverageDetailsFailBeforeAnyCommitIsPersisted() {
        batch(commit("FULL", null, "diff"));
        assertFailedWithoutPersistence();
        batch(commit("METADATA_ONLY", "x".repeat(16001), "metadata headers"));
        assertFailedWithoutPersistence();
    }

    @Test
    void metadataOnlyRequiresAConcreteDescriptionOfItsScope() {
        batch(commit("METADATA_ONLY", " ", "metadata headers"));

        assertFailedWithoutPersistence();
    }

    @Test
    void emptyLabelCannotHideANonemptyDiff() {
        batch(commit("EMPTY", "Claimed empty", "diff --git a/A.java b/A.java\n+changed();"));

        assertFailedWithoutPersistence();
    }

    @ParameterizedTest
    @ValueSource(strings = { "@@ -1 +1 @@", "+changed();", "-removed();" })
    void metadataLabelCannotHideHunksOrTextPatchBody(String patchLine) {
        batch(commit("METADATA_ONLY", "Claimed metadata", "diff --git a/A.java b/A.java\n" + patchLine));

        assertFailedWithoutPersistence();
    }

    @Test
    void fileHeadersAreAllowedForVerifiedMetadataOnlyCoverage() {
        GitCommit commit = commit("METADATA_ONLY", "rename a.sh → b.sh", "diff --git a/a.sh b/b.sh\n--- a/a.sh\n+++ b/b.sh\nrename from a.sh\nrename to b.sh\n");
        batch(commit);
        metadataProof(commit, List.of(new ManualReviewFile("a.sh", "b".repeat(40), null, "100644", null, "METADATA_CHANGE"),
                new ManualReviewFile("b.sh", null, "b".repeat(40), null, "100644", "METADATA_CHANGE")));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        verifyNoInteractions(ai);
    }

    @Test
    void maximumCoverageDetailsLengthIsAcceptedWithoutTruncation() {
        String details = "x".repeat(16000);
        GitCommit commit = commit("METADATA_ONLY", details, "metadata headers");
        batch(commit);
        metadataProof(commit, List.of(new ManualReviewFile("a.sh", "b".repeat(40), "b".repeat(40), "100644", "100755", "METADATA_CHANGE")));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        assertThat(db.jdbc.queryForObject("select coverage_details from reviewed_commit", String.class)).isEqualTo(details);
        verifyNoInteractions(ai);
    }

    @Test
    void failedAiForFullCoverageIsNotDowngradedToMetadataSuccess() {
        GitCommit commit = commit("FULL", "Contains a renamed file too", "diff");
        batch(commit);
        when(ai.review(commit)).thenThrow(new IntegrationException("Commit diff unavailable"));

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.FAILED);

        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.cursor()).isNull();
        verify(ai).review(commit);
    }

    @Test
    void retryOfStoredMetadataDoesNotCreateDuplicateCommitOrCallAi() {
        GitCommit old = commit("METADATA_ONLY", "old mode 100644 → new mode 100755", "metadata headers");
        db.jdbc.update("insert into reviewed_commit(project_id,commit_sha,summary,coverage_type,coverage_details) values(10,?,'Existing metadata notice','METADATA_ONLY',?)", old.sha(), old.coverageDetails());
        batch(old);

        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);

        assertThat(db.count("reviewed_commit")).isEqualTo(1);
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.count("manual_review_file")).isZero();
        assertThat(db.jdbc.queryForObject("select coverage_type from reviewed_commit", String.class)).isEqualTo("METADATA_ONLY");
        assertThat(db.jdbc.queryForObject("select summary from reviewed_commit", String.class)).isEqualTo("Existing metadata notice");
        assertThat(db.jdbc.queryForList("select reviewed_commits from review_run order by id", Integer.class)).containsExactly(0, 0);
        verify(git, never()).manualMetadataFallback(any(), any());
        verifyNoInteractions(ai);
    }

    private void metadataProof(GitCommit original, List<ManualReviewFile> files) {
        when(git.manualMetadataFallback(any(), eq(original))).thenReturn(new GitCommit(original.sha(), original.authorLogin(), original.authorEmail(),
                original.message(), "", "MANUAL_ONLY", original.coverageDetails(), files));
    }

    private GitCommit commit(String coverage, String details, String diff) {
        return new GitCommit("a".repeat(40), "author-git", null, "Commit", diff, coverage, details);
    }

    private void batch(GitCommit commit) { when(git.batch(any(), any(), any(), anySet(), anyInt())).thenReturn(new GitReviewBatch(List.of(commit), commit.sha())); }

    private void assertFailedWithoutPersistence() {
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.FAILED);
        assertThat(db.count("reviewed_commit")).isZero();
        assertThat(db.count("review_issue")).isZero();
        assertThat(db.cursor()).isNull();
        verifyNoInteractions(ai);
    }
}
