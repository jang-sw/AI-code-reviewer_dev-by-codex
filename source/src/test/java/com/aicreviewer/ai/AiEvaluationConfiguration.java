package com.aicreviewer.ai;

import com.aicreviewer.git.SafeHttpTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** No generated toString: configuration contains credentials that must never enter reports. */
final class AiEvaluationConfiguration {
    private final String provider;
    private final URI base;
    private final String model;
    private final String key;
    private final AiEvaluationSettings settings;
    private final List<AiEvaluationCorpus.Case> cases;
    private final boolean dryRun;
    private final boolean enforce;
    private final int maxCases;

    private AiEvaluationConfiguration(String provider, URI base, String model, String key,
            AiEvaluationSettings settings, List<AiEvaluationCorpus.Case> cases, boolean dryRun, boolean enforce, int maxCases) {
        this.provider = provider; this.base = base; this.model = model; this.key = key;
        this.settings = settings; this.cases = List.copyOf(cases); this.dryRun = dryRun; this.enforce = enforce; this.maxCases = maxCases;
    }
    static AiEvaluationConfiguration from(Map<String, String> environment) {
        String provider = environment.getOrDefault("AI_EVAL_PROVIDER", "ollama");
        if (!Set.of("ollama", "litellm").contains(provider)) throw new IllegalArgumentException("Evaluation provider must be ollama or litellm");
        URI base = SafeHttpTransport.baseUri(provider.equals("litellm") ? required(environment, "AI_EVAL_BASE_URL")
                : environment.getOrDefault("AI_EVAL_BASE_URL", "http://127.0.0.1:11434"));
        boolean loopback = Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(base.getHost().toLowerCase(java.util.Locale.ROOT));
        String model = provider.equals("litellm") ? required(environment, "AI_EVAL_MODEL")
                : environment.getOrDefault("AI_EVAL_MODEL", "gemma3:1b");
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}") || model.contains("://")
                || model.matches("sk-(?:(?:proj-|svcacct-))?[A-Za-z0-9_-]{32,}")) {
            throw new IllegalArgumentException("Evaluation model must be a non-secret model identifier");
        }
        String key = SafeHttpTransport.credential(environment.getOrDefault("AI_EVAL_API_KEY", ""));
        if (!"https".equals(base.getScheme()) && !loopback && !key.isBlank())
            throw new IllegalArgumentException("Authenticated non-loopback evaluation endpoints require HTTPS");
        if (!key.isBlank() && model.contains(key)) throw new IllegalArgumentException("Evaluation model must not contain credentials");
        String limit = environment.getOrDefault("AI_EVAL_MAX_CASES", "6");
        if (!limit.matches("[1-6]")) throw new IllegalArgumentException("AI_EVAL_MAX_CASES must be between 1 and 6");
        int maxCases = Integer.parseInt(limit);
        List<AiEvaluationCorpus.Case> corpus = AiEvaluationCorpus.cases();
        List<AiEvaluationCorpus.Case> selected = new ArrayList<>();
        if (!environment.containsKey("AI_EVAL_CASE_IDS")) selected.addAll(corpus);
        else {
            String requested = environment.get("AI_EVAL_CASE_IDS");
            if (requested == null || requested.length() > 1024) throw new IllegalArgumentException("Invalid evaluation case selection");
            var seen = new HashSet<String>();
            for (String part : requested.split(",", -1)) {
                String id = part.strip();
                if (!seen.add(id)) throw new IllegalArgumentException("Duplicate evaluation case selection");
                selected.add(corpus.stream().filter(sample -> sample.id().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown evaluation case selection")));
            }
        }
        if (selected.isEmpty() || selected.size() > maxCases) throw new IllegalArgumentException("Selected evaluation cases exceed the configured limit");
        return new AiEvaluationConfiguration(provider, base, model, key, AiEvaluationSettings.fromEnvironment(environment),
                selected, flag(environment, "AI_EVAL_DRY_RUN"), flag(environment, "AI_EVAL_ENFORCE"), maxCases);
    }
    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be explicit for LiteLLM evaluation");
        return value;
    }
    private static boolean flag(Map<String, String> environment, String name) {
        String value = environment.getOrDefault(name, "false");
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException(name + " must be true or false");
        return value.equals("true");
    }
    AiReviewClient client() {
        return new AiReviewClient(provider, base.toString(), model, key, settings.timeoutSeconds(), 262144, 1048576,
                settings.contextTokens(), settings.maxOutputTokens(), "", "", 1);
    }
    void requireReportSafe(ReviewResult result) {
        if (containsConnectionDetails(result.summary()) || result.findings().stream().anyMatch(finding ->
                containsConnectionDetails(finding.title()) || containsConnectionDetails(finding.description())
                        || containsConnectionDetails(finding.suggestion()) || containsConnectionDetails(finding.filePath()))) {
            throw new com.aicreviewer.git.IntegrationException("Evaluation response contained configured connection details");
        }
    }
    private boolean containsConnectionDetails(String value) {
        return value.contains(base.toString()) || (!key.isBlank() && value.contains(key));
    }
    String provider() { return provider; }
    String model() { return model; }
    AiEvaluationSettings settings() { return settings; }
    List<AiEvaluationCorpus.Case> cases() { return cases; }
    boolean dryRun() { return dryRun; }
    boolean enforce() { return enforce; }
    int maxCases() { return maxCases; }
}
