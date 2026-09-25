package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryUrlTest {
    @Test void normalizesGithubCloneUrlAndNamespaceCase() {
        assertThat(RepositoryUrl.parse("https://GitHub.com:443/Example/Repo.git/", Set.of("github.com")))
                .isEqualTo(new RepositoryUrl("https://github.com/example/repo", "GITHUB", "github.com", "example/repo"));
    }

    @Test void explicitlyAllowedGitlabSupportsHttpPortsAndSubgroups() {
        assertThat(RepositoryUrl.parse("http://git.internal:8080/team/Subgroup/repo.git", Set.of("git.internal")))
                .isEqualTo(new RepositoryUrl("http://git.internal:8080/team/Subgroup/repo", "GITLAB", "git.internal", "team/Subgroup/repo"));
    }

    @Test void allowsDotGithubAndHyphenPrefixedRepositoryNames() {
        assertThat(RepositoryUrl.parse("https://github.com/example/.github", Set.of("github.com")).path()).isEqualTo("example/.github");
        assertThat(RepositoryUrl.parse("https://github.com/example/-repo", Set.of("github.com")).path()).isEqualTo("example/-repo");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/a/b", "ssh://github.com/a/b", "git@github.com:a/b.git", "https://github.com.evil.test/a/b",
            "https://u:secret@github.com/a/b", "https://github.com/a/b?token=secret", "https://github.com/a/b#readme",
            "https://github.com/a/b/tree/main", "https://github.com/a/../b", "https://github.com/a/%2E%2E", "https://github.com/a/%2fb",
            "https://github.com/a//b", "https://github.com/a", "https://github.com:444/a/b", " https://github.com/a/b",
            "https://github.com/a/b\n", "https://127.0.0.1/a/b", "https://github.com/a/", "https://github.com/a/b\\c"
    })
    void rejectsUnsafeOrNonCloneUrls(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryUrl.parse(value, Set.of("github.com")));
    }

    @Test void rejectsUnconfiguredSelfHostedHostAndInvalidPort() {
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryUrl.parse("http://git.internal/a/b", Set.of("github.com")));
        assertThatIllegalArgumentException().isThrownBy(() -> RepositoryUrl.parse("http://git.internal:65536/a/b", Set.of("git.internal")));
    }
}
