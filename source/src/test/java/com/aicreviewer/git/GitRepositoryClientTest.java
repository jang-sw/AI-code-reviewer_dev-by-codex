package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitRepositoryClientTest {
    private static final String A = "a".repeat(40), B = "b".repeat(40), C = "c".repeat(40), D = "d".repeat(40);
    private static final RepositoryUrl GITHUB = RepositoryUrl.parse("https://github.com/team/repo.git", Set.of("github.com"));
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }
    private GitRepositoryClient client() { return new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "fixture-token", "github.com", "", 2, 10, 65536, 262144, 300); }

    @Test void pinsPaginatedHistoryAndReturnsOldestBatchThenNextBatch() {
        server.handler = request -> {
            if (request.query().equals("per_page=1")) return ok(List.of(gh(C, B)));
            if (request.path().endsWith("/commits")) {
                assertThat(request.query()).contains("sha=" + C);
                if (request.query().endsWith("page=1")) return new HttpFixture.Reply(200, json.writeValueAsString(List.of(gh(C, B), gh(B, A))),
                        Map.of("Link", "<https://attacker.invalid/do-not-follow>; rel=\"next\""));
                return ok(List.of(gh(A)));
            }
            return detail(request.path().substring(request.path().lastIndexOf('/') + 1));
        };
        assertThat(client().commits(GITHUB, null, null, 2)).extracting(GitCommit::sha).containsExactly(A, B);
        assertThat(client().commits(GITHUB, null, B, 2)).extracting(GitCommit::sha).containsExactly(C);
        assertThat(server.requests).allSatisfy(request -> assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-token"));
    }

    @Test void includesRootAndMergeDiffsAndOrdersOlderMergedCommitsAfterExistingFirstParent() {
        graph(List.of(gh(D, B, C), gh(C, A), gh(B, A), gh(A)), D);
        assertThat(client().commits(GITHUB, null, B, 10)).extracting(GitCommit::sha).containsExactly(C, D);
        assertThat(client().commits(GITHUB, null, null, 3)).extracting(GitCommit::sha).containsExactly(A, B);
        assertThat(client().commits(GITHUB, null, null, 4)).extracting(GitCommit::sha).containsExactly(A, B, C, D);
        assertThatThrownBy(() -> client().commits(GITHUB, null, B, 1)).hasMessageContaining("merge group");
        assertThatThrownBy(() -> client().commits(GITHUB, null, C, 10)).hasMessageContaining("first-parent");
    }

    @Test void rejectsPreviouslyReviewedHeadThatIsNowOnlySecondParent() {
        graph(List.of(gh(D, C, B), gh(C, A), gh(B, A), gh(A)), D);
        assertThatThrownBy(() -> client().commits(GITHUB, null, B, 10)).hasMessageContaining("first-parent");
    }

    @Test void rejectsMissingParentCursorAndDuplicateHistoryInsteadOfSkipping() {
        graph(List.of(gh(B, A)), B);
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 10)).hasMessageContaining("parent commit is missing");
        graph(List.of(gh(A)), A);
        assertThatThrownBy(() -> client().commits(GITHUB, null, B, 10)).hasMessageContaining("first-parent");
        graph(List.of(gh(A), gh(A)), A);
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 10)).hasMessageContaining("duplicate commits");
    }

    @Test void rejectsPageBudgetOverflowAndTruncatedOrBinaryPatches() {
        server.handler = request -> request.query().equals("per_page=1") ? ok(List.of(gh(B, A)))
                : new HttpFixture.Reply(200, json.writeValueAsString(List.of(gh(B, A))), Map.of("Link", "<ignored>; rel=next"));
        GitRepositoryClient tiny = new GitRepositoryClient(Set.of("github.com"), server.url(), "", "github.com", "", 2, 1, 4096, 65536, 300);
        assertThatThrownBy(() -> tiny.commits(GITHUB, null, null, 1)).hasMessageContaining("pagination budget");
        server.handler = request -> request.path().endsWith("/commits") ? ok(List.of(gh(A)))
                : ok(Map.of("sha", A, "stats", Map.of("additions", 2, "deletions", 0), "files", List.of(
                        Map.of("filename", "a.java", "additions", 2, "deletions", 0, "patch", "@@ -0,0 +1,1 @@\n+one"))));
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 1)).hasMessageContaining("truncated");
        server.handler = request -> request.path().endsWith("/commits") ? ok(List.of(gh(A)))
                : ok(Map.of("sha", A, "stats", Map.of("additions", 0, "deletions", 0), "files", List.of(
                        Map.of("filename", "binary.png", "additions", 0, "deletions", 0))));
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 1)).hasMessageContaining("patch");
    }

    @Test void handlesGitlabEncodedNamespaceTreeVerificationAndScopedToken() {
        gitlab(false, false);
        RepositoryUrl repository = RepositoryUrl.parse(server.url() + "/group/sub/repo.git", Set.of("127.0.0.1"));
        List<GitCommit> result = client().commits(repository, "main", null, 5);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().authorLogin()).isNull();
        assertThat(result.getFirst().diff()).contains("+one");
        assertThat(server.requests).allSatisfy(request -> {
            assertThat(request.path()).startsWith("/api/v4/projects/group%2Fsub%2Frepo/");
            assertThat(request.header("PRIVATE-TOKEN")).isNull();
            assertThat(request.header("Authorization")).isNull();
        });
        assertThat(server.requests).anySatisfy(request -> assertThat(request.query()).contains("ref_name=main"));
    }

    @Test void detectsGitlabSilentFileCapEvenWhenMissingFileHasZeroChangedLines() {
        gitlab(true, false);
        RepositoryUrl repository = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        assertThatThrownBy(() -> client().commits(repository, null, null, 5)).hasMessageContaining("incomplete");
    }

    @Test void gitlabBinaryMarkersInsideAddedSourceRemainReviewable() {
        gitlab(false, false);
        var usual = server.handler;
        server.handler = request -> request.path().endsWith("/diff")
                ? ok(List.of(Map.of("old_path", "a.java", "new_path", "a.java", "collapsed", false,
                        "too_large", false, "diff", "@@ -0,0 +1,1 @@\n+String message = \"Binary files differ; GIT binary patch\";")))
                : usual.apply(request);
        RepositoryUrl repository = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        assertThat(client().commits(repository, null, null, 5).getFirst().diff())
                .contains("+String message = \"Binary files differ; GIT binary patch\";");
    }

    @Test void gitlabStandaloneBinaryMarkersAreRejected() {
        gitlab(false, false);
        var usual = server.handler;
        RepositoryUrl repository = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        for (String marker : List.of("Binary files a/a.java and b/a.java differ", "GIT binary patch")) {
            server.handler = request -> request.path().endsWith("/diff")
                    ? ok(List.of(Map.of("old_path", "a.java", "new_path", "a.java", "collapsed", false,
                            "too_large", false, "diff", marker))) : usual.apply(request);
            assertThatThrownBy(() -> client().commits(repository, null, null, 5)).hasMessageContaining("non-text file diff");
        }
    }

    @Test void rejectsGitlabCollapsedDiffAndUsesConfiguredGitlabToken() {
        gitlab(false, true);
        RepositoryUrl repository = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        GitRepositoryClient gitlab = new GitRepositoryClient(Set.of("127.0.0.1"), server.url(), "gitlab-fixture-token", "127.0.0.1", server.url(), 2, 10, 65536, 262144, 300);
        assertThatThrownBy(() -> gitlab.commits(repository, null, null, 5)).hasMessageContaining("collapsed");
        assertThat(server.requests).allSatisfy(request -> assertThat(request.header("PRIVATE-TOKEN")).isEqualTo("gitlab-fixture-token"));
    }

    @Test void tokenNeverReachesSameHostnameOnAnotherPort() throws Exception {
        gitlab(false, false);
        try (HttpFixture untrusted = new HttpFixture()) {
            GitRepositoryClient scoped = new GitRepositoryClient(Set.of("127.0.0.1"), server.url(), "gitlab-fixture-token",
                    "127.0.0.1", server.url(), 2, 10, 65536, 262144, 300);
            RepositoryUrl trusted = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
            RepositoryUrl otherPort = RepositoryUrl.parse(untrusted.url() + "/group/sub/repo", Set.of("127.0.0.1"));
            assertThat(scoped.commits(trusted, null, null, 5)).hasSize(1);
            assertThat(server.requests).allSatisfy(request -> assertThat(request.header("PRIVATE-TOKEN")).isEqualTo("gitlab-fixture-token"));
            assertThatThrownBy(() -> scoped.commits(otherPort, null, null, 5)).hasMessageContaining("credential origin");
            assertThat(untrusted.requests).isEmpty();
        }
    }

    @Test void tokenNeverReachesHttpWhenHttpsOriginWasConfigured() {
        String httpsOrigin = server.url().replace("http://", "https://");
        GitRepositoryClient scoped = new GitRepositoryClient(Set.of("127.0.0.1"), server.url(), "gitlab-fixture-token",
                "127.0.0.1", httpsOrigin, 2, 10, 65536, 262144, 300);
        RepositoryUrl insecure = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        assertThatThrownBy(() -> scoped.commits(insecure, null, null, 5)).hasMessageContaining("credential origin");
        assertThat(server.requests).isEmpty();
    }

    @Test void legacyTokenHostDefaultsToHttpsAndCustomHttpRequiresExplicitOrigin() {
        GitRepositoryClient legacy = new GitRepositoryClient(Set.of("127.0.0.1"), server.url(), "gitlab-fixture-token",
                "127.0.0.1", "", 2, 10, 65536, 262144, 300);
        RepositoryUrl insecure = RepositoryUrl.parse(server.url() + "/group/sub/repo", Set.of("127.0.0.1"));
        assertThatThrownBy(() -> legacy.commits(insecure, null, null, 5)).hasMessageContaining("credential origin");
        assertThat(server.requests).isEmpty();
        assertThatThrownBy(() -> new GitRepositoryClient(Set.of("127.0.0.1"), server.url(), "gitlab-fixture-token",
                "127.0.0.1", server.url() + "/path", 2, 10, 65536, 262144, 300))
                .hasMessageContaining("only scheme, host and optional port");
    }

    @Test void rejectsRedirectsErrorsAndMalformedJsonWithoutExposingServerBody() {
        for (int status : List.of(302, 401, 429, 500)) {
            server.handler = request -> new HttpFixture.Reply(status, "fixture-token SECRET REMOTE BODY", Map.of("Location", "http://attacker.invalid"));
            assertThatThrownBy(() -> client().commits(GITHUB, null, null, 1)).hasMessage("Git returned HTTP " + status);
        }
        assertThat(server.requests).hasSize(4);
        server.handler = request -> new HttpFixture.Reply(200, "not json SECRET REMOTE BODY");
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 1)).hasMessage("Git returned invalid JSON");
    }

    @Test void boundsSlowBodyAndOversizedResponse() {
        GitRepositoryClient quick = new GitRepositoryClient(Set.of("github.com"), server.url(), "", "github.com", "", 1, 10, 4096, 1024, 300);
        server.handler = request -> new HttpFixture.Reply(200, "[]", Map.of(), 0, 1800);
        assertThatThrownBy(() -> quick.commits(GITHUB, null, null, 1)).hasMessageContaining("timed out");
        server.handler = request -> new HttpFixture.Reply(200, "x".repeat(2048));
        assertThatThrownBy(() -> quick.commits(GITHUB, null, null, 1)).hasMessageContaining("size limit");
    }

    @Test void boundsWholeOperationAcrossMultipleRequests() {
        GitRepositoryClient bounded = new GitRepositoryClient(Set.of("github.com"), server.url(), "", "github.com", "", 3, 10, 4096, 65536, 1);
        server.handler = request -> new HttpFixture.Reply(200, json.writeValueAsString(List.of(gh(A))), Map.of(), 600, 0);
        assertThatThrownBy(() -> bounded.commits(GITHUB, null, null, 1)).hasMessageContaining("timed out");
        assertThat(server.requests).hasSize(2);
    }

    @Test void rejectsDiffHeaderSpoofingAndCredentialControlCharacters() {
        server.handler = request -> request.path().endsWith("/commits") ? ok(List.of(gh(A)))
                : ok(Map.of("sha", A, "stats", Map.of("additions", 1, "deletions", 0), "files", List.of(
                        Map.of("filename", "a.java\ndiff --git a/fake b/fake", "additions", 1, "deletions", 0, "patch", "@@ -0,0 +1,1 @@\n+one"))));
        assertThatThrownBy(() -> client().commits(GITHUB, null, null, 1)).hasMessageContaining("unsafe file path");
        assertThatThrownBy(() -> new GitRepositoryClient(Set.of("github.com"), server.url(), "SECRET\r\n", "github.com", "", 3, 10, 4096, 65536, 1))
                .hasMessage("Invalid service credential configuration");
    }

    @Test void paginatesGithubDiffFilesAndChecksWholeCommitStatistics() {
        server.handler = request -> {
            if (request.path().endsWith("/commits")) return ok(List.of(gh(A)));
            boolean first = request.query().endsWith("page=1");
            return new HttpFixture.Reply(200, json.writeValueAsString(Map.of("sha", A,
                    "stats", Map.of("additions", 2, "deletions", 0), "files", List.of(
                            Map.of("filename", first ? "a.java" : "b.java", "additions", 1, "deletions", 0, "patch", "@@ -0,0 +1,1 @@\n+one")))),
                    first ? Map.of("Link", "<ignored>; rel=\"next\"") : Map.of());
        };
        assertThat(client().commits(GITHUB, null, null, 1).getFirst().diff()).contains("a/a.java b/a.java", "a/b.java b/b.java");
    }

    private void graph(List<Map<String, Object>> commits, String head) {
        server.handler = request -> request.path().endsWith("/commits")
                ? ok(request.query().equals("per_page=1") ? commits.stream().filter(commit -> commit.get("sha").equals(head)).toList() : commits)
                : detail(request.path().substring(request.path().lastIndexOf('/') + 1));
    }
    private Map<String, Object> gh(String sha, String... parents) {
        return Map.of("sha", sha, "parents", java.util.Arrays.stream(parents).map(parent -> Map.of("sha", parent)).toList(),
                "commit", Map.of("message", "Commit " + sha.charAt(0)), "author", Map.of("login", "developer"));
    }
    private HttpFixture.Reply detail(String sha) {
        return ok(Map.of("sha", sha, "stats", Map.of("additions", 1, "deletions", 0), "files", List.of(
                Map.of("filename", "a.java", "additions", 1, "deletions", 0, "patch", "@@ -0,0 +1,1 @@\n+one"))));
    }
    private void gitlab(boolean missingFile, boolean collapsed) {
        server.handler = request -> {
            if (request.path().endsWith("/repository/commits")) return ok(List.of(Map.of("id", A, "parent_ids", List.of(), "message", "Root", "author_name", "unverified-display-name")));
            if (request.path().endsWith("/repository/commits/" + A)) return ok(Map.of("id", A, "stats", Map.of("additions", 1, "deletions", 0)));
            if (request.path().endsWith("/tree")) return ok(missingFile ? List.of(tree("a.java"), tree("binary.png")) : List.of(tree("a.java")));
            if (request.path().endsWith("/diff")) return ok(List.of(Map.of("old_path", "a.java", "new_path", "a.java", "collapsed", collapsed,
                    "too_large", false, "diff", "@@ -0,0 +1,1 @@\n+one")));
            return new HttpFixture.Reply(404, "{}");
        };
    }
    private Map<String, String> tree(String path) { return Map.of("path", path, "type", "blob", "id", B, "mode", "100644"); }
    private HttpFixture.Reply ok(Object value) { return new HttpFixture.Reply(200, json.writeValueAsString(value)); }
}
