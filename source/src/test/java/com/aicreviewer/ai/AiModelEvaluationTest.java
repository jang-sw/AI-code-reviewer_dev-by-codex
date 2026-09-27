package com.aicreviewer.ai;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in synthetic evaluation; report completion and prechecks never certify model quality. */
@EnabledIfEnvironmentVariable(named = "RUN_AI_EVALUATION", matches = "true")
class AiModelEvaluationTest {
    @Test
    void evaluateSyntheticCorpusAndWriteResultsBeforeOptionalGate() throws Exception {
        AiEvaluationRunner.run(System.getenv(), configuration -> configuration.client()::review,
                Path.of("target/ai-evaluation-report.json"));
    }
}
