package com.aicreviewer.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GitCommitLinkTest {
    private static final String SHA = "a".repeat(40);

    @Test
    void githubUsesNormalizedCloneUrlAndImmutableCommitPath() {
        assertThat(GitCommitLink.from("https://GITHUB.com:443/Org/Repo.git/", SHA))
                .isEqualTo("https://github.com/org/repo/commit/" + SHA);
    }

    @Test
    void gitlabRetainsNestedNamespaceCaseAndNonDefaultPort() {
        assertThat(GitCommitLink.from("https://GIT.example.test:8443/Group/Subgroup/Repo.git", SHA))
                .isEqualTo("https://git.example.test:8443/Group/Subgroup/Repo/-/commit/" + SHA);
        assertThat(GitCommitLink.from("http://127.0.0.1:8080/group/repo", "b".repeat(64)))
                .isEqualTo("http://127.0.0.1:8080/group/repo/-/commit/" + "b".repeat(64));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "javascript:alert(1)", "//github.com/org/repo", "https://user:password@github.com/org/repo",
            "https://github.com/org/repo?next=bad", "https://github.com/org/repo#fragment", "https://github.com/org/../repo",
            "https://github.com/org/repo\"", "https://github.com/org/repo%22", "https://github.com/org/repo name",
            "https://github.com/org/repo%20name", "https://github.com/org/repo\n", "https://github.com/org", "file:///org/repo" })
    void unsafeOrUnsupportedRepositoryDataOmitsTheLink(String repositoryUrl) {
        assertThat(GitCommitLink.from(repositoryUrl, SHA)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "main", "../../issues", "<script>alert(1)</script>", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?x=1" })
    void onlyCompleteLowercaseCommitIdentifiersCanBecomeLinks(String sha) {
        assertThat(GitCommitLink.from("https://github.com/org/repo", sha)).isNull();
    }
}
