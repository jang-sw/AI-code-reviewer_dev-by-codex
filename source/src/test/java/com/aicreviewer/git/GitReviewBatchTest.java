package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GitReviewBatchTest {
    private static final String A = sha(1), B = sha(2), C = sha(3), D = sha(4), E = sha(5), F = sha(6), ORPHAN = sha(99999);
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;
    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    @Test void partialMergeGroupMakesDurableProgressForBothProvidersWithOneCommitPerRun() {
        for (boolean github : List.of(true, false)) {
            graph(github, List.of(node(D, B, C), node(C, A), node(B, A), node(A)), D);
            GitReviewBatch first = batch(github, null, Set.of(), 1);
            assertSelection(first, A, A);
            GitReviewBatch main = batch(github, A, Set.of(A), 1);
            assertSelection(main, B, B);
            GitReviewBatch side = batch(github, B, Set.of(A, B), 1);
            assertSelection(side, null, C);
            GitReviewBatch merge = batch(github, B, Set.of(A, B, C), 1);
            assertSelection(merge, D, D);
            GitReviewBatch complete = batch(github, D, Set.of(A, B, C, D), 1);
            assertSelection(complete, null);
        }
    }

    @Test void sideBranchThatPrecedesCursorInAnArbitraryTopologicalListIsNeverSkipped() {
        // A,C,B,D is also topological, but only first-parent-priority A,B,C,D makes B a safe prefix.
        graph(true, List.of(node(A), node(C, A), node(B, A), node(D, B, C)), D);
        assertSelection(batch(true, B, Set.of(A, B), 1), null, C);
    }

    @Test void completedTailCanAdvanceBeyondLastSelectedCommitOrWithoutSelectingAnything() {
        graph(true, List.of(node(D, B, C), node(C, A), node(B, A), node(A)), D);
        assertSelection(batch(true, B, Set.of(A, B, D), 1), D, C);
        server.requests.clear();
        assertSelection(batch(true, B, Set.of(A, B, C, D), 1), D);
        assertThat(diffRequests(true)).isEmpty();
    }

    @Test void retryAfterPartialSuccessFetchesOnlyRemainingDiffsAndLeavesOriginalCursorValid() {
        graph(true, List.of(node(E, B, D), node(D, C), node(C, A), node(B, A), node(A)), E);
        assertSelection(batch(true, B, Set.of(A, B), 2), null, C, D);
        // C was durably stored before AI or persistence failed for D; checkpoint B did not move.
        server.requests.clear();
        assertSelection(batch(true, B, Set.of(A, B, C), 2), E, D, E);
        assertThat(diffRequests(true)).containsExactly(D, E);
    }

    @Test void newHeadAndReorderedSideBranchesReuseReviewedNodesWithoutSkippingAnEarlierHole() {
        // Previous partial run reviewed C, then the branch moved while retaining first-parent B.
        graph(true, List.of(node(F, B, E, D), node(E, A), node(D, C), node(C, A), node(B, A), node(A)), F);
        assertSelection(batch(true, B, Set.of(A, B, C), 1), null, E);
        server.requests.clear();
        assertSelection(batch(true, B, Set.of(A, B, C, E), 2), F, D, F);
        assertThat(diffRequests(true)).containsExactly(D, F);
    }

    @Test void orphanReviewsCannotCloseAnyCurrentGraphGap() {
        graph(true, List.of(node(D, B, C), node(C, A), node(B, A), node(A)), D);
        assertSelection(batch(true, B, Set.of(A, B, D, ORPHAN), 1), D, C);
        assertSelection(batch(true, B, Set.of(A, B, ORPHAN), 1), null, C);
    }

    @Test void cursorRewritesStillFailEvenWhenEveryCurrentCommitIsAlreadyReviewed() {
        graph(true, List.of(node(D, C, B), node(C, A), node(B, A), node(A)), D);
        assertThatThrownBy(() -> batch(true, B, Set.of(A, B, C, D), 5)).hasMessageContaining("first-parent");
        graph(true, List.of(node(A)), A);
        assertThatThrownBy(() -> batch(true, B, Set.of(A, B), 5)).hasMessageContaining("first-parent");
        assertThat(diffRequests(true)).isEmpty();
    }

    @Test void missingParentsAreNotHiddenByCompletedShaSet() {
        graph(true, List.of(node(B, A)), B);
        assertThatThrownBy(() -> batch(true, null, Set.of(A, B), 5)).hasMessageContaining("parent commit is missing");
    }

    @Test void mergeGroupLargerThanMaximumBatchResumesWithoutRefetchingReviewedDiffs() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node(A));
        nodes.add(node(B, A));
        String parent = A;
        List<String> side = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            String id = sha(100 + i);
            side.add(id);
            nodes.add(node(id, parent));
            parent = id;
        }
        String merge = sha(2000);
        nodes.add(node(merge, B, parent));
        Collections.reverse(nodes);
        graph(true, nodes, merge);
        Set<String> reviewed = new HashSet<>(Set.of(A, B));
        reviewed.addAll(side.subList(0, 850));
        GitReviewBatch partial = batch(true, B, reviewed, 100);
        assertThat(partial.commits()).extracting(GitCommit::sha).containsExactlyElementsOf(side.subList(850, 950));
        assertThat(partial.checkpointSha()).isNull();
        assertThat(diffRequests(true)).containsExactlyElementsOf(side.subList(850, 950));
        reviewed.addAll(side.subList(850, 950));
        server.requests.clear();
        GitReviewBatch finished = batch(true, B, reviewed, 100);
        List<String> expected = new ArrayList<>(side.subList(950, 1001));
        expected.add(merge);
        assertThat(finished.commits()).extracting(GitCommit::sha).containsExactlyElementsOf(expected);
        assertThat(finished.checkpointSha()).isEqualTo(merge);
        assertThat(diffRequests(true)).containsExactlyElementsOf(expected);
        assertThat(server.requests.stream().filter(request -> request.path().endsWith("/commits")
                && !request.query().equals("per_page=1"))).hasSize(11);
    }

    @Test void legacyWrapperKeepsItsSafeLastCommitContract() {
        graph(true, List.of(node(D, B, C), node(C, A), node(B, A), node(A)), D);
        assertThat(client().commits(repository(true), null, null, 3)).extracting(GitCommit::sha).containsExactly(A, B);
        assertThatThrownBy(() -> client().commits(repository(true), null, B, 1)).hasMessageContaining("complete merge group");
    }

    @Test void invalidOrOversizedDurableProgressFailsBeforeNetworkOrCopying() {
        Set<String> oversized = new AbstractSet<>() {
            @Override public int size() { return GitRepositoryClient.MAX_REVIEWED_SHAS + 1; }
            @Override public Iterator<String> iterator() { throw new AssertionError("Oversized input must not be copied"); }
        };
        assertThatThrownBy(() -> batch(true, null, oversized, 1)).hasMessageContaining("memory safety budget");
        assertThatThrownBy(() -> batch(true, null, Set.of("not-a-sha"), 1)).hasMessageContaining("invalid commit identifier");
        assertThatThrownBy(() -> batch(true, null, null, 1)).hasMessageContaining("memory safety budget");
        assertThat(server.requests).isEmpty();
    }

    private void assertSelection(GitReviewBatch batch, String checkpoint, String... selected) {
        assertThat(batch.commits()).extracting(GitCommit::sha).containsExactly(selected);
        assertThat(batch.checkpointSha()).isEqualTo(checkpoint);
    }
    private GitReviewBatch batch(boolean github, String cursor, Set<String> reviewed, int limit) {
        return client().batch(repository(github), null, cursor, reviewed, limit);
    }
    private GitRepositoryClient client() {
        return new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "",
                2, 30, 65536, 2097152, 300);
    }
    private RepositoryUrl repository(boolean github) {
        return RepositoryUrl.parse(github ? "https://github.com/team/repo" : server.url() + "/team/repo", Set.of("github.com", "127.0.0.1"));
    }
    private List<String> diffRequests(boolean github) {
        return server.requests.stream().filter(request -> github ? !request.path().endsWith("/commits") : request.path().endsWith("/diff"))
                .map(request -> {
                    String path = github ? request.path() : request.path().substring(0, request.path().length() - 5);
                    return path.substring(path.lastIndexOf('/') + 1);
                }).toList();
    }
    private void graph(boolean github, List<Node> nodes, String head) {
        server.requests.clear();
        server.handler = request -> {
            if (request.path().endsWith("/commits")) {
                if (request.query().equals("per_page=1")) return ok(nodes.stream().filter(node -> node.sha().equals(head)).map(node -> metadata(node, github)).toList());
                assertThat(request.query()).contains((github ? "sha=" : "ref_name=") + head);
                int page = Integer.parseInt(java.util.Arrays.stream(request.query().split("&"))
                        .filter(part -> part.startsWith("page=")).findFirst().orElseThrow().substring(5));
                int start = Math.min(nodes.size(), (page - 1) * 100), end = Math.min(nodes.size(), page * 100);
                return ok(nodes.subList(start, end).stream().map(node -> metadata(node, github)).toList());
            }
            if (!github && request.path().endsWith("/tree")) {
                String ref = java.util.Arrays.stream(request.query().split("&")).filter(part -> part.startsWith("ref=")).findFirst().orElseThrow().substring(4);
                return ok(List.of(Map.of("path", "app.txt", "id", ref, "type", "blob", "mode", "100644")));
            }
            if (!github && request.path().endsWith("/diff")) {
                return ok(List.of(Map.of("old_path", "app.txt", "new_path", "app.txt", "diff", "@@ -0,0 +1,1 @@\n+one")));
            }
            String id = request.path().substring(request.path().lastIndexOf('/') + 1);
            return github ? ok(Map.of("sha", id, "stats", Map.of("additions", 1, "deletions", 0), "files", List.of(
                    Map.of("filename", "app.txt", "additions", 1, "deletions", 0, "patch", "@@ -0,0 +1,1 @@\n+one"))))
                    : ok(Map.of("id", id, "stats", Map.of("additions", 1, "deletions", 0)));
        };
    }
    private Map<String, Object> metadata(Node node, boolean github) {
        return github ? Map.of("sha", node.sha(), "parents", node.parents().stream().map(parent -> Map.of("sha", parent)).toList(), "commit", Map.of("message", "Fixture"))
                : Map.of("id", node.sha(), "parent_ids", node.parents(), "message", "Fixture");
    }
    private HttpFixture.Reply ok(Object body) { return new HttpFixture.Reply(200, json.writeValueAsString(body)); }
    private static Node node(String sha, String... parents) { return new Node(sha, List.of(parents)); }
    private static String sha(int value) { return String.format("%040x", value); }
    private record Node(String sha, List<String> parents) { }
}
