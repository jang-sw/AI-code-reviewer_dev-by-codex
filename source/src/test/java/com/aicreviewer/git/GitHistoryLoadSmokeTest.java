package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in correctness/load probe. Every HTTP request stays on loopback; no AI, database or real repository is used. */
@EnabledIfEnvironmentVariable(named = "RUN_GIT_LOAD_SMOKE", matches = "true")
class GitHistoryLoadSmokeTest {
    private static final int HISTORY_SIZE = 10000;
    private static final int PAGE_SIZE = 100;
    private static final int BATCH_SIZE = 100;
    // The adapter conservatively requests an empty sentinel after a completely full final page.
    private static final int HISTORY_REQUESTS = 1 + HISTORY_SIZE / PAGE_SIZE + 1;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String, Object>> phases = new ArrayList<>();

    @Test
    void reviewsTenThousandCommitHistoriesWithoutRefetchingAlreadyPersistedDiffs() throws Exception {
        writeReport();
        for (String provider : List.of("github", "gitlab")) {
            try (HttpFixture server = new HttpFixture()) {
                installHistory(server, provider);
                var client = new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "",
                        10, 120, 65536, 1048576, 300);
                RepositoryUrl repository = RepositoryUrl.parse(provider.equals("github")
                        ? "https://github.com/load/history" : server.url() + "/load/history", Set.of("github.com", "127.0.0.1"));

                verifyPhase(server, provider, client, repository, "initial_batch", Set.of(), 0, BATCH_SIZE, sha(BATCH_SIZE - 1));
                Set<String> firstPersisted = new HashSet<>(IntStream.range(0, BATCH_SIZE).mapToObj(GitHistoryLoadSmokeTest::sha).toList());
                // Model a crash after commit persistence but before checkpoint finalization: cursor is still null.
                verifyPhase(server, provider, client, repository, "persisted_batch_retry", firstPersisted,
                        BATCH_SIZE, BATCH_SIZE, sha(2 * BATCH_SIZE - 1));
                Set<String> allPersisted = new HashSet<>(IntStream.range(0, HISTORY_SIZE).mapToObj(GitHistoryLoadSmokeTest::sha).toList());
                verifyPhase(server, provider, client, repository, "finalize_already_persisted_history", allPersisted,
                        HISTORY_SIZE, 0, sha(HISTORY_SIZE - 1));
            }
        }
        assertThat(phases).hasSize(6).allSatisfy(phase -> assertThat(phase.get("validated")).isEqualTo(true));
    }

    private void verifyPhase(HttpFixture server, String provider, GitRepositoryClient client, RepositoryUrl repository,
            String phaseName, Set<String> persisted, int firstIndex, int count, String checkpoint) throws Exception {
        int startRequest = server.requests.size();
        long start = System.nanoTime();
        Map<String, Object> measurement = new LinkedHashMap<>();
        measurement.put("provider", provider);
        measurement.put("phase", phaseName);
        measurement.put("persistedShaCount", persisted.size());
        measurement.put("requestedBatchLimit", BATCH_SIZE);
        measurement.put("expectedSelectedCount", count);
        measurement.put("validated", false);
        phases.add(measurement);
        try {
            GitReviewBatch batch = client.batch(repository, null, null, persisted, BATCH_SIZE);
            measurement.put("selectedCount", batch.commits().size());
            measurement.put("checkpointSha", batch.checkpointSha());
            List<String> expected = IntStream.range(firstIndex, firstIndex + count).mapToObj(GitHistoryLoadSmokeTest::sha).toList();
            assertThat(batch.commits()).extracting(GitCommit::sha).containsExactlyElementsOf(expected);
            assertThat(batch.checkpointSha()).isEqualTo(checkpoint);
            assertThat(batch.commits()).allSatisfy(commit -> {
                assertThat(commit.coverageType()).isEqualTo("FULL");
                assertThat(commit.diff()).contains("diff --git a/app.txt b/app.txt", "+line-");
                assertThat(persisted).doesNotContain(commit.sha());
            });
            List<HttpFixture.Request> requests = List.copyOf(server.requests.subList(startRequest, server.requests.size()));
            Map<String, Long> counts = counts(requests);
            assertThat(counts.get("history")).isEqualTo((long) HISTORY_REQUESTS);
            assertThat(counts.get("details")).isEqualTo((long) count);
            assertThat(counts.get("other")).isZero();
            if (provider.equals("github")) {
                assertThat(counts.get("trees")).isZero();
                assertThat(counts.get("diffs")).isZero();
            } else {
                assertThat(counts.get("diffs")).isEqualTo((long) count);
                assertThat(counts.get("trees")).isBetween((long) count, 2L * count);
            }
            int requestBudget = HISTORY_REQUESTS + (provider.equals("github") ? count : 4 * count);
            measurement.put("requestBudget", requestBudget);
            assertThat(requests).hasSizeLessThanOrEqualTo(requestBudget);
            assertThat(requests).filteredOn(request -> category(request).equals("details"))
                    .extracting(request -> request.path().substring(request.path().lastIndexOf('/') + 1))
                    .containsExactlyElementsOf(expected);
            if (provider.equals("gitlab")) {
                assertThat(requests).filteredOn(request -> category(request).equals("diffs"))
                        .extracting(request -> request.path().substring(request.path().indexOf("/commits/") + 9,
                                request.path().length() - "/diff".length()))
                        .containsExactlyElementsOf(expected);
            }
            assertThat(requests).allSatisfy(request -> {
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.header("Host")).isEqualTo(URI.create(server.url()).getRawAuthority());
                assertThat(request.header("Authorization")).isNull();
                assertThat(request.header("PRIVATE-TOKEN")).isNull();
                if (category(request).equals("history") && !query(request.query()).get("per_page").equals("1")) {
                    assertThat(query(request.query()).get(provider.equals("github") ? "sha" : "ref_name")).isEqualTo(sha(HISTORY_SIZE - 1));
                }
            });
            measurement.put("validated", true);
        } catch (RuntimeException | AssertionError failure) {
            measurement.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            measurement.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            List<HttpFixture.Request> observed = List.copyOf(server.requests.subList(startRequest, server.requests.size()));
            measurement.put("requestCount", observed.size());
            measurement.put("requestsByKind", counts(observed));
            writeReport();
        }
    }

    private void installHistory(HttpFixture server, String provider) {
        List<Map<String, Object>> history = IntStream.range(0, HISTORY_SIZE)
                .mapToObj(index -> commit(provider, HISTORY_SIZE - 1 - index)).toList();
        String base = provider.equals("github") ? "/repos/load/history" : "/api/v4/projects/load%2Fhistory/repository";
        server.handler = request -> {
            Map<String, String> params = query(request.query());
            if (request.path().equals(base + "/commits")) {
                if ("1".equals(params.get("per_page"))) return ok(List.of(history.getFirst()));
                if (!sha(HISTORY_SIZE - 1).equals(params.get(provider.equals("github") ? "sha" : "ref_name"))) {
                    return new HttpFixture.Reply(400, "history must be pinned");
                }
                int page = Integer.parseInt(params.getOrDefault("page", "0"));
                if (page < 1 || page > HISTORY_SIZE / PAGE_SIZE + 1) return new HttpFixture.Reply(400, "page outside fixture budget");
                int from = Math.min((page - 1) * PAGE_SIZE, HISTORY_SIZE), to = Math.min(from + PAGE_SIZE, HISTORY_SIZE);
                return ok(history.subList(from, to));
            }
            if (provider.equals("gitlab") && request.path().equals(base + "/tree")) {
                int index = index(params.get("ref"));
                if (index < 0) return new HttpFixture.Reply(400, "invalid tree ref");
                return ok(List.of(Map.of("path", "app.txt", "type", "blob", "mode", "100644", "id", blob(index))));
            }
            String prefix = base + "/commits/";
            if (request.path().startsWith(prefix)) {
                String suffix = request.path().substring(prefix.length());
                boolean diffRequest = suffix.endsWith("/diff");
                String commitSha = diffRequest ? suffix.substring(0, suffix.length() - 5) : suffix;
                int index = index(commitSha);
                if (index < 0) return new HttpFixture.Reply(400, "invalid detail ref");
                String patch = index == 0 ? "@@ -0,0 +1 @@\n+line-0"
                        : "@@ -1 +1 @@\n-line-" + (index - 1) + "\n+line-" + index;
                if (provider.equals("github") && !diffRequest) {
                    return ok(Map.of("sha", commitSha, "stats", Map.of("additions", 1, "deletions", index == 0 ? 0 : 1),
                            "files", List.of(Map.of("filename", "app.txt", "status", index == 0 ? "added" : "modified", "sha", blob(index),
                                    "additions", 1, "deletions", index == 0 ? 0 : 1, "patch", patch))));
                }
                if (provider.equals("gitlab") && !diffRequest) {
                    return ok(Map.of("id", commitSha, "stats", Map.of("additions", 1, "deletions", index == 0 ? 0 : 1)));
                }
                if (provider.equals("gitlab")) {
                    return ok(List.of(Map.of("old_path", "app.txt", "new_path", "app.txt", "diff", patch,
                            "new_file", index == 0, "deleted_file", false, "renamed_file", false)));
                }
            }
            return new HttpFixture.Reply(404, "unexpected load fixture route");
        };
    }

    private static Map<String, Object> commit(String provider, int index) {
        List<String> parents = index == 0 ? List.of() : List.of(sha(index - 1));
        return provider.equals("github")
                ? Map.of("sha", sha(index), "parents", parents.stream().map(parent -> Map.of("sha", parent)).toList(),
                        "commit", Map.of("message", "Synthetic load commit " + index))
                : Map.of("id", sha(index), "parent_ids", parents, "message", "Synthetic load commit " + index);
    }
    private HttpFixture.Reply ok(Object value) { return new HttpFixture.Reply(200, json.writeValueAsString(value)); }
    private static String sha(int index) { return String.format("%040x", index + 1); }
    private static String blob(int index) { return String.format("%040x", HISTORY_SIZE + index + 1); }
    private static int index(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{40}")) return -1;
        try {
            int value = new BigInteger(sha, 16).intValueExact() - 1;
            return value >= 0 && value < HISTORY_SIZE ? value : -1;
        } catch (ArithmeticException exception) { return -1; }
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) return result;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) result.put(pair[0], pair[1]);
        }
        return result;
    }
    private static String category(HttpFixture.Request request) {
        if (request.path().endsWith("/commits")) return "history";
        if (request.path().endsWith("/diff")) return "diffs";
        if (request.path().endsWith("/tree")) return "trees";
        if (request.path().contains("/commits/")) return "details";
        return "other";
    }
    private static Map<String, Long> counts(List<HttpFixture.Request> requests) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String kind : List.of("history", "details", "trees", "diffs", "other")) result.put(kind, 0L);
        requests.forEach(request -> result.compute(category(request), (key, value) -> value + 1));
        return result;
    }
    private void writeReport() throws Exception {
        Files.createDirectories(Path.of("target"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("fixtureTopology", "LINEAR");
        report.put("historyCommitsPerProvider", HISTORY_SIZE);
        report.put("batchLimit", BATCH_SIZE);
        report.put("externalServicesUsed", false);
        report.put("processMemoryMeasured", false);
        report.put("phases", phases);
        Files.writeString(Path.of("target/git-load-smoke-result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
