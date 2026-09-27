package com.aicreviewer.ai;

import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.IntegrationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic-only runner with injectable client creation for no-network regression tests. */
final class AiEvaluationRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @FunctionalInterface interface Reviewer { ReviewResult review(GitCommit commit); }

    static Map<String, Object> run(Map<String, String> environment,
            Function<AiEvaluationConfiguration, Reviewer> factory, Path destination) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("generatedAt", Instant.now().toString());
        report.put("status", "VALIDATING");
        report.put("qualityApproval", "NOT_ASSESSED");
        report.put("humanSemanticReview", "PENDING");
        report.put("humanSemanticReviewRequired", true);
        report.put("completedCases", 0);
        report.put("automaticPassedCases", 0);
        report.put("cases", results);
        write(destination, report);
        AiEvaluationConfiguration configuration;
        try { configuration = AiEvaluationConfiguration.from(environment); }
        catch (RuntimeException failure) {
            report.put("status", "CONFIGURATION_FAILED");
            report.put("failureCategory", "INVALID_CONFIGURATION");
            write(destination, report);
            throw new IllegalArgumentException("Evaluation configuration rejected; no model request was sent");
        }
        report.put("provider", configuration.provider());
        report.put("model", configuration.model());
        report.putAll(configuration.settings().reportFields());
        report.put("requestedCases", configuration.cases().size());
        report.put("maxCases", configuration.maxCases());
        report.put("maxClientHttpRequests", configuration.cases().size());
        report.put("maxClientOutputTokens", (long) configuration.cases().size() * configuration.settings().maxOutputTokens());
        report.put("maxRequestBudgetSeconds", (long) configuration.cases().size() * configuration.settings().timeoutSeconds());
        report.put("clientRetries", 0);
        report.put("budgetScope", "Client request/output limits only; proxy retries, provider usage and actual price are not bounded by this report.");
        report.put("promptFingerprint", "NOT_RECORDED");
        report.put("corpusFingerprint", hash(JSON.writeValueAsString(AiEvaluationCorpus.cases())));
        report.put("plannedCases", configuration.cases().stream().map(sample -> Map.of(
                "caseId", sample.id(), "diffSHA256", hash(sample.commit().diff()), "expectedFinding", sample.expectedFinding(),
                "expectedDefect", sample.expectedDefect(), "expectedFile", sample.expectedFile(),
                "allowedLines", sample.allowedLines(), "semanticChecks", sample.semanticChecks())).toList());
        if (configuration.dryRun()) {
            report.put("status", "PREFLIGHT");
            write(destination, report);
            return report;
        }
        Reviewer reviewer;
        try { reviewer = factory.apply(configuration); }
        catch (RuntimeException failure) {
            report.put("status", "CONFIGURATION_FAILED");
            report.put("failureCategory", "CLIENT_CONFIGURATION");
            write(destination, report);
            throw new IllegalArgumentException("Evaluation client configuration rejected; no model request was sent");
        }
        report.put("status", "RUNNING");
        write(destination, report);
        int passed = 0;
        for (var sample : configuration.cases()) {
            if (Thread.currentThread().isInterrupted()) break;
            long start = System.nanoTime();
            var row = new LinkedHashMap<String, Object>();
            row.put("caseId", sample.id());
            row.put("humanSemanticReview", "PENDING");
            try {
                ReviewResult result = reviewer.review(sample.commit());
                configuration.requireReportSafe(result);
                var assessment = AiEvaluationAssessment.assess(sample, result);
                row.put("outcome", "REVIEWED");
                row.put("automaticChecks", assessment);
                row.put("review", result);
                if (assessment.automaticStatus().equals("PRECHECK_PASSED")) passed++;
            } catch (RuntimeException failure) {
                row.put("outcome", "REQUEST_FAILED");
                row.put("failureCategory", failureCategory(failure));
            }
            row.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(row);
            report.put("completedCases", results.size());
            report.put("automaticPassedCases", passed);
            write(destination, report);
        }
        boolean completed = results.size() == configuration.cases().size() && !Thread.currentThread().isInterrupted();
        report.put("status", completed ? "COMPLETED" : "INTERRUPTED");
        report.put("automaticGate", completed && passed == configuration.cases().size() ? "PRECHECK_PASSED" : "PRECHECK_FAILED");
        write(destination, report);
        if (configuration.enforce() && (!completed || passed != configuration.cases().size()))
            throw new AssertionError("Automatic evaluation prechecks failed; human semantic review is separately required");
        return report;
    }
    private static String failureCategory(RuntimeException failure) {
        if (failure instanceof AiInputLimitException) return "INPUT_LIMIT";
        if (failure instanceof IntegrationException) {
            String message = failure.getMessage();
            if ("AI request timed out".equals(message) || "AI operation exceeded its time budget".equals(message)) return "TIMEOUT";
            if ("AI request interrupted".equals(message)) return "INTERRUPTED";
            if (message != null && message.matches("AI returned HTTP [0-9]{3}")) return "HTTP_ERROR";
            return "INTEGRATION_FAILURE";
        }
        return "UNEXPECTED_CLIENT_FAILURE";
    }
    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void write(Path destination, Map<String, Object> report) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.writeString(destination, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
