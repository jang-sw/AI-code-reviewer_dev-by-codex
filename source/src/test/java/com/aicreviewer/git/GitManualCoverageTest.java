package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Complete tree evidence is required even when an early diff is explicitly unavailable. */
class GitManualCoverageTest {
    private static final String ROOT = "a".repeat(40), HEAD = "b".repeat(40), OLD = "c".repeat(40), NEW = "d".repeat(40);
    private static final String BEFORE_TREE = "e".repeat(40), AFTER_TREE = "f".repeat(40);
    private static final String PATCH = "@@ -1 +1 @@\n-old\n+new";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    private String provider;
    private Map<String, Object> details, beforeTree, afterTree;
    private List<Map<String, Object>> files, before, after;

    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }
    private HttpFixture.Reply ok(Object body) { return new HttpFixture.Reply(200, json.writeValueAsString(body)); }
    private String patchName() { return provider.equals("github") ? "patch" : "diff"; }
    private RepositoryUrl repository() {
        return RepositoryUrl.parse(provider.equals("github") ? "https://github.com/team/repo" : server.url() + "/team/repo", Set.of("github.com", "127.0.0.1"));
    }
    private GitRepositoryClient client(int maxDiff, int timeout) {
        return new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "", 2, 20, maxDiff, 1048576, timeout);
    }
    private GitCommit review() { return client(65536, 30).batch(repository(), null, ROOT, Set.of(), 1).commits().getFirst(); }
    private Map<String, Object> metadata(String sha, String... parents) {
        return provider.equals("github") ? Map.of("sha", sha, "parents", Stream.of(parents).map(value -> Map.of("sha", value)).toList(),
                "commit", Map.of("message", "Change", "author", Map.of("email", "dev@example.invalid")), "author", Map.of("login", "alice"))
                : Map.of("id", sha, "parent_ids", List.of(parents), "message", "Change", "author_email", "dev@example.invalid");
    }
    private Map<String, Object> entry(String path, String sha, String mode) {
        return Map.of("path", path, provider.equals("github") ? "sha" : "id", sha, "mode", mode,
                "type", mode.equals("160000") ? "commit" : "blob");
    }
    private Map<String, Object> file(String path, String patch, int adds, int deletes) {
        Map<String, Object> result = new HashMap<>();
        if (provider.equals("github")) result.putAll(Map.of("filename", path, "sha", NEW, "status", "modified", "additions", adds, "deletions", deletes));
        else result.putAll(Map.of("old_path", path, "new_path", path, "new_file", false, "deleted_file", false, "renamed_file", false,
                "a_mode", "100644", "b_mode", "100644", "collapsed", false, "too_large", false));
        if (patch != null) result.put(patchName(), patch);
        return result;
    }
    private void configure(String provider, String unavailablePatch) {
        this.provider = provider;
        before = new ArrayList<>(List.of(entry("image.bin", OLD, "100644"), entry("source.txt", OLD, "100644")));
        after = new ArrayList<>(List.of(entry("image.bin", NEW, "100644"), entry("source.txt", NEW, "100644")));
        files = new ArrayList<>(List.of(file("image.bin", unavailablePatch, 0, 0), file("source.txt", PATCH, 1, 1)));
        details = new HashMap<>(metadata(HEAD, ROOT));
        details.put("stats", Map.of("additions", 1, "deletions", 1));
        if (provider.equals("github")) {
            details.put("commit", Map.of("message", "Change", "tree", Map.of("sha", AFTER_TREE)));
            details.put("files", files);
        }
        beforeTree = new HashMap<>(Map.of("sha", BEFORE_TREE, "truncated", false, "tree", before));
        afterTree = new HashMap<>(Map.of("sha", AFTER_TREE, "truncated", false, "tree", after));
        server.handler = request -> {
            if (request.path().endsWith("/commits")) return ok(List.of(metadata(HEAD, ROOT), metadata(ROOT)));
            if (request.path().endsWith("/git/commits/" + ROOT)) return ok(Map.of("sha", ROOT, "tree", Map.of("sha", BEFORE_TREE)));
            if (request.path().endsWith("/commits/" + HEAD)) return ok(details);
            if (request.path().endsWith("/git/trees/" + BEFORE_TREE)) return ok(beforeTree);
            if (request.path().endsWith("/git/trees/" + AFTER_TREE)) return ok(afterTree);
            if (request.path().endsWith("/tree")) return ok(request.query().contains("ref=" + ROOT) ? before : after);
            if (request.path().endsWith("/diff")) return ok(files);
            return new HttpFixture.Reply(404, "PRIVATE unexpected route");
        };
    }

    static Stream<Arguments> unavailableDiffs() {
        return Stream.of("github", "gitlab").flatMap(provider -> Stream.of(null, "", "Binary files a/image.bin and b/image.bin differ", "GIT binary patch")
                .map(patch -> Arguments.of(provider, patch)));
    }

    @ParameterizedTest @MethodSource("unavailableDiffs")
    void completeTreeProofMakesTheEntireCommitManualIncludingSupportedFiles(String provider, String patch) {
        configure(provider, patch);
        GitReviewBatch batch = client(65536, 30).batch(repository(), null, ROOT, Set.of(), 1);
        GitCommit result = batch.commits().getFirst();
        assertThat(result.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(result.diff()).isEmpty();
        assertThat(result.manualFiles()).containsExactly(new ManualReviewFile("image.bin", OLD, NEW, "100644", "100644", "SOURCE_DIFF_UNAVAILABLE"),
                new ManualReviewFile("source.txt", OLD, NEW, "100644", "100644", "SOURCE_DIFF_UNAVAILABLE"));
        assertThat(result.coverageDetails()).contains("AI 본문 검토 없음", "커밋 전체", "지원 가능한 파일도 포함", "첫 부모");
        if (provider.equals("gitlab")) assertThat(result.coverageDetails()).contains("행수는 검증할 수 없습니다");
        assertThat(batch.checkpointSha()).isEqualTo(HEAD);
        assertThat(server.requests).allSatisfy(request -> {
            assertThat(request.header("Authorization")).isNull();
            assertThat(request.header("PRIVATE-TOKEN")).isNull();
            assertThat(request.path()).doesNotContain("/blobs/", "/raw");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void largeButCompleteTextBecomesManualOnlyAfterStatisticsAndTreesAreVerified(String provider) {
        configure(provider, "@@ -1 +1 @@\n-old\n+" + "x".repeat(1500));
        if (provider.equals("github")) { files.getFirst().put("additions", 1); files.getFirst().put("deletions", 1); }
        details.put("stats", Map.of("additions", 2, "deletions", 2));
        GitCommit result = client(1024, 30).batch(repository(), null, ROOT, Set.of(), 1).commits().getFirst();
        assertThat(result.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(result.manualFiles()).hasSize(2).allSatisfy(file -> assertThat(file.reasonCode()).isEqualTo("GIT_DIFF_BUDGET"));
        assertThat(result.coverageDetails()).doesNotContain("행수는 검증할 수 없습니다");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void aiInputFallbackReProvesPinnedMetadataAndEveryFileWithoutFetchingHistory(String provider) {
        configure(provider, PATCH);
        if (provider.equals("github")) { files.getFirst().put("additions", 1); files.getFirst().put("deletions", 1); }
        details.put("stats", Map.of("additions", 2, "deletions", 2));
        GitCommit original = new GitCommit(HEAD, "alice", "dev@example.invalid", "Change", "complete original diff");
        GitCommit result = client(65536, 30).manualFallback(repository(), original);
        assertThat(result.manualFiles()).hasSize(2).allSatisfy(file -> assertThat(file.reasonCode()).isEqualTo("AI_INPUT_LIMIT"));
        assertThat(result.authorLogin()).isEqualTo(original.authorLogin());
        assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).endsWith("/commits"));
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void rootCreationAndDeletionHaveExactOneSidedObjectAndModeEvidence(String provider) {
        for (boolean created : List.of(true, false)) {
            configure(provider, null);
            before.clear();
            after.clear();
            (created ? after : before).add(entry("image.bin", created ? NEW : OLD, "100644"));
            files.removeLast();
            details.put("stats", Map.of("additions", 0, "deletions", 0));
            if (provider.equals("github")) {
                files.getFirst().put("status", created ? "added" : "removed");
                files.getFirst().put("sha", created ? NEW : OLD);
                if (created) details.put("parents", List.of());
            } else {
                files.getFirst().put("new_file", created);
                files.getFirst().put("deleted_file", !created);
                files.getFirst().put(created ? "a_mode" : "b_mode", "0");
                if (created) details.put("parent_ids", List.of());
            }
            var ordinary = server.handler;
            if (created) server.handler = request -> request.path().endsWith("/commits") ? ok(List.of(metadata(HEAD))) : ordinary.apply(request);
            GitCommit result = client(65536, 30).batch(repository(), null, created ? null : ROOT, Set.of(), 1).commits().getFirst();
            assertThat(result.manualFiles()).containsExactly(new ManualReviewFile("image.bin", created ? null : OLD,
                    created ? NEW : null, created ? null : "100644", created ? "100644" : null, "SOURCE_DIFF_UNAVAILABLE"));
            if (created) assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).endsWith("/git/commits/" + ROOT));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void changedBlobRenameKeepsBothDeletedAndCreatedPathsInManualEvidence(String provider) {
        configure(provider, null);
        before.removeLast();
        after.clear();
        after.add(entry("renamed.bin", NEW, "100644"));
        files.removeLast();
        details.put("stats", Map.of("additions", 0, "deletions", 0));
        if (provider.equals("github")) {
            files.getFirst().put("filename", "renamed.bin");
            files.getFirst().put("previous_filename", "image.bin");
            files.getFirst().put("status", "renamed");
        } else {
            files.getFirst().put("new_path", "renamed.bin");
            files.getFirst().put("renamed_file", true);
        }
        assertThat(review().manualFiles()).containsExactly(new ManualReviewFile("image.bin", OLD, null, "100644", null, "SOURCE_DIFF_UNAVAILABLE"),
                new ManualReviewFile("renamed.bin", null, NEW, null, "100644", "SOURCE_DIFF_UNAVAILABLE"));
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void aiFallbackRejectsRewrittenPinnedMetadataAndUnsupportedOriginalCoverage(String provider) {
        configure(provider, null);
        GitRepositoryClient client = client(65536, 30);
        assertThatThrownBy(() -> client.manualFallback(repository(), new GitCommit(HEAD, null, "Other message", "full diff")))
                .hasMessageContaining("inconsistent pinned commit metadata");
        assertThatThrownBy(() -> client.manualFallback(repository(), new GitCommit(HEAD, null, null, "Change", "", "EMPTY", "No changes")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("complete pinned commit");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void earlyUnavailablePatchDoesNotHideDuplicateFilesLaterInTheListing(String provider) {
        configure(provider, null);
        files.add(new HashMap<>(files.getLast()));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("duplicate diff files");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void earlyUnavailablePatchDoesNotHideInvalidOrIncompleteStatistics(String provider) {
        configure(provider, null);
        details.put("stats", Map.of("additions", 0, "deletions", 0));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("statistics");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void treesMustProveEveryChangedPathNotJustTheUnavailableFile(String provider) {
        configure(provider, null);
        after.add(entry("hidden.txt", NEW, "100644"));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("incomplete");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void completeTreeAndFileChangeFlagsMustAgree(String provider) {
        configure(provider, null);
        if (provider.equals("github")) files.getLast().put("status", "added");
        else files.getLast().put("new_file", true);
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("immutable trees");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void headerOnlyPatchAfterUnavailableFileStillFails(String provider) {
        configure(provider, null);
        files.getLast().put(patchName(), "diff --git a/source.txt b/source.txt\nindex aaa..bbb\n");
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("truncated text patch");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void matchingReportedStatisticsCannotHideAnIncompleteProvidedHunk(String provider) {
        for (String patch : List.of("@@ -1,2 +1,2 @@\n-old\n+new", "@@ -1 +1 @@\n-old\n+new\n+extra",
                "@@ -1 +999999999999999999999 @@\n-old\n+new", "@@ -1 +1 @@\n-old\n+new\n+++ extra body")) {
            configure(provider, null);
            files.getLast().put(patchName(), patch);
            int actualAdds = (int) patch.lines().filter(line -> line.startsWith("+")).count();
            details.put("stats", Map.of("additions", actualAdds, "deletions", 1));
            if (provider.equals("github")) files.getLast().put("additions", actualAdds);
            assertThatThrownBy(this::review).hasMessageContaining("incomplete hunks");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void completeMultipleHunksWithContextAndNoNewlineMarkersRetainManualCoverage(String provider) {
        configure(provider, null);
        files.getLast().put(patchName(), "--- a/source.txt\n+++ b/source.txt\n@@ -1,2 +1,2 @@\n context\n-old\n+new\n\\ No newline at end of file\n\n@@ -9,0 +10,1 @@\n+added\n\n");
        details.put("stats", Map.of("additions", 2, "deletions", 1));
        if (provider.equals("github")) files.getLast().put("additions", 2);
        assertThat(review().manualFiles()).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void malformedPatchFieldAfterUnavailableFileStillFails(String provider) {
        configure(provider, null);
        files.getLast().put(patchName(), 123);
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class).hasMessageContaining("invalid patch field");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void binaryMarkersCannotHideContradictoryTextOrMalformedHunks(String provider) {
        for (String suffix : List.of("\n@@ broken\n+data", "\n@@ -1 +1 @@\n-old\n+new", "\n+unexpected", "\n-unexpected")) {
            configure(provider, "Binary files a/image.bin and b/image.bin differ" + suffix);
            assertThatThrownBy(this::review).hasMessageContaining("contradictory binary and text patch content");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void earlyUnsupportedFileStillRequiresAllPagesBeforeCheckpointing(String provider) {
        configure(provider, null);
        var ordinary = server.handler;
        server.handler = request -> {
            if (request.path().endsWith(provider.equals("github") ? "/commits/" + HEAD : "/diff")) {
                boolean firstPage = request.query().matches("(?:.*&)?page=1(?:&.*)?");
                Object body;
                if (provider.equals("github")) {
                    Map<String, Object> paged = new HashMap<>(details);
                    paged.put("files", List.of(files.get(firstPage ? 0 : 1)));
                    body = paged;
                } else body = List.of(files.get(firstPage ? 0 : 1));
                return new HttpFixture.Reply(200, json.writeValueAsString(body), firstPage ? Map.of("Link", "<ignored>; rel=next") : Map.of());
            }
            return ordinary.apply(request);
        };
        GitCommit result = review();
        assertThat(result.manualFiles()).extracting(ManualReviewFile::filePath).containsExactly("image.bin", "source.txt");
        assertThat(server.requests).anySatisfy(request -> assertThat(request.query()).endsWith("page=2"));
    }

    @Test void truncatedGithubTreesAndWrongObjectIdsNeverBecomeManualSuccess() {
        configure("github", null);
        afterTree.put("truncated", true);
        assertThatThrownBy(this::review).hasMessageContaining("truncated");
        afterTree.put("truncated", false);
        files.getFirst().put("sha", OLD);
        assertThatThrownBy(this::review).hasMessageContaining("blob disagrees");
    }

    @Test void bothGitlabUnavailableFlagsAreStrictlyValidatedAndMissingBodyCountsAreDisclosed() {
        configure("gitlab", null);
        files.getFirst().put("collapsed", true);
        files.getFirst().put("too_large", "invalid");
        assertThatThrownBy(this::review).hasMessageContaining("file change flag");
        files.getFirst().put("too_large", true);
        assertThat(review().manualFiles()).allSatisfy(file -> assertThat(file.reasonCode()).isEqualTo("GIT_DIFF_BUDGET"));
    }

    @Test void manualFileCountLimitDoesNotSilentlyDropEvidence() {
        configure("github", null);
        for (int index = 0; index < ManualReviewFile.MAX_FILES; index++) after.add(entry("extra-" + index, NEW, "100644"));
        assertThatThrownBy(this::review).hasMessageContaining("safety limit");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void networkOrInvalidJsonFailuresRemainFailuresWithoutEchoingRemoteBodies(String provider) {
        configure(provider, null);
        var ordinary = server.handler;
        server.handler = request -> request.path().endsWith(provider.equals("github") ? "/git/trees/" + AFTER_TREE : "/tree")
                ? new HttpFixture.Reply(503, "PRIVATE RESPONSE") : ordinary.apply(request);
        assertThatThrownBy(this::review).hasMessage("Git returned HTTP 503").hasCause(null);
    }
}
