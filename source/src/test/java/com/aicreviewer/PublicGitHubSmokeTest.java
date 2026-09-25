package com.aicreviewer;

import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.RepositoryUrl;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in read-only check; uses a public sample, no token and no AI calls. */
@EnabledIfEnvironmentVariable(named = "RUN_GITHUB_SMOKE", matches = "true")
class PublicGitHubSmokeTest {
    @Test
    void readsCompletePublicHistoryAndResumesAtHead() {
        GitRepositoryClient client = new GitRepositoryClient(Set.of("github.com"), "https://api.github.com", "",
                "github.com", "", 20, 10, 262144, 2097152, 90);
        RepositoryUrl repository = RepositoryUrl.parse("https://github.com/octocat/Hello-World.git", Set.of("github.com"));
        var commits = client.commits(repository, null, null, 100);
        assertThat(commits).isNotEmpty();
        assertThat(commits.stream().map(commit -> commit.sha()).distinct().count()).isEqualTo(commits.size());
        assertThat(commits).allSatisfy(commit -> assertThat(commit.diff()).isNotNull());
        assertThat(client.commits(repository, null, commits.getLast().sha(), 100)).isEmpty();
    }
}
