package com.aicreviewer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SafeHttpTransportFailureTest {
    private static final String PRIVATE_BODY = "private-response-fixture";
    private static final String PRIVATE_KEY = "private-request-fixture";

    static Stream<Arguments> rejectedBodies() {
        return Stream.of("Git", "AI").flatMap(service -> Stream.of(403, 503)
                .flatMap(status -> Stream.of(Body.STALLED, Body.OVERSIZED, Body.TRUNCATED)
                        .map(body -> Arguments.of(service, status, body))));
    }

    @ParameterizedTest
    @MethodSource("rejectedBodies")
    void errorHeadersKeepTheirStatusWithoutReadingBrokenOrUnboundedBodies(String service, int status, Body body)
            throws Exception {
        try (var server = new RawResponse(status, body)) {
            var gate = new RecordingGate();
            assertThatThrownBy(() -> exchange(server, service, gate))
                    .isExactlyInstanceOf(IntegrationException.class)
                    .hasMessage(service + " returned HTTP " + status).hasCause(null)
                    .hasMessageNotContaining(PRIVATE_BODY).hasMessageNotContaining(PRIVATE_KEY)
                    .hasMessageNotContaining(server.uri().toString());
            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(gate.before.get()).isEqualTo(1);
            assertThat(gate.limited.get()).isZero();
            // STALLED has no completion before cleanup. Receiving its status cannot depend on EOF.
            assertThat(server.releaseBody.getCount()).isEqualTo(1);
        }
    }

    static Stream<Arguments> incompleteSuccessBodies() {
        return Stream.of("Git", "AI").flatMap(service -> Stream.of(Body.TRUNCATED, Body.TRUNCATED_CHUNK)
                .map(body -> Arguments.of(service, body)));
    }

    @ParameterizedTest
    @MethodSource("incompleteSuccessBodies")
    void successfulStatusWithAnInterruptedBodyRemainsASafeFailure(String service, Body body) throws Exception {
        try (var server = new RawResponse(200, body)) {
            var gate = new RecordingGate();
            assertThatThrownBy(() -> exchange(server, service, gate))
                    .isExactlyInstanceOf(IntegrationException.class)
                    .hasMessage(service + " request failed or response exceeded its size limit").hasCause(null)
                    .hasMessageNotContaining(PRIVATE_BODY).hasMessageNotContaining(PRIVATE_KEY)
                    .hasMessageNotContaining(server.uri().toString());
            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(server.bodyBytesWritten.get()).isEqualTo(PRIVATE_BODY.getBytes(StandardCharsets.UTF_8).length);
            assertThat(gate.before.get()).isEqualTo(1);
            assertThat(gate.limited.get()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Git", "AI"})
    void completeSuccessfulBodyStillReturnsItsBytes(String service) throws Exception {
        try (var server = new RawResponse(200, Body.COMPLETE)) {
            var gate = new RecordingGate();
            assertThat(exchange(server, service, gate)).isEqualTo(PRIVATE_BODY);
            assertThat(server.requests.get()).isEqualTo(1);
            assertThat(gate.before.get()).isEqualTo(1);
            assertThat(gate.limited.get()).isZero();
        }
    }

    private static String exchange(RawResponse server, String service, RateLimitGate gate) {
        var request = HttpRequest.newBuilder(server.uri()).header("Authorization", "Bearer " + PRIVATE_KEY).GET();
        var response = new SafeHttpTransport(Duration.ofSeconds(3), 1024, gate).exchange(request, service);
        assertThat(response.statusCode()).isEqualTo(200);
        return SafeHttpTransport.utf8(response.body());
    }

    private static final class RecordingGate implements RateLimitGate {
        private final AtomicInteger before = new AtomicInteger();
        private final AtomicInteger limited = new AtomicInteger();
        @Override public void beforeRequest(RateLimitedException.Service service, URI uri) { before.incrementAndGet(); }
        @Override public void onRateLimited(RateLimitedException.Service service, URI uri, Instant retryAt) { limited.incrementAndGet(); }
    }

    private enum Body { STALLED, OVERSIZED, TRUNCATED, TRUNCATED_CHUNK, COMPLETE }

    /** One loopback HTTP response; raw framing deliberately permits incomplete fixed/chunked bodies. */
    private static final class RawResponse implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicReference<Socket> accepted = new AtomicReference<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger bodyBytesWritten = new AtomicInteger();
        private final CountDownLatch releaseBody = new CountDownLatch(1);
        private final Future<?> worker;

        private RawResponse(int status, Body body) throws IOException {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            worker = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    accepted.set(socket);
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    int requestBytes = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        requestBytes += line.length();
                        if (requestBytes > 8192) throw new IOException("Synthetic request exceeded fixture limit");
                    }
                    if (line == null) throw new IOException("Synthetic request headers were incomplete");
                    requests.incrementAndGet();
                    byte[] bytes = (body == Body.OVERSIZED ? PRIVATE_BODY.repeat(256) : PRIVATE_BODY)
                            .getBytes(StandardCharsets.UTF_8);
                    String framing = body == Body.TRUNCATED_CHUNK ? "Transfer-Encoding: chunked\r\n"
                            : "Content-Length: " + (body == Body.TRUNCATED || body == Body.STALLED ? bytes.length + 100 : bytes.length) + "\r\n";
                    String headers = "HTTP/1.1 " + status + " Synthetic\r\n" + framing
                            + "Content-Type: application/json\r\nRetry-After: 90\r\nConnection: close\r\n\r\n";
                    var output = socket.getOutputStream();
                    output.write(headers.getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    if (body == Body.STALLED) {
                        releaseBody.await();
                    } else {
                        if (body == Body.TRUNCATED_CHUNK) output.write("100\r\n".getBytes(StandardCharsets.US_ASCII));
                        output.write(bytes);
                        output.flush();
                        bodyBytesWritten.set(bytes.length);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // A rejected response intentionally closes before this fixture finishes writing.
                }
            });
        }

        private URI uri() { return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/data"); }

        @Override public void close() throws Exception {
            releaseBody.countDown();
            listener.close();
            Socket socket = accepted.get();
            if (socket != null) socket.close();
            worker.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
