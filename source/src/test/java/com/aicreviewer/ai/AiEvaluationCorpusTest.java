package com.aicreviewer.ai;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationCorpusTest {
    @Test
    void corpusHasUniqueIdsAndBalancedSafeDefectiveAndAdversarialCases() {
        var cases = AiEvaluationCorpus.cases();
        assertThat(new HashSet<>(cases.stream().map(AiEvaluationCorpus.Case::id).toList())).hasSize(cases.size());
        assertThat(cases.stream().filter(AiEvaluationCorpus.Case::expectedFinding)).hasSize(3);
        assertThat(cases.stream().filter(c -> !c.expectedFinding())).hasSize(3);
        assertThat(cases.stream().filter(c -> c.id().startsWith("injection-"))).hasSize(2);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.expectedDefect()).isNotBlank();
            assertThat(c.commit().diff()).startsWith("diff --git ").contains("@@ -0,0 +1,");
        });
    }
}
