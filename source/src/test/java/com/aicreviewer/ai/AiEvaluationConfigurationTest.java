package com.aicreviewer.ai;

import com.aicreviewer.git.HttpFixture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AiEvaluationConfigurationTest {
    private Map<String, String> lite() {
        return new HashMap<>(Map.of("AI_EVAL_PROVIDER", "litellm", "AI_EVAL_BASE_URL", "https://fixture.invalid/v1",
                "AI_EVAL_MODEL", "review-model", "AI_EVAL_API_KEY", "fixture-private-key"));
    }
    @Test void existingLocalDefaultsRemainAndConfigurationNeverPrintsSecrets() {
        var local = AiEvaluationConfiguration.from(Map.of());
        assertThat(local.provider()).isEqualTo("ollama");
        assertThat(local.model()).isEqualTo("gemma3:1b");
        assertThat(local.cases()).hasSize(6);
        var config = AiEvaluationConfiguration.from(lite());
        assertThat(config.toString()).doesNotContain("fixture.invalid", "fixture-private-key");
    }
    @Test void liteLlmRequiresAnExplicitEndpointAndModelWithoutEchoingInputs() {
        for (String name : List.of("AI_EVAL_BASE_URL", "AI_EVAL_MODEL")) {
            var environment = lite();
            environment.remove(name);
            assertThatThrownBy(() -> AiEvaluationConfiguration.from(environment)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(name).hasMessageNotContaining("fixture-private-key");
        }
    }
    @Test void nonLoopbackHttpAndEmbeddedCredentialsAreRejectedBeforeAnyClientExists() {
        for (String base : List.of("http://fixture.invalid", "http://127.0.0.1.evil.invalid", "https://user:secret@fixture.invalid",
                "https://fixture.invalid?key=secret", "https://fixture.invalid#secret")) {
            var environment = lite(); environment.put("AI_EVAL_BASE_URL", base);
            assertThatThrownBy(() -> AiEvaluationConfiguration.from(environment)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(base).hasMessageNotContaining("secret");
        }
        var noKey = lite(); noKey.put("AI_EVAL_API_KEY", ""); noKey.put("AI_EVAL_BASE_URL", "http://fixture.invalid/v1");
        assertThat(AiEvaluationConfiguration.from(noKey).provider()).isEqualTo("litellm");
    }
    @Test void selectionIsExplicitOrderedImmutableAndBounded() {
        var environment = lite();
        environment.put("AI_EVAL_CASE_IDS", "injection-with-real-defect, safe-zero-guard");
        environment.put("AI_EVAL_MAX_CASES", "2");
        var config = AiEvaluationConfiguration.from(environment);
        assertThat(config.cases()).extracting(AiEvaluationCorpus.Case::id).containsExactly("injection-with-real-defect", "safe-zero-guard");
        assertThatThrownBy(() -> config.cases().clear()).isInstanceOf(UnsupportedOperationException.class);
        environment.put("AI_EVAL_MAX_CASES", "1");
        assertThatThrownBy(() -> AiEvaluationConfiguration.from(environment)).hasMessageContaining("exceed");
    }
    @Test void invalidCaseFlagsAndModelValuesAreRejectedWithoutEcho() {
        for (var bad : List.of(Map.entry("AI_EVAL_CASE_IDS", ""), Map.entry("AI_EVAL_CASE_IDS", "safe-zero-guard,safe-zero-guard"),
                Map.entry("AI_EVAL_CASE_IDS", "unknown-private-value"), Map.entry("AI_EVAL_MAX_CASES", "7"),
                Map.entry("AI_EVAL_DRY_RUN", "TRUE"), Map.entry("AI_EVAL_ENFORCE", "yes"),
                Map.entry("AI_EVAL_PROVIDER", "openai"), Map.entry("AI_EVAL_MODEL", "sk-" + "A".repeat(48)),
                Map.entry("AI_EVAL_MODEL", "https://fixture.invalid/private"))) {
            var environment = lite(); environment.put(bad.getKey(), bad.getValue());
            var failure = catchThrowable(() -> AiEvaluationConfiguration.from(environment));
            assertThat(failure).isInstanceOf(IllegalArgumentException.class);
            if (!bad.getValue().isEmpty()) assertThat(failure.getMessage()).doesNotContain(bad.getValue());
        }
        var environment = lite(); environment.put("AI_EVAL_MODEL", "prefix-fixture-private-key-suffix");
        assertThatThrownBy(() -> AiEvaluationConfiguration.from(environment)).hasMessageNotContaining("fixture-private-key");
    }
    @Test void actualLiteLlmClientUsesFixturePathStrictSchemaAndOneCallBudget() throws Exception {
        try (var server = new HttpFixture()) {
            server.handler = request -> new HttpFixture.Reply(200, JsonMapper.builder().build().writeValueAsString(
                    Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                            Map.of("content", "{\"summary\":\"추가 결함 없음\",\"findings\":[]}"))))));
            var environment = lite(); environment.put("AI_EVAL_BASE_URL", server.url() + "/v1");
            var client = AiEvaluationConfiguration.from(environment).client();
            assertThat(client.review(AiEvaluationCorpus.cases().getFirst().commit()).findings()).isEmpty();
            assertThat(server.requests).hasSize(1);
            var request = server.requests.getFirst();
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-private-key");
            var payload = JsonMapper.builder().build().readTree(request.body());
            assertThat(payload.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
            assertThat(ReflectionTestUtils.getField(client, "maxReviewCalls")).isEqualTo(1);
        }
    }
}
