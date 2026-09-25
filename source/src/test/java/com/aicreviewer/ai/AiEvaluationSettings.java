package com.aicreviewer.ai;

import java.util.Map;

/** Test-only reproducible numeric settings; never retains provider URLs or credentials. */
record AiEvaluationSettings(int contextTokens, int maxOutputTokens, int timeoutSeconds) {
    AiEvaluationSettings {
        if (contextTokens < 2048 || contextTokens > 1048576) {
            throw new IllegalArgumentException("AI_EVAL_CONTEXT_TOKENS must be between 2048 and 1048576");
        }
        if (maxOutputTokens < 256 || maxOutputTokens >= contextTokens) {
            throw new IllegalArgumentException("AI_EVAL_MAX_OUTPUT_TOKENS must be at least 256 and smaller than context tokens");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 1800) {
            throw new IllegalArgumentException("AI_EVAL_TIMEOUT_SECONDS must be between 1 and 1800");
        }
    }

    static AiEvaluationSettings fromEnvironment(Map<String, String> environment) {
        return new AiEvaluationSettings(number(environment, "AI_EVAL_CONTEXT_TOKENS", 32768),
                number(environment, "AI_EVAL_MAX_OUTPUT_TOKENS", 4096), number(environment, "AI_EVAL_TIMEOUT_SECONDS", 120));
    }

    Map<String, Object> reportFields() {
        return Map.of("contextTokens", contextTokens, "maxOutputTokens", maxOutputTokens, "timeoutSeconds", timeoutSeconds);
    }

    private static int number(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        if (value == null) return defaultValue;
        try {
            if (!value.matches("[0-9]{1,10}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            // Invalid values can be secrets pasted into the wrong variable; never echo them.
            throw new IllegalArgumentException(name + " must be an unsigned decimal integer");
        }
    }
}
