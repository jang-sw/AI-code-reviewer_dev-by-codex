package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitCoverageTest {
    private static final String A = "a".repeat(40), B = "b".repeat(40), OLD = "c".repeat(40), NEW = "d".repeat(40);
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    @Test void githubEmptyCommitRequiresAnEmptyFileListAndZeroStatistics() {
        github(List.of(), 0);
        GitCommit commit = githubReview();
        assertThat(commit.coverageType()).isEqualTo("EMPTY");
        assertThat(commit.diff()).isEmpty();
        assertThat(commit.coverageDetails()).contains("변경 파일 0개", "AI 본문 검토 없음");
        github(List.of(), 1);
        assertThatThrownBy(this::githubReview).hasMessageContaining("incomplete");
        github(List.of(Map.of("filename", "image.png", "additions", 0, "deletions", 0)), 0);
        assertThatThrownBy(this::githubReview).hasMessageContaining("patch");
    }

    @Test void gitlabEmptyCommitRequiresIdenticalTreesNoFilesAndZeroStatistics() {
        List<Map<String, String>> tree = List.of(blob("same.java", OLD, "100644"));
        gitlab(tree, tree, List.of(), 0);
        GitCommit commit = gitlabReview();
        assertThat(commit.coverageType()).isEqualTo("EMPTY");
        assertThat(commit.diff()).isEmpty();
        assertThat(commit.coverageDetails()).contains("동일한 커밋 트리", "AI 본문 검토 없음");
        gitlab(tree, tree, List.of(), 1);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("incomplete");
        gitlab(tree, List.of(blob("same.java", NEW, "100644")), List.of(), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("incomplete");
    }

    @Test void emptyRootWithAnEmptyTreeIsRecordedExplicitly() {
        gitlab(List.of(), List.of(), List.of(), 0);
        var usual = server.handler;
        server.handler = request -> request.path().endsWith("/repository/commits")
                ? ok(List.of(Map.of("id", B, "parent_ids", List.of(), "message", "Empty root"))) : usual.apply(request);
        assertThat(client().commits(repository(), null, null, 5).getFirst().coverageType()).isEqualTo("EMPTY");
    }

    @Test void unchangedBlobModeChangeHasExplicitManualCoverageAndSyntheticDiff() {
        gitlab(List.of(blob("script.sh", OLD, "100644")), List.of(blob("script.sh", OLD, "100755")),
                List.of(file("script.sh", "script.sh", "")), 0);
        GitCommit commit = gitlabReview();
        assertThat(commit.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(commit.diff()).contains("diff --git a/script.sh b/script.sh", "old mode 100644", "new mode 100755");
        assertThat(commit.coverageDetails()).contains("AI 본문 검토 없음", "수동 확인", "script.sh", "100644", "100755", "동일 blob");
    }

    @Test void unchangedBlobRenameAllowsAbsentPatchButDisclosesBothPathsAndModes() {
        gitlab(List.of(blob("before.java", OLD, "100644")), List.of(blob("after.java", OLD, "100644")),
                List.of(Map.of("old_path", "before.java", "new_path", "after.java")), 0);
        GitCommit commit = gitlabReview();
        assertThat(commit.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(commit.diff()).contains("rename from before.java", "rename to after.java", "old mode 100644", "new mode 100644");
        assertThat(commit.coverageDetails()).contains("이전 경로: before.java", "새 경로: after.java");
    }

    @Test void unchangedBlobRegularAndSymlinkTransitionsRequireExplicitFileTypeManualReview() {
        for (List<String> modes : List.of(List.of("100644", "120000"), List.of("120000", "100644"))) {
            gitlab(List.of(blob("config", OLD, modes.getFirst())), List.of(blob("config", OLD, modes.getLast())),
                    List.of(file("config", "config", "")), 0);
            GitCommit commit = gitlabReview();
            assertThat(commit.coverageType()).isEqualTo("METADATA_ONLY");
            assertThat(commit.coverageDetails()).contains("파일 유형", "수동 확인", "100644", "120000");
            assertThat(commit.diff()).contains("old mode " + modes.getFirst(), "new mode " + modes.getLast());
        }
    }

    @Test void mixedBodyAndMetadataChangesRetainFullCoverageWithExplicitDisclosure() {
        gitlab(List.of(blob("app.java", OLD, "100644"), blob("script.sh", OLD, "100644")),
                List.of(blob("app.java", NEW, "100644"), blob("script.sh", OLD, "100755")),
                List.of(file("app.java", "app.java", "@@ -0,0 +1,1 @@\n+one"), file("script.sh", "script.sh", "")), 1);
        GitCommit commit = gitlabReview();
        assertThat(commit.coverageType()).isEqualTo("FULL");
        assertThat(commit.diff()).contains("+one", "diff --git a/script.sh b/script.sh", "old mode 100644", "new mode 100755");
        assertThat(commit.coverageDetails()).contains("본문 diff와 함께", "script.sh", "100644", "100755");
    }

    @Test void bodyChangeAndRenameInOneFileAlsoDiscloseModesAndPaths() {
        gitlab(List.of(blob("before.java", OLD, "100644")), List.of(blob("after.java", NEW, "100755")),
                List.of(file("before.java", "after.java", "@@ -0,0 +1,1 @@\n+one")), 1);
        GitCommit commit = gitlabReview();
        assertThat(commit.coverageType()).isEqualTo("FULL");
        assertThat(commit.diff()).contains("+one", "rename from before.java", "rename to after.java", "old mode 100644", "new mode 100755");
        assertThat(commit.coverageDetails()).contains("before.java", "after.java", "본문 변경 포함");
    }

    @Test void blankPatchWithChangedBlobOrNewEmptyFileCannotClaimMetadataCoverage() {
        gitlab(List.of(blob("script.sh", OLD, "100644")), List.of(blob("script.sh", NEW, "100755")),
                List.of(file("script.sh", "script.sh", "")), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("unavailable");
        gitlab(List.of(), List.of(blob("empty.java", NEW, "100644")), List.of(file("empty.java", "empty.java", "")), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("unavailable");
    }

    @Test void nonblankHeadersWithoutChangedBodyCannotClaimFullCoverage() {
        String headers = "diff --git a/app.txt b/app.txt\nindex 1234..5678\n--- a/app.txt\n+++ b/app.txt\n";
        gitlab(List.of(blob("app.txt", OLD, "100644")), List.of(blob("app.txt", NEW, "100644")),
                List.of(file("app.txt", "app.txt", headers)), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("unavailable");
        github(List.of(Map.of("filename", "app.txt", "additions", 0, "deletions", 0, "patch", headers)), 0);
        assertThatThrownBy(this::githubReview).hasMessageContaining("missing or truncated");
    }

    @Test void malformedHunkCannotSupplyAChangedBodyEvenWhenStatisticsMatch() {
        String patch = "@@ missing ranges @@\n+one";
        gitlab(List.of(blob("app.txt", OLD, "100644")), List.of(blob("app.txt", NEW, "100644")),
                List.of(file("app.txt", "app.txt", patch)), 1);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("invalid patch hunk");
        github(List.of(Map.of("filename", "app.txt", "additions", 1, "deletions", 0, "patch", patch)), 1);
        assertThatThrownBy(this::githubReview).hasMessageContaining("invalid patch hunk");
    }

    @Test void aCopyThatRetainsItsOriginalPathCannotClaimAnUnchangedBlobRename() {
        gitlab(List.of(blob("original.java", OLD, "100644")),
                List.of(blob("original.java", OLD, "100644"), blob("copy.java", OLD, "100644")),
                List.of(file("original.java", "copy.java", "")), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("unavailable");
    }

    @Test void unchangedBlobDoesNotSuppressCollapsedBinaryOrContradictoryPatchFailures() {
        for (Map<String, Object> patch : List.of(
                Map.<String, Object>of("old_path", "script.sh", "new_path", "script.sh", "collapsed", true, "diff", ""),
                Map.<String, Object>of("old_path", "script.sh", "new_path", "script.sh", "too_large", true, "diff", ""),
                file("script.sh", "script.sh", "GIT binary patch"),
                file("script.sh", "script.sh", "@@ -0,0 +1,1 @@\n+unexpected"))) {
            gitlab(List.of(blob("script.sh", OLD, "100644")), List.of(blob("script.sh", OLD, "100755")), List.of(patch), 0);
            assertThatThrownBy(this::gitlabReview).isInstanceOf(IntegrationException.class);
        }
    }

    @Test void metadataCoverageStillRequiresEveryTreeChangeToHaveADiffEntry() {
        gitlab(List.of(blob("script.sh", OLD, "100644"), blob("hidden.sh", OLD, "100644")),
                List.of(blob("script.sh", OLD, "100755"), blob("hidden.sh", OLD, "100755")),
                List.of(file("script.sh", "script.sh", "")), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("incomplete");
    }

    @Test void metadataDetailsAreBoundedWithoutTruncatingChangedPaths() {
        List<Map<String, String>> before = new ArrayList<>(), after = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String path = i + "x".repeat(950) + ".sh";
            before.add(blob(path, OLD, "100644"));
            after.add(blob(path, OLD, "100755"));
            files.add(file(path, path, ""));
        }
        gitlab(before, after, files, 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("coverage details");
    }

    @Test void modeHeadersCannotBeSpoofedThroughMalformedTreeMetadata() {
        gitlab(List.of(blob("script.sh", OLD, "100644\n+injected")), List.of(blob("script.sh", OLD, "100755")),
                List.of(file("script.sh", "script.sh", "")), 0);
        assertThatThrownBy(this::gitlabReview).hasMessageContaining("mode");
    }

    @Test void compatibilityConstructorsKeepFullCoverageWithoutInventedExclusions() {
        for (GitCommit commit : List.of(new GitCommit(A, "dev", "message", "diff"),
                new GitCommit(A, "dev", "dev@example.com", "message", "diff"))) {
            assertThat(commit.coverageType()).isEqualTo("FULL");
            assertThat(commit.coverageDetails()).isEmpty();
        }
    }

    private GitRepositoryClient client() {
        return new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "",
                2, 10, 65536, 262144, 300);
    }
    private RepositoryUrl repository() { return RepositoryUrl.parse(server.url() + "/group/repo", Set.of("127.0.0.1")); }
    private GitCommit gitlabReview() { return client().commits(repository(), null, A, 5).getFirst(); }
    private GitCommit githubReview() {
        return client().commits(RepositoryUrl.parse("https://github.com/group/repo", Set.of("github.com")), null, null, 5).getFirst();
    }
    private void github(List<Map<String, Object>> files, int additions) {
        server.handler = request -> request.path().endsWith("/commits")
                ? ok(List.of(Map.of("sha", B, "parents", List.of(), "commit", Map.of("message", "Empty"))))
                : ok(Map.of("sha", B, "stats", Map.of("additions", additions, "deletions", 0), "files", files));
    }
    private void gitlab(List<Map<String, String>> before, List<Map<String, String>> after,
            List<Map<String, Object>> files, int additions) {
        server.handler = request -> {
            if (request.path().endsWith("/repository/commits")) return ok(List.of(
                    Map.of("id", B, "parent_ids", List.of(A), "message", "Change"),
                    Map.of("id", A, "parent_ids", List.of(), "message", "Root")));
            if (request.path().endsWith("/repository/commits/" + B)) return ok(Map.of("id", B, "stats", Map.of("additions", additions, "deletions", 0)));
            if (request.path().endsWith("/tree")) return ok(request.query().contains("ref=" + A) ? before : after);
            if (request.path().endsWith("/diff")) return ok(files);
            return new HttpFixture.Reply(404, "{}");
        };
    }
    private Map<String, String> blob(String path, String id, String mode) { return Map.of("path", path, "id", id, "mode", mode, "type", "blob"); }
    private Map<String, Object> file(String before, String after, String patch) { return Map.of("old_path", before, "new_path", after, "diff", patch); }
    private HttpFixture.Reply ok(Object value) { return new HttpFixture.Reply(200, json.writeValueAsString(value)); }
}
