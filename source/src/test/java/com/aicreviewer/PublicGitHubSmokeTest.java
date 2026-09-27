package com.aicreviewer;

import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.ManualReviewFile;
import com.aicreviewer.git.RepositoryUrl;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in read-only check; uses a public sample, no token and no AI calls. */
@EnabledIfEnvironmentVariable(named = "RUN_GITHUB_SMOKE", matches = "true")
class PublicGitHubSmokeTest {
    private static final String MEDIA_ROOT = "af5fe61b38fb7c2343360d074a714f226d4cc029";
    private static final String MEDIA_NEXT = "86a78d8d370a490aa8b5b87d31c626884114103b";

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
        Set<String> reviewed = commits.stream().map(commit -> commit.sha()).collect(java.util.stream.Collectors.toSet());
        var finalized = client.batch(repository, null, null, reviewed, 1);
        assertThat(finalized.commits()).isEmpty();
        assertThat(finalized.checkpointSha()).isEqualTo(commits.getLast().sha());
        var resumed = client.batch(repository, null, finalized.checkpointSha(), reviewed, 1);
        assertThat(resumed.commits()).isEmpty();
        assertThat(resumed.checkpointSha()).isNull();
    }

    @Test
    @Timeout(180)
    void provesBinaryRootAndNextCommitAsManualWorkWithoutStoppingProgress() {
        // Archived GitHub-owned fixture. Pin the two-commit history; never fetch image/blob contents.
        GitRepositoryClient client = new GitRepositoryClient(Set.of("github.com"), "https://api.github.com", "",
                "github.com", "", 20, 2, 262144, 2097152, 90);
        RepositoryUrl repository = RepositoryUrl.parse("https://github.com/github/media.git", Set.of("github.com"));

        var first = client.batch(repository, MEDIA_NEXT, null, Set.of(), 1);
        assertThat(first.commits()).hasSize(1);
        var root = first.commits().getFirst();
        assertThat(root.sha()).isEqualTo(MEDIA_ROOT);
        assertThat(root.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(root.diff()).isEmpty();
        assertThat(root.coverageDetails()).contains("AI 본문 검토 없음");
        assertThat(root.manualFiles()).containsExactly(new ManualReviewFile("octocat.png", null,
                "582c451dac8f2077606eafe2d5170b1a65b55e33", null, "100644", "SOURCE_DIFF_UNAVAILABLE"));
        assertThat(first.checkpointSha()).isEqualTo(MEDIA_ROOT);

        var next = client.batch(repository, MEDIA_NEXT, first.checkpointSha(), Set.of(root.sha()), 1);
        assertThat(next.commits()).hasSize(1);
        var second = next.commits().getFirst();
        assertThat(second.sha()).isEqualTo(MEDIA_NEXT);
        assertThat(second.coverageType()).isEqualTo("MANUAL_ONLY");
        assertThat(second.diff()).isEmpty();
        assertThat(second.manualFiles()).containsExactly(new ManualReviewFile("octocat_gems.png", null,
                "7a72d19fa581452eafd9c3eb93a99d0f30255ddb", null, "100644", "SOURCE_DIFF_UNAVAILABLE"));
        assertThat(next.checkpointSha()).isEqualTo(MEDIA_NEXT);

        var resumed = client.batch(repository, MEDIA_NEXT, next.checkpointSha(), Set.of(root.sha(), second.sha()), 1);
        assertThat(resumed.commits()).isEmpty();
        assertThat(resumed.checkpointSha()).isNull();
    }
}
