package io.ynay.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scripted stand-in for an LLM HTTP API (JDK HttpServer on a random local port). Responses are
 * served in order; when the script is exhausted the fallback response (if any) is repeated.
 */
final class FakeLlmServer implements AutoCloseable {

    record Scripted(int status, String body, Map<String, String> headers) {
    }

    record Recorded(String method, String path, Map<String, List<String>> headers, JsonNode body) {

        String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final ConcurrentLinkedDeque<Scripted> script = new ConcurrentLinkedDeque<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Scripted fallback;

    FakeLlmServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeLlmServer respond(String jsonBody) {
        return respond(200, jsonBody, Map.of());
    }

    FakeLlmServer respond(int status, String body, Map<String, String> headers) {
        script.add(new Scripted(status, body, headers));
        return this;
    }

    FakeLlmServer respondForever(String jsonBody) {
        fallback = new Scripted(200, jsonBody, Map.of());
        return this;
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    void reset() {
        script.clear();
        requests.clear();
        fallback = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] in = exchange.getRequestBody().readAllBytes();
        JsonNode body = in.length == 0 ? null : JSON.readTree(new String(in, StandardCharsets.UTF_8));
        // Deep-copy-free snapshot: the client serializes fresh JSON for each request.
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), body));
        Scripted next = script.poll();
        if (next == null) {
            next = fallback;
        }
        if (next == null) {
            next = new Scripted(500, "{\"error\":{\"message\":\"no scripted response left\"}}", Map.of());
        }
        byte[] out = next.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        next.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(next.status(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
