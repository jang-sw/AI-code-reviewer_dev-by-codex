package com.aicreviewer.git;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Limits response bytes while receiving them, and bounds the entire exchange including slow bodies. */
public final class SafeHttpTransport {
    private final HttpClient client;
    private final Duration timeout;
    private final int maxBytes;

    public SafeHttpTransport(Duration timeout, int maxBytes) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(30)) > 0
                || maxBytes < 1024 || maxBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid HTTP timeout or response size limit");
        }
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public HttpResponse<byte[]> exchange(HttpRequest.Builder builder, String service) {
        return exchange(builder, service, timeout);
    }

    public HttpResponse<byte[]> exchange(HttpRequest.Builder builder, String service, Duration remaining) {
        Duration effectiveTimeout = remaining.compareTo(timeout) < 0 ? remaining : timeout;
        if (effectiveTimeout.isZero() || effectiveTimeout.isNegative()) throw new IntegrationException(service + " operation exceeded its time budget");
        var future = client.sendAsync(builder.timeout(effectiveTimeout).build(), info -> new LimitedBody(maxBytes));
        try {
            HttpResponse<byte[]> response = future.get(effectiveTimeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IntegrationException(service + " returned HTTP " + response.statusCode());
            }
            return response;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(service + " request interrupted");
        } catch (TimeoutException ex) {
            throw new IntegrationException(service + " request timed out");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new IntegrationException(service + " request timed out");
            }
            throw new IntegrationException(service + " request failed or response exceeded its size limit");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public static URI baseUri(String input) {
        try {
            URI uri = URI.create(input);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return URI.create(input.replaceAll("/+$", ""));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Configure a valid HTTP(S) service base URL without credentials, query or fragment");
        }
    }

    public static String credential(String value) {
        if (value == null || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid service credential configuration");
        }
        return value;
    }

    public static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if ((long) buffer.size() + item.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new IntegrationException("Response size limit exceeded"));
                    return;
                }
                byte[] part = new byte[item.remaining()];
                item.get(part);
                buffer.writeBytes(part);
            }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
