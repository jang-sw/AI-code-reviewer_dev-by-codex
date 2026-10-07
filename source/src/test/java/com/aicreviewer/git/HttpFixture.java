package com.aicreviewer.git;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class HttpFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public volatile Function<Request, Reply> handler = request -> new Reply(404, "{}");

    public HttpFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            Reply reply = handler.apply(request);
            try {
                if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
                reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), body.length);
                exchange.getResponseBody().flush();
                if (reply.bodyDelayMs() > 0) Thread.sleep(reply.bodyDelayMs());
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* Client timeout/size-limit tests close the connection. */ }
            finally { exchange.close(); }
        });
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }
    public record Reply(int status, String body, Map<String, String> headers, long delayMs, long bodyDelayMs) {
        public Reply(int status, String body) { this(status, body, Map.of(), 0, 0); }
        public Reply(int status, String body, Map<String, String> headers) { this(status, body, headers, 0, 0); }
    }
}
