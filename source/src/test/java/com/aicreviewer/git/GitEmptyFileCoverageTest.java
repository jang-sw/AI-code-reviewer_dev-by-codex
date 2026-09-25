package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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

class GitEmptyFileCoverageTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    private String provider, root, head, beforeTreeSha, afterTreeSha;
    private boolean rootCommit;
    private List<Map<String, Object>> before, after, files;
    private Map<String, Object> details;

    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private static String emptyBlob(int hashLength) {
        try {
            byte[] header = {'b', 'l', 'o', 'b', ' ', '0', 0};
            return HexFormat.of().formatHex(MessageDigest.getInstance(hashLength == 40 ? "SHA-1" : "SHA-256").digest(header));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
    private Map<String, Object> entry(String path, String sha, String mode) {
        return Map.of("path", path, provider.equals("github") ? "sha" : "id", sha,
                "type", mode.equals("160000") ? "commit" : "blob", "mode", mode);
    }
    private Map<String, Object> file(String path, String sha, boolean created, String patch, int adds, int deletes) {
        Map<String, Object> result = new HashMap<>();
        if (provider.equals("github")) {
            result.putAll(Map.of("filename", path, "sha", sha, "status", created ? "added" : "removed", "additions", adds, "deletions", deletes));
            if (patch != null) result.put("patch", patch);
        } else {
            result.putAll(Map.of("old_path", path, "new_path", path, "new_file", created, "deleted_file", !created, "renamed_file", false));
            if (patch != null) result.put("diff", patch);
        }
        return result;
    }
    private Map<String, Object> commit(String sha, String... parents) {
        return provider.equals("github")
                ? Map.of("sha", sha, "parents", Stream.of(parents).map(parent -> Map.of("sha", parent)).toList(), "commit", Map.of("message", "Empty file change"))
                : Map.of("id", sha, "parent_ids", List.of(parents), "message", "Empty file change");
    }
    private HttpFixture.Reply ok(Object value) { return new HttpFixture.Reply(200, json.writeValueAsString(value)); }
    private void configure(String provider, int hashLength, boolean created, boolean rootCommit, String mode, String blob, String patch) {
        this.provider = provider;
        this.rootCommit = rootCommit;
        root = "a".repeat(hashLength);
        head = "b".repeat(hashLength);
        beforeTreeSha = "c".repeat(hashLength);
        afterTreeSha = "d".repeat(hashLength);
        before = new ArrayList<>();
        after = new ArrayList<>();
        (created ? after : before).add(entry("empty.flag", blob, mode));
        files = new ArrayList<>(List.of(file("empty.flag", blob, created, patch, 0, 0)));
        details = new HashMap<>(Map.of(provider.equals("github") ? "sha" : "id", head,
                "stats", Map.of("additions", 0, "deletions", 0)));
        if (provider.equals("github")) {
            details.put("parents", rootCommit ? List.of() : List.of(Map.of("sha", root)));
            details.put("commit", Map.of("tree", Map.of("sha", afterTreeSha)));
            details.put("files", files);
        }
        server.handler = request -> {
            if (request.path().endsWith("/commits")) return ok(rootCommit ? List.of(commit(head)) : List.of(commit(head, root), commit(root)));
            if (request.path().endsWith("/git/commits/" + root)) return ok(Map.of("sha", root, "tree", Map.of("sha", beforeTreeSha)));
            if (request.path().endsWith("/commits/" + head)) return ok(details);
            if (request.path().endsWith("/git/trees/" + beforeTreeSha)) return ok(Map.of("sha", beforeTreeSha, "truncated", false, "tree", before));
            if (request.path().endsWith("/git/trees/" + afterTreeSha)) return ok(Map.of("sha", afterTreeSha, "truncated", false, "tree", after));
            if (request.path().endsWith("/tree")) return ok(request.query().contains("ref=" + root) ? before : after);
            if (request.path().endsWith("/diff")) return ok(files);
            return new HttpFixture.Reply(404, "unexpected fixture route");
        };
    }
    private GitReviewBatch reviewBatch() {
        var client = new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "", 2, 10, 65536, 262144, 300);
        RepositoryUrl repo = RepositoryUrl.parse(provider.equals("github") ? "https://github.com/team/repo" : server.url() + "/team/repo",
                Set.of("github.com", "127.0.0.1"));
        return client.batch(repo, null, rootCommit ? null : root, Set.of(), 1);
    }
    private GitCommit review() { return reviewBatch().commits().getFirst(); }

    static Stream<Arguments> regularChanges() {
        return Stream.of("github", "gitlab").flatMap(provider -> Stream.of(40, 64).flatMap(length ->
                Stream.of(true, false).flatMap(created -> Stream.of("100644", "100755")
                        .map(mode -> Arguments.of(provider, length, created, mode)))));
    }

    @Test void canonicalProofUsesTheGitObjectHeaderForBothHashFormats() {
        assertThat(emptyBlob(40)).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
        assertThat(emptyBlob(64)).isEqualTo("473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813");
    }

    @ParameterizedTest @MethodSource("regularChanges")
    void canonicalEmptyRegularFilesHaveExplicitCreateDeleteManualCoverage(String provider, int length, boolean created, String mode) {
        configure(provider, length, created, false, mode, emptyBlob(length), null);
        GitReviewBatch batch = reviewBatch();
        GitCommit result = batch.commits().getFirst();
        assertThat(batch.checkpointSha()).isEqualTo(head);
        assertThat(result.coverageType()).isEqualTo("METADATA_ONLY");
        assertThat(result.coverageDetails()).contains("AI 본문 검토 없음", "빈 파일 " + (created ? "생성" : "삭제"),
                "empty.flag", mode, "Git 빈 blob 확인", "본문 없음", "수동 확인");
        assertThat(result.diff()).contains("diff --git a/empty.flag b/empty.flag", (created ? "new file mode " : "deleted file mode ") + mode)
                .doesNotContain("@@", "+body", "-body");
        assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).contains("/blobs/"));
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void rootCommitWithAnEmptyFileIsMetadataOnlyRatherThanAnEmptyCommit(String provider) {
        for (int length : List.of(40, 64)) {
            configure(provider, length, true, true, "100644", emptyBlob(length), "");
            assertThat(review().coverageType()).isEqualTo("METADATA_ONLY");
            assertThat(server.requests).noneSatisfy(request -> assertThat(request.path()).endsWith("/git/commits/" + root));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void mixedRealTextAndEmptyFileChangesKeepTheWholeTextAndExplicitMetadata(String provider) {
        for (boolean created : List.of(true, false)) {
            configure(provider, 40, created, false, "100644", emptyBlob(40), "");
            String addedBlob = "e".repeat(40);
            after.add(entry("app.java", addedBlob, "100644"));
            files.add(file("app.java", addedBlob, true, "@@ -0,0 +1 @@\n+body", 1, 0));
            details.put("stats", Map.of("additions", 1, "deletions", 0));
            GitCommit result = review();
            assertThat(result.coverageType()).isEqualTo("FULL");
            assertThat(result.diff()).contains("@@ -0,0 +1 @@\n+body", created ? "new file mode 100644" : "deleted file mode 100644");
            assertThat(result.coverageDetails()).contains("본문 diff와 함께", "빈 파일 " + (created ? "생성" : "삭제"), "본문 없음");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void canonicalBlobCannotPermitSymlinkOrSubmoduleCreationAndDeletion(String provider) {
        for (String mode : List.of("120000", "160000")) {
            for (boolean created : List.of(true, false)) {
                configure(provider, 40, created, false, mode, emptyBlob(40), "");
                assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void ZeroBytesReportedByMetadataOrHashOfEmptyBytesIsNotCanonicalGitBlobProof(String provider) {
        for (String blob : List.of("e".repeat(40), "da39a3ee5e6b4b0d3255bfef95601890afd80709",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyBlob(64))) {
            configure(provider, 40, true, false, "100644", blob, "");
            after.set(0, new HashMap<>(after.getFirst()));
            after.getFirst().put("size", 0);
            assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void everyAdditionalEmptyFileMustBePresentInTheCommitFileList(String provider) {
        configure(provider, 40, true, true, "100644", emptyBlob(40), null);
        after.add(entry("hidden.empty", emptyBlob(40), "100644"));
        assertThatThrownBy(this::review).hasMessageContaining("incomplete");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void falseCreateDeleteShapeAndCrossPathChangesCannotUseEmptyProof(String provider) {
        configure(provider, 40, true, false, "100644", emptyBlob(40), "");
        before.add(entry("empty.flag", emptyBlob(40), "100644"));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
        configure(provider, 40, true, false, "100644", emptyBlob(40), "");
        files.getFirst().put(provider.equals("github") ? "previous_filename" : "old_path", "other.flag");
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void emptyProofDoesNotSuppressBinaryMarkersAddedDeletedLinesOrHunks(String provider) {
        for (String patch : List.of("GIT binary patch", "Binary files a/empty.flag and b/empty.flag differ", "+unexpected", "-unexpected",
                "@@ -0,0 +0,0 @@", "@@ -0,0 +1 @@\n+unexpected")) {
            configure(provider, 40, true, false, "100644", emptyBlob(40), patch);
            assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void positiveStatisticsContradictAnEmptyFileOnlyCommit(String provider) {
        configure(provider, 40, true, false, "100644", emptyBlob(40), "");
        details.put("stats", Map.of("additions", 1, "deletions", 0));
        if (provider.equals("github")) files.getFirst().put("additions", 1);
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
    }

    @Test void gitlabMustExplicitlyReportTheMatchingCreateDeleteFlags() {
        for (Map<String, Object> flags : List.of(Map.<String, Object>of("new_file", false, "deleted_file", false),
                Map.<String, Object>of("new_file", true, "deleted_file", true),
                Map.<String, Object>of("new_file", "true", "deleted_file", false),
                Map.<String, Object>of("new_file", true, "deleted_file", false, "renamed_file", true))) {
            configure("gitlab", 40, true, false, "100644", emptyBlob(40), "");
            files.getFirst().putAll(flags);
            assertThatThrownBy(this::review).hasMessageContaining("empty-file change flags");
        }
        configure("gitlab", 40, true, false, "100644", emptyBlob(40), "");
        files.getFirst().remove("new_file");
        assertThatThrownBy(this::review).hasMessageContaining("empty-file change flags");
    }

    @Test void gitlabCollapsedAndOversizedFlagsStillFailForCanonicalEmptyBlobs() {
        for (String flag : List.of("collapsed", "too_large")) {
            configure("gitlab", 40, true, false, "100644", emptyBlob(40), "");
            files.getFirst().put(flag, true);
            assertThatThrownBy(this::review).hasMessageContaining("collapsed or oversized");
        }
    }

    @Test void githubFileIdentifierMustMatchTheEmptyBlobActuallyPresentInTheTree() {
        configure("github", 40, true, false, "100644", emptyBlob(40), "");
        after.set(0, entry("empty.flag", "e".repeat(40), "100644"));
        assertThatThrownBy(this::review).hasMessageContaining("diff blob disagrees");
        configure("github", 40, true, false, "100644", emptyBlob(40), "");
        files.getFirst().put("status", "removed");
        assertThatThrownBy(this::review).hasMessageContaining("change kind");
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void anEmptyFileDoesNotHideAnotherChangedBinaryWithNoPatch(String provider) {
        configure(provider, 40, true, false, "100644", emptyBlob(40), "");
        String binary = "e".repeat(40);
        after.add(entry("image.bin", binary, "100644"));
        files.add(file("image.bin", binary, true, null, 0, 0));
        assertThatThrownBy(this::review).isInstanceOf(IntegrationException.class);
    }
}
