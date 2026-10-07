package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitRateLimitTest {
    private HttpFixture server;
    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private GitRepositoryClient client(RateLimitGate gate) {
        return new GitRepositoryClient(Set.of("github.com", "127.0.0.1"), server.url(), "", "github.com", "",
                2, 10, 65536, 262144, 300, new GitCredentialProperties(), gate);
    }

    private RepositoryUrl repository(String provider) {
        return RepositoryUrl.parse(provider.equals("github") ? "https://github.com/team/repo.git" : server.url() + "/team/repo.git",
                Set.of("github.com", "127.0.0.1"));
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void bothGitProvidersPreserveTypedRateLimitsAndDoNotRetryInsideTheAdapter(String provider) {
        List<Instant> published = new ArrayList<>();
        List<URI> started = new ArrayList<>();
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) {
                assertThat(service).isEqualTo(RateLimitedException.Service.GIT);
                started.add(uri);
            }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) {
                assertThat(service).isEqualTo(RateLimitedException.Service.GIT);
                assertThat(uri).isEqualTo(started.getFirst());
                published.add(retryAt);
            }
        };
        server.handler = request -> new HttpFixture.Reply(429, "PRIVATE SOURCE AND TOKEN", Map.of("Retry-After", "90"));
        Instant before = Instant.now();
        assertThatThrownBy(() -> client(gate).batch(repository(provider), null, null, Set.of(), 1))
                .isInstanceOfSatisfying(RateLimitedException.class, error -> {
                    assertThat(error.service()).isEqualTo(RateLimitedException.Service.GIT);
                    assertThat(error.actualResponse()).isTrue();
                    assertThat(error.retryAt()).isBetween(before.plusSeconds(90), Instant.now().plusSeconds(90));
                    assertThat(published).containsExactly(error.retryAt());
                }).hasMessage("Git returned HTTP 429").hasCause(null);
        assertThat(started).hasSize(1);
        assertThat(server.requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"github", "gitlab"})
    void cachedGateStopsGitBeforeEvenLoadingHistory(String provider) {
        RateLimitedException cached = new RateLimitedException(RateLimitedException.Service.GIT, Instant.now().plusSeconds(60), false);
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { throw cached; }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { throw new AssertionError(); }
        };
        assertThatThrownBy(() -> client(gate).batch(repository(provider), null, null, Set.of(), 1)).isSameAs(cached);
        assertThat(server.requests).isEmpty();
    }
}
