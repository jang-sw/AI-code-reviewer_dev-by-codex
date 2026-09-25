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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GitHubMetadataCoverageTest {
    private static final String ROOT = "a".repeat(40), HEAD = "b".repeat(40), SIDE = "c".repeat(40);
    private static final String OLD = "d".repeat(40), NEW = "e".repeat(40);
    private static final String BEFORE_TREE = "f".repeat(40), AFTER_TREE = "1".repeat(40);
    private static final RepositoryUrl REPOSITORY = RepositoryUrl.parse("https://github.com/team/repo", Set.of("github.com"));
    private static final String PATCH = "@@ -1 +1 @@\n-old\n+new";
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    private List<Map<String, Object>> history;
    private Map<String, Object> details, parentDetails, beforeTree, afterTree;

    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private GitRepositoryClient client() { return client(65536, 262144, 300); }
    private GitRepositoryClient client(int maxDiff, int maxResponse, int operationTimeout) {
        return new GitRepositoryClient(Set.of("github.com"), server.url(), "fixture-token", "github.com", "",
                2, 10, maxDiff, maxResponse, operationTimeout);
    }
    private GitCommit review() { return client().batch(REPOSITORY, null, ROOT, Set.of(), 1).commits().getFirst(); }
    private HttpFixture.Reply ok(Object body) { return new HttpFixture.Reply(200, json.writeValueAsString(body)); }
    private static Map<String, Object> commit(String sha, String... parents) {
        return Map.of("sha", sha, "author", Map.of("login", "developer"),
                "commit", Map.of("message", "Change", "author", Map.of("email", "dev@example.invalid")),
                "parents", Stream.of(parents).map(parent -> Map.of("sha", parent)).toList());
    }
    private static Map<String, Object> entry(String path, String sha, String mode) {
        return Map.of("path", path, "sha", sha, "mode", mode, "type", mode.equals("160000") ? "commit" : "blob",
                "url", "https://attacker.invalid/ignored-blob-url");
    }
    private static Map<String, Object> file(String path, String previous, String status, String blob, String patch, int additions, int deletions) {
        Map<String, Object> file = new HashMap<>(Map.of("filename", path, "status", status, "sha", blob,
                "additions", additions, "deletions", deletions));
        if (previous != null) file.put("previous_filename", previous);
        if (patch != null) file.put("patch", patch);
        return file;
    }
    private static Map<String, Object> modeFile(String path) { return file(path, null, "modified", OLD, null, 0, 0); }
    private void configure(List<Map<String, Object>> before, List<Map<String, Object>> after, List<Map<String, Object>> files) {
        history = List.of(commit(HEAD, ROOT), commit(ROOT));
        int adds = files.stream().mapToInt(file -> (Integer) file.get("additions")).sum();
        int deletes = files.stream().mapToInt(file -> (Integer) file.get("deletions")).sum();
        details = new HashMap<>(Map.of("sha", HEAD, "parents", List.of(Map.of("sha", ROOT)),
                "commit", Map.of("tree", Map.of("sha", AFTER_TREE)),
                "stats", Map.of("additions", adds, "deletions", deletes), "files", files));
        parentDetails = new HashMap<>(Map.of("sha", ROOT, "tree", Map.of("sha", BEFORE_TREE)));
        beforeTree = new HashMap<>(Map.of("sha", BEFORE_TREE, "truncated", false, "tree", before));
        afterTree = new HashMap<>(Map.of("sha", AFTER_TREE, "truncated", false, "tree", after));
        server.handler = request -> {
            if (request.path().endsWith("/commits")) return ok(history);
            if (request.path().endsWith("/git/commits/" + ROOT)) return ok(parentDetails);
            if (request.path().endsWith("/commits/" + HEAD)) return ok(details);
            if (request.path().endsWith("/git/trees/" + BEFORE_TREE)) return ok(beforeTree);
            if (request.path().endsWith("/git/trees/" + AFTER_TREE)) return ok(afterTree);
            return new HttpFixture.Reply(404, "unexpected fixture route");
        };
    }
    private void modeChange() {
        configure(List.of(entry("script.sh", OLD, "100644")), List.of(entry("script.sh", OLD, "100755")), List.of(modeFile("script.sh")));
    }

    @Test void provesAbsentPatchRenameFromPinnedTreesAndDisclosesPathsModesAndManualReview() {
        configure(List.of(entry("before.java", OLD, "100644")), List.of(entry("after.java", OLD, "100644")),
                List.of(file("after.java", "before.java", "renamed", OLD, null, 0, 0)));
        GitReviewBatch batch = client().batch(REPOSITORY, "main", ROOT, Set.of(), 1);
        GitCommit result = batch.commits().getFirst();
        assertThat(batch.checkpointSha()).isEqualTo(HEAD);
        assertThat(result.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(result.authorLogin()).isEqualTo("developer");
        assertThat(result.authorEmail()).isEqualTo("dev@example.invalid");
        assertThat(result.diff()).contains("rename from before.java", "rename to after.java", "old mode 100644", "new mode 100644").doesNotContain("@@");
        assertThat(result.coverageDetails()).contains("AI 본문 검토 없음", "경로·권한·파일 유형", "수동 확인", "동일 blob", "before.java", "after.java");
        assertThat(server.requests).filteredOn(request -> request.path().contains("/git/trees/"))
                .hasSize(2).allSatisfy(request -> assertThat(request.query()).isEqualTo("recursive=1"));
        assertThat(server.requests).allSatisfy(request -> {
            assertThat(request.path()).startsWith("/repos/team/repo/");
            assertThat(request.path()).doesNotContain("attacker", "main");
            assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-token");
        });
    }

    static Stream<List<String>> modeTransitions() {
        return Stream.of(List.of("100644", "100755"), List.of("100755", "100644"),
                List.of("100644", "120000"), List.of("120000", "100644"));
    }

    @ParameterizedTest @MethodSource("modeTransitions")
    void provesExecutableAndBidirectionalSymlinkChangesWithIdenticalBlob(List<String> modes) {
        configure(List.of(entry("config", OLD, modes.getFirst())), List.of(entry("config", OLD, modes.getLast())), List.of(modeFile("config")));
        GitCommit result = review();
        assertThat(result.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(result.diff()).contains("old mode " + modes.getFirst(), "new mode " + modes.getLast());
        assertThat(result.coverageDetails()).contains("파일 유형", "수동 확인", modes.getFirst(), modes.getLast());
    }

    @Test void identicalBlobEvidenceDoesNotClaimTheContentsWereTextOrAiReviewed() {
        configure(List.of(entry("image.bin", OLD, "100644")), List.of(entry("image.bin", OLD, "100755")), List.of(modeFile("image.bin")));
        assertThat(review().coverageDetails()).contains("AI 본문 검토 없음", "동일 blob");
        assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).contains("/git/blobs/"));
    }

    @Test void mixedBodyAndMetadataRemainFullWithBothKindsOfChangeExplicitlyIncluded() {
        configure(List.of(entry("app.java", OLD, "100644"), entry("script.sh", OLD, "100644")),
                List.of(entry("app.java", NEW, "100755"), entry("script.sh", OLD, "100755")),
                List.of(file("app.java", null, "modified", NEW, PATCH, 1, 1), modeFile("script.sh")));
        GitCommit result = review();
        assertThat(result.coverageType()).isEqualTo("FULL");
        assertThat(result.diff()).contains(PATCH, "diff --git a/script.sh b/script.sh", "old mode 100644", "new mode 100755");
        assertThat(result.coverageDetails()).contains("본문 diff와 함께", "app.java", "script.sh", "본문 변경 포함", "본문 변경 없음");
    }

    @Test void renameWithChangedBodyIncludesExactPathsModesAndPatch() {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("new.java", NEW, "100755")),
                List.of(file("new.java", "old.java", "renamed", NEW, PATCH, 1, 1)));
        GitCommit result = review();
        assertThat(result.coverageType()).isEqualTo("FULL");
        assertThat(result.diff()).contains("rename from old.java", "rename to new.java", "new mode 100755", PATCH);
        assertThat(result.coverageDetails()).contains("본문 변경 포함", "old.java", "new.java");
    }

    @Test void verifiesAddedAndRemovedTextFilesAlongsideMetadataAgainstBothTrees() {
        configure(List.of(entry("deleted.java", OLD, "100644"), entry("script.sh", OLD, "100644")),
                List.of(entry("added.java", NEW, "100644"), entry("script.sh", OLD, "100755")),
                List.of(file("deleted.java", null, "removed", OLD, "@@ -1 +0,0 @@\n-old", 0, 1),
                        file("added.java", null, "added", NEW, "@@ -0,0 +1 @@\n+new", 1, 0), modeFile("script.sh")));
        assertThat(review().diff()).contains("b/deleted.java", "b/added.java", "-old", "+new");
    }

    @Test void mergeComparesToPinnedFirstParentEvenWhenOtherParentHasBeenReviewed() {
        modeChange();
        history = List.of(commit(HEAD, ROOT, SIDE), commit(SIDE, ROOT), commit(ROOT));
        details.put("parents", List.of(Map.of("sha", ROOT), Map.of("sha", SIDE)));
        GitReviewBatch result = client().batch(REPOSITORY, null, ROOT, Set.of(SIDE), 1);
        assertThat(result.commits().getFirst().coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(result.checkpointSha()).isEqualTo(HEAD);
        assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).endsWith("/git/commits/" + SIDE));
    }

    @Test void retainsExistingTextOnlyPathWithoutAdditionalTreeRequests() {
        configure(List.of(), List.of(), List.of(file("app.java", null, "modified", NEW, PATCH, 1, 1)));
        assertThat(review().coverageType()).isEqualTo("FULL");
        assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).contains("/git/"));
    }

    @Test void aCopyOrOverwriteCannotMasqueradeAsADeletedSourceRename() {
        for (boolean retainsSource : List.of(true, false)) {
            List<Map<String, Object>> before = new ArrayList<>(List.of(entry("old.java", OLD, "100644")));
            List<Map<String, Object>> after = new ArrayList<>(List.of(entry("new.java", OLD, "100644")));
            if (retainsSource) after.add(entry("old.java", OLD, "100644"));
            else before.add(entry("new.java", NEW, "100644"));
            configure(before, after, List.of(file("new.java", "old.java", "renamed", OLD, null, 0, 0)));
            assertThatThrownBy(this::review).hasMessageContaining("change kind");
        }
    }

    @Test void twoRenamesCannotReuseTheSameDeletedSourceToCoverAHiddenCopy() {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("a.java", OLD, "100644"), entry("b.java", OLD, "100644")),
                List.of(file("a.java", "old.java", "renamed", OLD, null, 0, 0), file("b.java", "old.java", "renamed", OLD, null, 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("complete commit trees");
    }

    @ParameterizedTest @ValueSource(strings = {"copied", "unchanged", "added", "removed", "unknown"})
    void unsupportedOrContradictoryFileStatusesCannotClaimARename(String status) {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("new.java", OLD, "100644")),
                List.of(file("new.java", "old.java", status, OLD, null, 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("change kind");
    }

    @Test void aChangedBlobWithNoBodyAndAnUnchangedPathWithNoRealChangeRemainFailures() {
        configure(List.of(entry("script.sh", OLD, "100644")), List.of(entry("script.sh", NEW, "100755")),
                List.of(file("script.sh", null, "modified", NEW, null, 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("missing or truncated");
        configure(List.of(entry("script.sh", OLD, "100644")), List.of(entry("script.sh", OLD, "100644")), List.of(modeFile("script.sh")));
        assertThatThrownBy(this::review).hasMessageContaining("complete commit trees");
    }

    @Test void newEmptyFilesAreNotReclassifiedAsUnchangedMetadata() {
        configure(List.of(), List.of(entry("empty", OLD, "100644")), List.of(file("empty", null, "added", OLD, null, 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("patch");
    }

    @ParameterizedTest @ValueSource(strings = {"GIT binary patch", "Binary files a/old.java and b/new.java differ",
            "@@ -1 +1 @@\n-old\n+new", "+unexpected", "-unexpected", "@@ invalid @@"})
    void unchangedBlobCannotSuppressBinaryMarkersOrContradictoryText(String patch) {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("new.java", OLD, "100644")),
                List.of(file("new.java", "old.java", "renamed", OLD, patch, 0, 0)));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
    }

    @Test void sameBlobWithPositiveStatisticsIsRejectedEvenWhenThePatchCountsMatch() {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("new.java", OLD, "100644")),
                List.of(file("new.java", "old.java", "renamed", OLD, PATCH, 1, 1)));
        assertThatThrownBy(this::review).hasMessageContaining("contradicts unchanged blob");
    }

    @Test void changedBlobWithOnlyHeadersDoesNotCountAsACompleteTextReview() {
        configure(List.of(entry("old.java", OLD, "100644")), List.of(entry("new.java", NEW, "100644")),
                List.of(file("new.java", "old.java", "renamed", NEW, "diff --git a/old.java b/new.java\n--- a/old.java\n+++ b/new.java", 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("missing or truncated");
    }

    @Test void everyChangedPathIncludingZeroLineOrHiddenBinaryChangesMustHaveAFileEntry() {
        for (String hiddenBlob : List.of(OLD, NEW)) {
            configure(List.of(entry("script.sh", OLD, "100644"), entry("hidden", OLD, "100644")),
                    List.of(entry("script.sh", OLD, "100755"), entry("hidden", hiddenBlob, "100755")), List.of(modeFile("script.sh")));
            assertThatThrownBy(this::review).hasMessageContaining("incomplete compared with commit trees");
        }
    }

    @Test void diffBlobMustMatchTheCurrentImmutableTree() {
        modeChange();
        details.put("files", List.of(file("script.sh", null, "modified", NEW, null, 0, 0)));
        assertThatThrownBy(this::review).hasMessageContaining("diff blob disagrees");
    }

    @Test void submoduleRenamesAndTypeTransitionsRemainUnsupported() {
        for (String newMode : List.of("160000", "100644")) {
            configure(List.of(entry("old", OLD, "160000")), List.of(entry("new", OLD, newMode)),
                    List.of(file("new", "old", "renamed", OLD, null, 0, 0)));
            assertThatThrownBy(this::review).hasMessageContaining("file type");
        }
    }

    @Test void treeIdsAndParentCommitIdsAreBoundToThePinnedCommit() {
        modeChange();
        afterTree.put("sha", BEFORE_TREE);
        assertThatThrownBy(this::review).hasMessageContaining("unexpected tree");
        modeChange();
        beforeTree.put("sha", AFTER_TREE);
        assertThatThrownBy(this::review).hasMessageContaining("unexpected tree");
        modeChange();
        parentDetails.put("sha", SIDE);
        assertThatThrownBy(this::review).hasMessageContaining("unexpected parent");
        modeChange();
        details.put("parents", List.of(Map.of("sha", SIDE)));
        assertThatThrownBy(this::review).hasMessageContaining("parents disagree");
        modeChange();
        details.put("commit", Map.of("tree", Map.of("sha", "main")));
        assertThatThrownBy(this::review).hasMessageContaining("invalid commit identifier");
    }

    @Test void treeCompletenessMustBeExplicitAndCannotUsePaginationLinksAsProof() {
        for (Object truncated : List.of(true, "false", 0)) {
            modeChange();
            afterTree.put("truncated", truncated);
            assertThatThrownBy(this::review).hasMessageContaining("incomplete or truncated");
        }
        modeChange();
        afterTree.remove("truncated");
        assertThatThrownBy(this::review).hasMessageContaining("incomplete or truncated");
        modeChange();
        var usual = server.handler;
        server.handler = request -> request.path().contains("/git/trees/")
                ? new HttpFixture.Reply(200, json.writeValueAsString(afterTree), Map.of("Link", "<https://attacker.invalid>; rel=next"))
                : usual.apply(request);
        assertThatThrownBy(this::review).hasMessageContaining("incomplete or truncated");
    }

    @Test void rejectsDuplicateUnsafeOrInconsistentTreeEntries() {
        List<List<Map<String, Object>>> invalidTrees = List.of(
                List.of(entry("script.sh", OLD, "100755"), entry("script.sh", OLD, "100755")),
                List.of(entry("bad\npath", OLD, "100755")), List.of(entry("../script.sh", OLD, "100755")),
                List.of(entry("script.sh", OLD, "100755\n")), List.of(entry("script.sh", OLD, "040000")),
                List.of(entry("script.sh", "invalid-object", "100755")),
                List.of(Map.of("path", "script.sh", "sha", OLD, "mode", "100755", "type", "tree")));
        for (List<Map<String, Object>> invalid : invalidTrees) {
            modeChange();
            afterTree.put("tree", invalid);
            assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
        }
    }

    @Test void validDirectoryEntriesCanAccompanyRecursiveLeafPaths() {
        Map<String, Object> directory = Map.of("path", "src", "sha", NEW, "mode", "040000", "type", "tree");
        configure(List.of(directory, entry("src/script.sh", OLD, "100644")),
                List.of(directory, entry("src/script.sh", OLD, "100755")), List.of(modeFile("src/script.sh")));
        assertThat(review().coverageType()).isEqualTo("METADATA_ONLY");
    }

    @Test void paginatedFileListsKeepImmutableMetadataAndFullCoverage() {
        configure(List.of(entry("a.sh", OLD, "100644"), entry("b.sh", OLD, "100644")),
                List.of(entry("a.sh", OLD, "100755"), entry("b.sh", OLD, "100755")), List.of(modeFile("a.sh"), modeFile("b.sh")));
        var usual = server.handler;
        Map<String, Object> firstPage = new HashMap<>(details), secondPage = new HashMap<>(details);
        firstPage.put("files", List.of(modeFile("a.sh")));
        secondPage.put("files", List.of(modeFile("b.sh")));
        server.handler = request -> {
            if (!request.path().endsWith("/commits/" + HEAD)) return usual.apply(request);
            return request.query().endsWith("page=1")
                    ? new HttpFixture.Reply(200, json.writeValueAsString(firstPage), Map.of("Link", "<https://attacker.invalid>; rel=next")) : ok(secondPage);
        };
        assertThat(review().coverageDetails()).contains("a.sh", "b.sh");
        secondPage.put("stats", Map.of("additions", 1, "deletions", 0));
        assertThatThrownBy(this::review).hasMessageContaining("inconsistent paginated");
        secondPage.put("stats", firstPage.get("stats"));
        secondPage.put("commit", Map.of("tree", Map.of("sha", BEFORE_TREE)));
        assertThatThrownBy(this::review).hasMessageContaining("inconsistent paginated");
        secondPage.put("commit", firstPage.get("commit"));
        secondPage.put("parents", List.of(Map.of("sha", SIDE)));
        assertThatThrownBy(this::review).hasMessageContaining("inconsistent paginated");
    }

    @Test void coverageDescriptionLimitNeverSilentlyDropsPaths() {
        List<Map<String, Object>> before = new ArrayList<>(), after = new ArrayList<>(), files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String path = i + "x".repeat(950) + ".sh";
            before.add(entry(path, OLD, "100644"));
            after.add(entry(path, OLD, "100755"));
            files.add(modeFile(path));
        }
        configure(before, after, files);
        assertThatThrownBy(this::review).hasMessageContaining("coverage details");
    }

    @Test void completeSyntheticDiffRemainsBounded() {
        List<Map<String, Object>> before = new ArrayList<>(), after = new ArrayList<>(), files = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String path = i + ".sh";
            before.add(entry(path, OLD, "100644"));
            after.add(entry(path, OLD, "100755"));
            files.add(modeFile(path));
        }
        configure(before, after, files);
        assertThatThrownBy(() -> client(1024, 262144, 300).batch(REPOSITORY, null, ROOT, Set.of(), 1))
                .hasMessageContaining("diff exceeds configured size");
    }

    @Test void treeHttpErrorsAndOversizedResponsesCannotProduceMetadataSuccess() {
        modeChange();
        var usual = server.handler;
        for (int status : List.of(302, 401, 429, 503)) {
            server.handler = request -> request.path().contains("/git/trees/")
                    ? new HttpFixture.Reply(status, "PRIVATE fixture-token response", Map.of("Location", "https://attacker.invalid"))
                    : usual.apply(request);
            assertThatThrownBy(this::review).hasMessage("Git returned HTTP " + status);
        }
        server.handler = usual;
        afterTree.put("unused", "x".repeat(2048));
        assertThatThrownBy(() -> client(65536, 1024, 300).batch(REPOSITORY, null, ROOT, Set.of(), 1)).hasMessageContaining("size limit");
    }

    @Test void newTreeRequestsShareTheWholeOperationDeadline() {
        modeChange();
        var usual = server.handler;
        server.handler = request -> {
            HttpFixture.Reply reply = usual.apply(request);
            return request.path().contains("/git/trees/")
                    ? new HttpFixture.Reply(reply.status(), reply.body(), Map.of(), 0, 700) : reply;
        };
        assertThatThrownBy(() -> client(65536, 262144, 1).batch(REPOSITORY, null, ROOT, Set.of(), 1))
                .isInstanceOf(IntegrationException.class).hasMessageMatching(".*(timed out|time budget).*");
    }
}
