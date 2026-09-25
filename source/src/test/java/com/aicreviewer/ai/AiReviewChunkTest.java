package com.aicreviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.HttpFixture;
import com.aicreviewer.git.IntegrationException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only loopback HTTP fixtures: no environment credentials, external models or paid requests. */
class AiReviewChunkTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpFixture server;

    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private AiReviewClient client(String provider, int calls, int timeout) {
        return client(provider, calls, timeout, 262144, 8192);
    }

    private AiReviewClient client(String provider, int calls, int timeout, int maxDiff, int context) {
        if (provider.equals("openai")) {
            return AiReviewClient.openAiFixture(URI.create(server.url() + "/v1/responses"), "fixture-model",
                    "openai-fixture-key", timeout, maxDiff, 1048576, context, 512, calls);
        }
        return new AiReviewClient(provider, server.url(), "fixture-model", "fixture-key", timeout,
                maxDiff, 1048576, context, 512, "", "", calls);
    }

    private static GitCommit commit(String diff) { return new GitCommit("a".repeat(40), null, "검토 대상 변경", diff); }
    private static String file(String path, int bodyLength) {
        return "diff --git a/" + path + " b/" + path + "\nindex 111..222 100644\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -1 +1 @@\n-old\n+" + "x".repeat(bodyLength) + "\n";
    }
    private JsonNode supplied(HttpFixture.Request request, String provider) {
        JsonNode payload = json.readTree(request.body());
        return json.readTree(payload.path(provider.equals("openai") ? "input" : "messages").get(1).path("content").asText());
    }
    private List<String> sentDiffs(String provider) {
        return server.requests.stream().map(request -> supplied(request, provider).path("diff").asText()).toList();
    }
    private Map<String, Object> finding(String path, int line, String title) {
        return Map.of("severity", "HIGH", "title", title, "filePath", path, "lineNumber", line,
                "description", "실제 변경에 대한 설명", "suggestion", "수정 권고");
    }
    private HttpFixture.Reply response(String provider, String summary, List<?> findings) {
        String content = json.writeValueAsString(Map.of("summary", summary, "findings", findings));
        Object envelope;
        if (provider.equals("ollama")) envelope = Map.of("done", true, "done_reason", "stop", "message", Map.of("content", content));
        else if (provider.equals("litellm")) envelope = Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))));
        else envelope = Map.of("object", "response", "status", "completed", "output", List.of(Map.of("type", "message",
                "role", "assistant", "status", "completed", "content", List.of(Map.of("type", "output_text", "text", content, "annotations", List.of())))));
        return new HttpFixture.Reply(200, json.writeValueAsString(envelope));
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void reviewsEveryCompleteFileInOrderAndDisclosesLimitedCrossFileContext(String provider) {
        List<String> files = List.of(file("src/A.java", 3000), file("src/B.java", 3000), file("src/C.java", 3000));
        AtomicInteger calls = new AtomicInteger();
        server.handler = request -> {
            int index = calls.getAndIncrement();
            return response(provider, "묶음 " + (index + 1), List.of(finding("src/" + (char) ('A' + index) + ".java", 1, "수정 " + index)));
        };
        GitCommit commit = commit(String.join("", files));
        ReviewResult result = client(provider, 8, 5).review(commit);
        assertThat(sentDiffs(provider)).containsExactlyElementsOf(files);
        assertThat(String.join("", sentDiffs(provider))).isEqualTo(commit.diff());
        assertThat(result.findings()).extracting(ReviewFinding::filePath).containsExactly("src/A.java", "src/B.java", "src/C.java");
        assertThat(result.summary()).contains("3회 분할", "파일 간 상호작용", "제한", "[1/3] 묶음 1", "[3/3] 묶음 3");
        for (HttpFixture.Request request : server.requests) {
            JsonNode payload = json.readTree(request.body());
            JsonNode messages = payload.path(provider.equals("openai") ? "input" : "messages");
            assertThat(messages.get(0).path("content").asText()).contains("UNTRUSTED DATA", "not the entire commit");
            assertThat(supplied(request, provider).path("commitSha").asText()).isEqualTo(commit.sha());
            assertThat(supplied(request, provider).path("commitMessage").asText()).isEqualTo(commit.message());
            assertThat(request.body()).doesNotContain("fixture-key");
            assertThat(request.header("Authorization")).isEqualTo(provider.equals("openai") ? "Bearer openai-fixture-key" : "Bearer fixture-key");
            if (provider.equals("openai")) {
                assertThat(payload.path("store").asBoolean()).isFalse();
                assertThat(payload.path("truncation").asText()).isEqualTo("disabled");
                assertThat(payload.path("tools").isEmpty()).isTrue();
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void preservesTheSingleCallSummaryWhenTheWholeInputFits(String provider) {
        String diff = file("a.txt", 20) + file("b.txt", 20);
        server.handler = request -> response(provider, "원래 요약", List.of());
        ReviewResult result = client(provider, 1, 5).review(commit(diff));
        assertThat(result.summary()).isEqualTo("원래 요약");
        assertThat(sentDiffs(provider)).containsExactly(diff);
        String prompt = json.readTree(server.requests.getFirst().body()).path(provider.equals("openai") ? "input" : "messages")
                .get(0).path("content").asText();
        assertThat(prompt).doesNotContain("not the entire commit");
    }

    @Test void packsAdjacentSmallFilesTogetherWithinTheConfiguredCallLimit() {
        List<String> files = new ArrayList<>();
        for (int index = 0; index < 9; index++) files.add(file("file-" + index + ".txt", 900));
        server.handler = request -> response("ollama", "검토", List.of());
        client("ollama", 4, 5).review(commit(String.join("", files)));
        assertThat(server.requests.size()).isBetween(2, 4);
        assertThat(String.join("", sentDiffs("ollama"))).isEqualTo(String.join("", files));
        assertThat(sentDiffs("ollama").stream().anyMatch(part -> part.indexOf("diff --git ", 1) >= 0)).isTrue();
        for (String file : files) assertThat(sentDiffs("ollama").stream().filter(part -> part.contains(file)).count()).isEqualTo(1);
    }

    @Test void preservesAllMetadataUnicodeEscapingCrLfAndHeaderLookingSourceWithoutTruncation() {
        String first = file("한글 A.txt", 0).replace("+\n", "+" + "한\\\"".repeat(250) + "\n").replace("\n", "\r\n");
        String metadata = "diff --git a/old.txt b/new.txt\nsimilarity index 100%\nrename from old.txt\nrename to new.txt\nold mode 100644\nnew mode 100755\n";
        // Providers may retain a trailing patch newline in addition to the canonical file separator.
        String second = file("B.txt", 3000).replace("+xxx", "+diff --git a/fake b/fake xxx") + "\n";
        String third = file("C.txt", 3000).stripTrailing();
        String diff = first + metadata + second + third;
        server.handler = request -> response("ollama", "검토", List.of());
        client("ollama", 8, 5).review(commit(diff));
        assertThat(server.requests.size()).isGreaterThan(1);
        assertThat(String.join("", sentDiffs("ollama"))).isEqualTo(diff);
        assertThat(sentDiffs("ollama")).allSatisfy(part -> assertThat(part).startsWith("diff --git a/"));
        assertThat(sentDiffs("ollama").stream().filter(part -> part.contains("rename from old.txt")).count()).isEqualTo(1);
        assertThat(sentDiffs("ollama").stream().filter(part -> part.contains("+diff --git a/fake")).count()).isEqualTo(1);
        assertThat(sentDiffs("ollama").getLast()).endsWith("x");
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void detectsAnOversizedLaterFileBeforeMakingAnyRequest(String provider) {
        assertThatThrownBy(() -> client(provider, 8, 5).review(commit(file("small.txt", 100) + file("large.txt", 9000))))
                .isInstanceOfSatisfying(AiInputLimitException.class, error -> assertThat(error.reason()).isEqualTo(AiInputLimitException.Reason.FILE_CONTEXT_BUDGET))
                .hasCause(null).hasMessageNotContaining("large.txt");
        assertThat(server.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void checksTheWholeCallPlanBeforeMakingAnyRequest(String provider) {
        assertThatThrownBy(() -> client(provider, 2, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000) + file("c.txt", 3000))))
                .isInstanceOfSatisfying(AiInputLimitException.class, error -> assertThat(error.reason()).isEqualTo(AiInputLimitException.Reason.MAX_REVIEW_CALLS));
        assertThat(server.requests).isEmpty();
    }

    @Test void classifiesOnlyProvenInputLimitsAndIncludesCommonMetadataAndJsonEscapingInTheBudget() {
        assertThatThrownBy(() -> client("ollama", 8, 5, 1024, 8192).review(commit(file("A.txt", 1024))))
                .isInstanceOfSatisfying(AiInputLimitException.class, error -> assertThat(error.reason()).isEqualTo(AiInputLimitException.Reason.TOTAL_DIFF_BYTES));
        GitCommit longMessage = new GitCommit("a".repeat(40), null, "m".repeat(6000), file("a.txt", 10));
        assertThatThrownBy(() -> client("ollama", 8, 5).review(longMessage))
                .isInstanceOfSatisfying(AiInputLimitException.class, error -> assertThat(error.reason()).isEqualTo(AiInputLimitException.Reason.COMMIT_CONTEXT_BUDGET));
        String escaped = file("a.txt", 0).replace("+\n", "+" + "\\".repeat(3000) + "\n");
        assertThat(escaped.getBytes(StandardCharsets.UTF_8).length).isLessThan(4000);
        assertThatThrownBy(() -> client("ollama", 8, 5).review(commit(escaped)))
                .isInstanceOfSatisfying(AiInputLimitException.class, error -> assertThat(error.reason()).isEqualTo(AiInputLimitException.Reason.FILE_CONTEXT_BUDGET));
        assertThat(server.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"preamble", "duplicate", "quoted", "traversal", "unfinished", "huge-line", "malformed-header"})
    void rejectsAmbiguousOrIncompleteFileBoundariesBeforeAnyRequestWithoutManualLimitClassification(String variant) {
        String valid = file("a.txt", 3000) + file("b.txt", 3000);
        String diff = switch (variant) {
            case "preamble" -> "Untrusted preamble\n" + valid;
            case "duplicate" -> file("a.txt", 3000) + file("a.txt", 3000);
            case "quoted" -> valid.replace("diff --git a/b.txt b/b.txt", "diff --git \"a/b.txt\" \"b/b.txt\"");
            case "traversal" -> valid.replace("b.txt", "../b.txt");
            case "unfinished" -> valid.replace("@@ -1 +1 @@", "@@ -1,2 +1,2 @@");
            case "huge-line" -> valid.replace("@@ -1 +1 @@", "@@ -1 +999999999999999999999 @@");
            default -> valid.replace("diff --git a/b.txt b/b.txt", "diff --git broken");
        };
        assertThatThrownBy(() -> client("ollama", 8, 5).review(commit(diff))).isExactlyInstanceOf(IntegrationException.class)
                .hasMessageContaining("boundaries").hasMessageNotContaining("Untrusted").hasCause(null);
        assertThat(server.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void validatesEachFindingAgainstOnlyItsOwnFileGroup(String provider) {
        server.handler = request -> response(provider, "검토", List.of(finding("b.txt", 1, "다른 묶음")));
        assertThatThrownBy(() -> client(provider, 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessageContaining("outside the commit diff");
        assertThat(server.requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void neverReturnsAnEarlierSuccessfulGroupIfTheFinalRequestFails(String provider) {
        server.handler = request -> server.requests.size() == 1 ? response(provider, "첫 묶음 성공", List.of(finding("a.txt", 1, "검토")))
                : new HttpFixture.Reply(503, "PRIVATE RESPONSE fixture-key");
        assertThatThrownBy(() -> client(provider, 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessage("AI returned HTTP 503").hasCause(null);
        assertThat(server.requests).hasSize(2);
    }

    @Test void finalSchemaFailureRemainsAnErrorAndDoesNotBecomeAnInputLimit() {
        server.handler = request -> server.requests.size() == 1 ? response("ollama", "첫 묶음 성공", List.of())
                : response("ollama", "마지막 묶음", List.of(finding("b.txt", 999999, "잘못된 행")));
        assertThatThrownBy(() -> client("ollama", 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessageContaining("visible new-file");
        assertThat(server.requests).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void finalTruncationOrRefusalNeverBecomesAManualInputLimit(String provider) {
        server.handler = request -> {
            if (server.requests.size() == 1) return response(provider, "첫 묶음 성공", List.of());
            Object rejected = switch (provider) {
                case "ollama" -> Map.of("done", true, "done_reason", "length", "message", Map.of("content", "PRIVATE RESPONSE"));
                case "litellm" -> Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "PRIVATE RESPONSE", "refusal", "PRIVATE RESPONSE"))));
                default -> Map.of("object", "response", "status", "incomplete", "incomplete_details", Map.of("reason", "PRIVATE RESPONSE"));
            };
            return new HttpFixture.Reply(200, json.writeValueAsString(rejected));
        };
        assertThatThrownBy(() -> client(provider, 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessageNotContaining("PRIVATE RESPONSE").hasCause(null);
        assertThat(server.requests).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void sharesOneDeadlineAcrossEveryRequestInsteadOfResettingTheTimeout(String provider) {
        server.handler = request -> {
            HttpFixture.Reply success = response(provider, "검토", List.of());
            return new HttpFixture.Reply(200, success.body(), Map.of(), 0, 650);
        };
        assertThatThrownBy(() -> client(provider, 8, 1).review(commit(file("a.txt", 3000) + file("b.txt", 3000) + file("c.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessageContaining("timed out");
        assertThat(server.requests).hasSize(2);
    }

    @Test void rejectsCombinedFindingOverflowWithoutTruncatingOrDeduplicatingTheResults() {
        server.handler = request -> {
            String path = server.requests.size() == 1 ? "a.txt" : "b.txt";
            List<Object> findings = new ArrayList<>();
            for (int index = 0; index < 51; index++) findings.add(finding(path, 1, "각기 다른 수정 " + index));
            return response("ollama", "검토", findings);
        };
        assertThatThrownBy(() -> client("ollama", 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessage("Combined AI findings exceed 100 entries");
        assertThat(server.requests).hasSize(2);
    }

    @Test void acceptsExactlyOneHundredCombinedFindings() {
        server.handler = request -> {
            String path = server.requests.size() == 1 ? "a.txt" : "b.txt";
            List<Object> findings = new ArrayList<>();
            for (int index = 0; index < 50; index++) findings.add(finding(path, 1, "수정 " + index));
            return response("ollama", "검토", findings);
        };
        assertThat(client("ollama", 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000))).findings()).hasSize(100);
    }

    @Test void includesDisclosureAndGroupHeadingsInTheCombinedSummaryLimit() {
        server.handler = request -> response("ollama", "s".repeat(8000), List.of());
        assertThatThrownBy(() -> client("ollama", 8, 5).review(commit(file("a.txt", 3000) + file("b.txt", 3000)
                + file("c.txt", 3000) + file("d.txt", 3000))))
                .isExactlyInstanceOf(IntegrationException.class).hasMessage("Combined AI review summary exceeds 32000 characters");
        assertThat(server.requests).hasSize(4);
    }

    @Test void validatesConfigurationAndPreservesConstructorDefaultsAndSpringBinding() {
        for (int calls : List.of(0, -1, 33)) assertThatThrownBy(() -> client("ollama", calls, 5)).isInstanceOf(IllegalArgumentException.class);
        for (int calls : List.of(1, 32)) assertThat(ReflectionTestUtils.getField(client("ollama", calls, 5), "maxReviewCalls")).isEqualTo(calls);
        AiReviewClient existing = new AiReviewClient("ollama", server.url(), "fixture", "", 5, 262144, 1048576, 8192, 512);
        assertThat(ReflectionTestUtils.getField(existing, "maxReviewCalls")).isEqualTo(8);
        new ApplicationContextRunner().withBean(AiReviewClient.class)
                .withPropertyValues("app.ai.provider=ollama", "app.ai.base-url=" + server.url(), "app.ai.max-review-calls=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(ReflectionTestUtils.getField(context.getBean(AiReviewClient.class), "maxReviewCalls")).isEqualTo(3);
                });
        assertThat(server.requests).isEmpty();
    }
}
