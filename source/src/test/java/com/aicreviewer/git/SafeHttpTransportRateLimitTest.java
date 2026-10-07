package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class SafeHttpTransportRateLimitTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private HttpFixture server;
    private final List<String> events = new ArrayList<>();

    @BeforeEach void start() throws Exception { server = new HttpFixture(); }
    @AfterEach void stop() { server.close(); }

    private RateLimitGate gate() {
        return new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) {
                assertThat(service).isEqualTo(RateLimitedException.Service.GIT);
                assertThat(uri).isEqualTo(URI.create(server.url() + "/data"));
                events.add("before");
            }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) {
                assertThat(service).isEqualTo(RateLimitedException.Service.GIT);
                assertThat(uri).isEqualTo(URI.create(server.url() + "/data"));
                assertThat(retryAt).isEqualTo(NOW.plusSeconds(90));
                events.add("limited");
            }
        };
    }

    private void exchange(RateLimitGate gate) {
        new SafeHttpTransport(Duration.ofSeconds(1), 1024, gate, Clock.fixed(NOW, ZoneOffset.UTC))
                .exchange(HttpRequest.newBuilder(URI.create(server.url() + "/data")).GET(), "Git");
    }

    @ParameterizedTest @ValueSource(longs = {0, 2500})
    void headersRejectOversizedOrStalled429BodiesWithoutWaitingForTheBody(long bodyDelay) {
        server.handler = request -> new HttpFixture.Reply(429, "PRIVATE RESPONSE ".repeat(10000),
                Map.of("Retry-After", "90"), 0, bodyDelay);
        assertThatThrownBy(() -> exchange(gate())).isInstanceOfSatisfying(RateLimitedException.class, error -> {
            assertThat(error.service()).isEqualTo(RateLimitedException.Service.GIT);
            assertThat(error.retryAt()).isEqualTo(NOW.plusSeconds(90));
            assertThat(error.actualResponse()).isTrue();
        }).hasMessage("Git returned HTTP 429").hasCause(null).hasMessageNotContaining(server.url());
        assertThat(events).containsExactly("before", "limited");
        assertThat(server.requests).hasSize(1);
    }

    @Test void beyondPolicyDelayDoesNotPublishACooldownOrRetainRemoteData() {
        server.handler = request -> new HttpFixture.Reply(429, "PRIVATE RESPONSE", Map.of("Retry-After", "9999999999999999999999"));
        assertThatThrownBy(() -> exchange(gate())).isInstanceOfSatisfying(RateLimitedException.class, error -> {
            assertThat(error.retryAt()).isNull();
            assertThat(error.actualResponse()).isTrue();
        }).hasMessage("Git returned HTTP 429").hasCause(null);
        assertThat(events).containsExactly("before");
        assertThat(server.requests).hasSize(1);
    }

    @Test void cachedCooldownPreventsEveryNetworkRequest() {
        RateLimitedException cached = new RateLimitedException(RateLimitedException.Service.GIT, NOW.plusSeconds(90), false);
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { throw cached; }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { throw new AssertionError(); }
        };
        assertThatThrownBy(() -> exchange(gate)).isSameAs(cached);
        assertThat(server.requests).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void databaseFailuresFromEitherGateCallPropagateWithoutTransportWrapping(boolean onResponse) {
        var failure = new DataAccessResourceFailureException("synthetic database failure");
        server.handler = request -> new HttpFixture.Reply(429, "PRIVATE RESPONSE", Map.of("Retry-After", "90"));
        RateLimitGate gate = new RateLimitGate() {
            @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { if (!onResponse) throw failure; }
            @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { throw failure; }
        };
        assertThatThrownBy(() -> exchange(gate)).isSameAs(failure);
        assertThat(server.requests).hasSize(onResponse ? 1 : 0);
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 403, 500, 503})
    void otherStatusCodesRemainFailuresWithoutRetryClassification(int status) {
        server.handler = request -> new HttpFixture.Reply(status, "PRIVATE RESPONSE", Map.of("Retry-After", "90", "Location", server.url() + "/redirect"));
        assertThatThrownBy(() -> exchange(gate())).isExactlyInstanceOf(IntegrationException.class)
                .hasMessage("Git returned HTTP " + status).hasCause(null);
        assertThat(events).containsExactly("before");
        assertThat(server.requests).hasSize(1);
    }

    @Test void successfulResponsesStillHaveAByteLimit() {
        server.handler = request -> new HttpFixture.Reply(200, "x".repeat(2048), Map.of("Retry-After", "90"));
        assertThatThrownBy(() -> exchange(gate())).isExactlyInstanceOf(IntegrationException.class).hasMessageContaining("size limit");
        assertThat(events).containsExactly("before");
    }
}
