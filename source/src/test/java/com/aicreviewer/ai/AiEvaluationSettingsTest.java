package com.aicreviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiEvaluationSettingsTest {
    @Test void defaultsMatchTheExistingEvaluationConfiguration() {
        AiEvaluationSettings settings = AiEvaluationSettings.fromEnvironment(Map.of());
        assertThat(settings).isEqualTo(new AiEvaluationSettings(32768, 4096, 120));
        assertThat(settings.reportFields()).containsExactlyInAnyOrderEntriesOf(
                Map.of("contextTokens", 32768, "maxOutputTokens", 4096, "timeoutSeconds", 120));
    }

    @Test void smallerContextAndExplicitTimeoutAreAcceptedByTheUnchangedClientConstructor() {
        AiEvaluationSettings settings = AiEvaluationSettings.fromEnvironment(Map.of(
                "AI_EVAL_CONTEXT_TOKENS", "8192", "AI_EVAL_MAX_OUTPUT_TOKENS", "1024", "AI_EVAL_TIMEOUT_SECONDS", "60",
                "AI_EVAL_API_KEY", "not-part-of-numeric-settings", "AI_EVAL_BASE_URL", "http://secret.invalid"));
        assertThat(settings).isEqualTo(new AiEvaluationSettings(8192, 1024, 60));
        assertThat(settings.reportFields()).doesNotContainKeys("AI_EVAL_API_KEY", "AI_EVAL_BASE_URL");
        assertThatCode(() -> new AiReviewClient("ollama", "http://127.0.0.1:11434", "fixture", "",
                settings.timeoutSeconds(), 262144, 1048576, settings.contextTokens(), settings.maxOutputTokens())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", "8192 ", "+8192", "-1", "1.5", "2147483648", "fixture-secret\r\n" })
    void malformedNumericValuesFailWithoutEchoingTheInput(String value) {
        assertThatThrownBy(() -> AiEvaluationSettings.fromEnvironment(Map.of("AI_EVAL_CONTEXT_TOKENS", value)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_EVAL_CONTEXT_TOKENS must be an unsigned decimal integer");
    }

    @Test void numericBoundsMatchClientAndHttpTransportRestrictions() {
        for (int context : new int[] { 2047, 1048577 }) {
            assertThatThrownBy(() -> new AiEvaluationSettings(context, 256, 120)).hasMessageContaining("CONTEXT_TOKENS");
        }
        for (int output : new int[] { 255, 8192, 8193 }) {
            assertThatThrownBy(() -> new AiEvaluationSettings(8192, output, 120)).hasMessageContaining("MAX_OUTPUT_TOKENS");
        }
        for (int timeout : new int[] { 0, 1801 }) {
            assertThatThrownBy(() -> new AiEvaluationSettings(8192, 1024, timeout)).hasMessageContaining("TIMEOUT_SECONDS");
        }
        assertThat(new AiEvaluationSettings(2048, 256, 1).contextTokens()).isEqualTo(2048);
        assertThat(new AiEvaluationSettings(1048576, 1048575, 1800).timeoutSeconds()).isEqualTo(1800);
    }
}
