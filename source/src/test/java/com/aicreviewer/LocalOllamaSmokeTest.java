package com.aicreviewer;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitCommit;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in local protocol check using synthetic source only; this does not certify review quality. */
@EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_SMOKE", matches = "true")
class LocalOllamaSmokeTest {
    @Test
    void installedGemmaReturnsSchemaValidReview() throws Exception {
        AiReviewClient client = new AiReviewClient("ollama", "http://127.0.0.1:11434", "gemma3:1b", "",
                120, 262144, 1048576, 32768, 4096);
        var result = client.review(new GitCommit("c".repeat(40), "test-author", "Avoid division by zero", """
                diff --git a/src/Ratio.java b/src/Ratio.java
                @@ -1,5 +1,6 @@
                 public class Ratio {
                     public double divide(double numerator, double denominator) {
                +        if (denominator == 0) throw new IllegalArgumentException("denominator");
                         return numerator / denominator;
                     }
                 }
                """));
        assertThat(result.summary()).isNotBlank();
        assertThat(result.findings()).isNotNull();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/ollama-smoke-result.json"), JsonMapper.builder().build().writeValueAsString(result));
    }
}
