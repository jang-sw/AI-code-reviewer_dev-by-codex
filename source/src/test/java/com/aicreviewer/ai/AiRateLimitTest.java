package com.aicreviewer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.HttpFixture;
import com.aicreviewer.git.RateLimitGate;
import com.aicreviewer.git.RateLimitedException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiRateLimitTest {
    private static final GitCommit COMMIT = new GitCommit("a".repeat(40), null, "synthetic commit",
            "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-a\n+b\n");
    private HttpFixture server;
    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private AiReviewClient client(String provider, RateLimitGate gate) {
        return provider.equals("openai") ? AiReviewClient.openAiFixture(URI.create(server.url() + "/v1/responses"),
                "synthetic-model", "synthetic-key", 2, 65536, 262144, 32768, 4096, 8, gate)
                : new AiReviewClient(provider, server.url(), "synthetic-model", "synthetic-key", 2,
                        65536, 262144, 32768, 4096, "", "", 8, gate);
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void allAiProvidersPreserve429ClassificationAndPublishTheActualEndpoint(String provider) {
        List<Instant> published = new ArrayList<>();
        List<URI> started = new ArrayList<>();
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) {
                assertThat(service).isEqualTo(RateLimitedException.Service.AI);
                started.add(uri);
            }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) {
                assertThat(service).isEqualTo(RateLimitedException.Service.AI);
                assertThat(uri).isEqualTo(started.getFirst());
                published.add(retryAt);
            }
        };
        server.handler = request -> new HttpFixture.Reply(429, "PRIVATE RESPONSE synthetic-key", Map.of("Retry-After", "90"));
        Instant before = Instant.now();
        assertThatThrownBy(() -> client(provider, gate).review(COMMIT)).isInstanceOfSatisfying(RateLimitedException.class, error -> {
            assertThat(error.service()).isEqualTo(RateLimitedException.Service.AI);
            assertThat(error.actualResponse()).isTrue();
            assertThat(error.retryAt()).isBetween(before.plusSeconds(90), Instant.now().plusSeconds(90));
            assertThat(published).containsExactly(error.retryAt());
        }).hasMessage("AI returned HTTP 429").hasCause(null);
        assertThat(started).hasSize(1);
        assertThat(started.getFirst().getPath()).isEqualTo(switch (provider) {
            case "ollama" -> "/api/chat";
            case "litellm" -> "/chat/completions";
            default -> "/v1/responses";
        });
        assertThat(server.requests).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"ollama", "litellm", "openai"})
    void cachedGateReturnsTheSameSafeExceptionWithoutAiNetworkCalls(String provider) {
        RateLimitedException cached = new RateLimitedException(RateLimitedException.Service.AI, Instant.now().plusSeconds(60), false);
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { throw cached; }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { throw new AssertionError(); }
        };
        assertThatThrownBy(() -> client(provider, gate).review(COMMIT)).isSameAs(cached);
        assertThat(server.requests).isEmpty();
    }
}
