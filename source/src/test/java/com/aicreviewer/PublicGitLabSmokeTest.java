package com.aicreviewer;

import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.RepositoryUrl;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in public GitLab-owned test fixture; immutable refs, no token, no AI and one root diff only. */
@EnabledIfEnvironmentVariable(named = "RUN_GITLAB_SMOKE", matches = "true")
class PublicGitLabSmokeTest {
    private static final String FIXTURE_HEAD = "ddd0f15ae83993f5cb66a927a28673882e99100b";
    private static final String ROOT = "1a0b36b3cdad1d2ee32457c102a8c0b7056fa863";

    @Test
    void readsPinnedPublicHistoryAndRootDiffThenFinalizesAndResumesWithoutNewDiffs() {
        GitRepositoryClient client = new GitRepositoryClient(Set.of("gitlab.com"), "https://api.github.com", "",
                "github.com", "", 20, 10, 262144, 2097152, 90);
        RepositoryUrl repository = RepositoryUrl.parse("https://gitlab.com/gitlab-org/gitlab-test.git", Set.of("gitlab.com"));
        var first = client.batch(repository, FIXTURE_HEAD, null, Set.of(), 1);
        assertThat(first.commits()).hasSize(1);
        assertThat(first.commits().getFirst().sha()).isEqualTo(ROOT);
        assertThat(first.commits().getFirst().coverageType()).isEqualTo("FULL");
        assertThat(first.commits().getFirst().diff()).contains("diff --git ", "@@ ");
        assertThat(first.checkpointSha()).isEqualTo(ROOT);

        var finalized = client.batch(repository, ROOT, null, Set.of(ROOT), 1);
        assertThat(finalized.commits()).isEmpty();
        assertThat(finalized.checkpointSha()).isEqualTo(ROOT);
        var resumed = client.batch(repository, ROOT, ROOT, Set.of(ROOT), 1);
        assertThat(resumed.commits()).isEmpty();
        assertThat(resumed.checkpointSha()).isNull();
    }
}
