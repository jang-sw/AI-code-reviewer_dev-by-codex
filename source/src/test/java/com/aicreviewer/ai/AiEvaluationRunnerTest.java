package com.aicreviewer.ai;

import com.aicreviewer.git.IntegrationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiEvaluationRunnerTest {
    @TempDir Path directory;
    private Path report() { return directory.resolve("report.json"); }
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("AI_EVAL_PROVIDER", "litellm", "AI_EVAL_BASE_URL", "https://private-fixture.invalid/v1",
                "AI_EVAL_MODEL", "review-model", "AI_EVAL_API_KEY", "private-fixture-key",
                "AI_EVAL_CASE_IDS", "safe-zero-guard", "AI_EVAL_MAX_CASES", "1"));
    }
    @Test void preflightNeverConstructsAClientAndExposesOnlyPlanAndSafeBudgets() throws Exception {
        var environment = environment(); environment.put("AI_EVAL_DRY_RUN", "true");
        var result = AiEvaluationRunner.run(environment, ignored -> { throw new AssertionError("must not create client"); }, report());
        assertThat(result).containsEntry("status", "PREFLIGHT").containsEntry("completedCases", 0)
                .containsEntry("maxClientHttpRequests", 1).containsEntry("maxClientOutputTokens", 4096L)
                .containsEntry("maxRequestBudgetSeconds", 120L).containsEntry("clientRetries", 0)
                .containsEntry("qualityApproval", "NOT_ASSESSED").containsEntry("humanSemanticReview", "PENDING");
        assertThat(Files.readString(report())).doesNotContain("private-fixture-key", "private-fixture.invalid", "AI_EVAL_API_KEY");
        var second = AiEvaluationRunner.run(environment, ignored -> { throw new AssertionError(); }, directory.resolve("second.json"));
        assertThat(second.get("corpusFingerprint")).isEqualTo(result.get("corpusFingerprint"));
        assertThat(result.get("promptFingerprint")).isEqualTo("NOT_RECORDED");
    }
    @Test void allAutomaticChecksPassingStillLeaveHumanReviewAndQualityUnapproved() throws Exception {
        var calls = new AtomicInteger();
        var result = AiEvaluationRunner.run(environment(), ignored -> commit -> {
            calls.incrementAndGet();
            assertThat(commit.message()).isEqualTo("safe-zero-guard");
            assertThat(commit.diff()).contains("Ratio.java");
            return new ReviewResult("새 결함 없음", List.of());
        }, report());
        assertThat(calls).hasValue(1);
        assertThat(result).containsEntry("status", "COMPLETED").containsEntry("automaticPassedCases", 1)
                .containsEntry("automaticGate", "PRECHECK_PASSED").containsEntry("qualityApproval", "NOT_ASSESSED")
                .containsEntry("humanSemanticReview", "PENDING");
    }
    @Test void invalidConfigurationReplacesAnOldCompletedReportWithoutClientCreationOrSecrets() throws Exception {
        Files.writeString(report(), "old completed report");
        var environment = environment(); environment.put("AI_EVAL_MODEL", "https://secret-model.invalid/token");
        assertThatThrownBy(() -> AiEvaluationRunner.run(environment, ignored -> { throw new AssertionError(); }, report()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-model");
        assertThat(Files.readString(report())).contains("CONFIGURATION_FAILED", "INVALID_CONFIGURATION")
                .doesNotContain("old completed", "secret-model", "private-fixture-key", "private-fixture.invalid");
    }
    @Test void failuresAreClassifiedWithoutPersistingExceptionMessagesOrRetrying() throws Exception {
        for (var entry : Map.of("AI request timed out", "TIMEOUT", "AI returned HTTP 429", "HTTP_ERROR",
                "private-fixture-key https://private-fixture.invalid", "INTEGRATION_FAILURE").entrySet()) {
            var calls = new AtomicInteger();
            AiEvaluationRunner.run(environment(), ignored -> commit -> {
                calls.incrementAndGet(); throw new IntegrationException(entry.getKey());
            }, report());
            assertThat(calls).hasValue(1);
            var saved = JsonMapper.builder().build().readTree(Files.readString(report()));
            assertThat(saved.path("cases").get(0).path("failureCategory").asText()).isEqualTo(entry.getValue());
            assertThat(saved.path("automaticGate").asText()).isEqualTo("PRECHECK_FAILED");
            assertThat(Files.readString(report())).doesNotContain("private-fixture-key", "private-fixture.invalid", entry.getKey());
        }
    }
    @Test void enforceFailureWritesCompleteReportBeforeFailingTheTest() throws Exception {
        var environment = environment(); environment.put("AI_EVAL_ENFORCE", "true");
        assertThatThrownBy(() -> AiEvaluationRunner.run(environment, ignored -> commit -> new ReviewResult("English", List.of()), report()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("prechecks failed");
        assertThat(Files.readString(report())).contains("COMPLETED", "PRECHECK_FAILED", "NOT_ASSESSED");
    }
    @Test void interruptionPreservesCompletedCaseAndPreventsSubsequentRequests() throws Exception {
        var environment = environment(); environment.remove("AI_EVAL_CASE_IDS"); environment.put("AI_EVAL_MAX_CASES", "6");
        var calls = new AtomicInteger();
        try {
            var result = AiEvaluationRunner.run(environment, ignored -> commit -> {
                calls.incrementAndGet(); Thread.currentThread().interrupt(); throw new IntegrationException("AI request interrupted");
            }, report());
            assertThat(calls).hasValue(1);
            assertThat(result).containsEntry("status", "INTERRUPTED").containsEntry("completedCases", 1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void clientConstructionFailureIsSafeAndDoesNotPretendCasesCompleted() throws Exception {
        assertThatThrownBy(() -> AiEvaluationRunner.run(environment(), ignored -> {
            throw new IllegalArgumentException("private-fixture-key https://private-fixture.invalid");
        }, report())).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-fixture");
        var saved = Files.readString(report());
        assertThat(saved).contains("CLIENT_CONFIGURATION", "CONFIGURATION_FAILED").doesNotContain("private-fixture");
    }
    @Test void knownCredentialsAndEndpointEchoedByTheModelNeverReachTheReport() throws Exception {
        for (String sensitive : List.of("private-fixture-key", "https://private-fixture.invalid/v1")) {
            AiEvaluationRunner.run(environment(), ignored -> commit -> new ReviewResult("검토 " + sensitive, List.of()), report());
            assertThat(Files.readString(report())).contains("REQUEST_FAILED", "INTEGRATION_FAILURE").doesNotContain(sensitive);
        }
    }
}
