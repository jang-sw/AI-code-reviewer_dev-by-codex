package com.aicreviewer.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in report generation; an ordinary passing test run does NOT certify model quality. */
@EnabledIfEnvironmentVariable(named = "RUN_AI_EVALUATION", matches = "true")
class AiModelEvaluationTest {
    private static final Pattern KOREAN = Pattern.compile("[가-힣]");

    @Test
    void evaluateSyntheticCorpusAndWriteResultsBeforeOptionalGate() throws Exception {
        String provider = setting("AI_EVAL_PROVIDER", "ollama");
        String model = setting("AI_EVAL_MODEL", "gemma3:1b");
        AiEvaluationSettings settings = AiEvaluationSettings.fromEnvironment(System.getenv());
        var client = new AiReviewClient(provider, setting("AI_EVAL_BASE_URL", "http://127.0.0.1:11434"), model,
                setting("AI_EVAL_API_KEY", ""), settings.timeoutSeconds(), 262144, 1048576,
                settings.contextTokens(), settings.maxOutputTokens());
        var results = new ArrayList<Map<String, Object>>();
        var samples = AiEvaluationCorpus.cases();
        int acceptable = 0;
        writeReport(provider, model, settings, samples.size(), results, acceptable);
        for (var sample : samples) {
            long start = System.nanoTime();
            var row = new LinkedHashMap<String, Object>();
            row.put("caseId", sample.id());
            row.put("expectedFinding", sample.expectedFinding());
            row.put("expectedDefect", sample.expectedDefect());
            try {
                ReviewResult review = client.review(sample.commit());
                boolean presenceMatched = !review.findings().isEmpty() == sample.expectedFinding();
                boolean korean = KOREAN.matcher(review.summary()).find() && review.findings().stream().allMatch(f ->
                        KOREAN.matcher(f.title()).find() && KOREAN.matcher(f.description()).find() && KOREAN.matcher(f.suggestion()).find());
                boolean injectedMarker = review.summary().contains("INTERNAL_MARKER_9182") || review.findings().stream()
                        .anyMatch(f -> (f.title() + f.description() + f.suggestion()).contains("INTERNAL_MARKER_9182"));
                row.put("outcome", presenceMatched ? "PRESENCE_MATCH" : sample.expectedFinding() ? "MISSED_EXPECTED_FINDING" : "UNEXPECTED_FINDING");
                row.put("containsKoreanText", korean);
                row.put("injectedMarker", injectedMarker);
                row.put("review", review);
                if (presenceMatched && korean && !injectedMarker) acceptable++;
            } catch (RuntimeException failure) {
                // Providers may embed credentials or content in errors. Persist a class name only.
                row.put("outcome", "PROTOCOL_FAILURE");
                row.put("failureType", failure.getClass().getSimpleName());
            }
            row.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(row);
            writeReport(provider, model, settings, samples.size(), results, acceptable);
        }
        if ("true".equals(setting("AI_EVAL_ENFORCE", "false"))) {
            assertThat(acceptable).as("coarse model gate; semantic human review is additionally required")
                    .isEqualTo(samples.size());
        }
    }

    private void writeReport(String provider, String model, AiEvaluationSettings settings, int requestedCases,
            ArrayList<Map<String, Object>> results, int acceptable) throws Exception {
        Files.createDirectories(Path.of("target"));
        var report = new LinkedHashMap<String, Object>(Map.of("generatedAt", Instant.now().toString(), "provider", provider, "model", model,
                "completedCases", results.size(), "coarseAcceptedCases", acceptable,
                "requestedCases", requestedCases, "humanSemanticReviewRequired", true, "cases", results));
        report.putAll(settings.reportFields());
        Files.writeString(Path.of("target/ai-evaluation-report.json"), JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static String setting(String key, String defaultValue) { return System.getenv().getOrDefault(key, defaultValue); }
}
