package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryOriginTest {
    private static final Set<String> HOSTS = Set.of("git.internal", "github.com", "127.0.0.1");

    @Test void canonicalizesSchemeHostDefaultPortsAndRootSlash() {
        assertThat(RepositoryOrigin.normalize("HTTPS://Git.Internal:443/", HOSTS)).isEqualTo("https://git.internal");
        assertThat(RepositoryOrigin.normalize("http://git.internal:80/", HOSTS)).isEqualTo("http://git.internal");
        assertThat(RepositoryOrigin.normalize("https://git.internal:8443/", HOSTS)).isEqualTo("https://git.internal:8443");
        assertThat(RepositoryOrigin.normalize("https://github.com:443", HOSTS)).isEqualTo("https://github.com");
        assertThat(RepositoryOrigin.normalize("http://127.0.0.1:8080", HOSTS)).isEqualTo("http://127.0.0.1:8080");
    }

    @Test void repositoryUrlAndOriginNormalizeIdentically() {
        RepositoryUrl repository = RepositoryUrl.parse("HTTPS://Git.Internal:443/team/sub/repo.git", HOSTS);
        assertThat(RepositoryOrigin.fromRepository(repository)).isEqualTo("https://git.internal");
        assertThat(RepositoryOrigin.fromRepositoryUrl(repository.normalizedUrl()))
                .isEqualTo(RepositoryOrigin.normalize("https://git.internal:443/", HOSTS));
        assertThat(RepositoryOrigin.fromRepositoryUrl("http://git.internal:8080/team/repo"))
                .isEqualTo("http://git.internal:8080");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://git.internal/team", "https://git.internal//", "https://user:SECRET@git.internal",
            "https://git.internal?token=SECRET", "https://git.internal#SECRET", "ftp://git.internal", "file:///tmp/a",
            "https://git.internal:0", "https://git.internal:65536", "https://git.internal:", "https://git.internal:-1",
            " https://git.internal", "https://git.internal ", "https://evil.internal", "https://git.internal.evil.test",
            "http://github.com", "https://github.com:444", "https://[::1]", "https://[2001:db8::1]:8443"})
    void rejectsInvalidAndNonAllowedOriginsWithoutEchoingValues(String value) {
        assertThatThrownBy(() -> RepositoryOrigin.normalize(value, HOSTS)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test void ipv6AndInvalidOriginsAreRejectedByRepositoryUrlsToo() {
        assertThatThrownBy(() -> RepositoryUrl.parse("https://[::1]/team/repo", Set.of("[::1]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryUrl.parse("https://git.internal:/team/repo", HOSTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepositoryOrigin.fromRepositoryUrl("https://SECRET@git.internal/team/repo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET");
    }
}
