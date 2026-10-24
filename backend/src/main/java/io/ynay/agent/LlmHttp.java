package io.ynay.agent;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal JSON-over-HTTP client for LLM APIs (JDK {@link HttpClient} + Jackson 3), with retries on
 * rate limits, overloads, 5xx and connection errors.
 */
class LlmHttp {

    private static final Logger log = LoggerFactory.getLogger(LlmHttp.class);

    /** 408/409/429 and 5xx (529 = Anthropic "overloaded") are worth retrying. */
    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 429, 500, 502, 503, 504, 529);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration initialBackoff;

    LlmHttp(ObjectMapper json, AgentProperties props) {
        this(json, props.getConnectTimeout(), props.getRequestTimeout(), props.getMaxRetries(), Duration.ofSeconds(1));
    }

    LlmHttp(ObjectMapper json, Duration connectTimeout, Duration requestTimeout, int maxRetries,
            Duration initialBackoff) {
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .proxy(ProxySelector.getDefault())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * POSTs {@code body} as JSON and returns the parsed response.
     *
     * @throws LlmException when the provider answers with an error or cannot be reached
     */
    JsonNode postJson(URI uri, Map<String, String> headers, JsonNode body, String providerLabel) {
        String payload = json.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new LlmException(providerLabel + " did not answer within " + requestTimeout.toSeconds()
                        + "s (YNAY_AGENT_TIMEOUT)", e);
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    log.warn("{} request failed ({}), retrying", providerLabel, e.toString());
                    sleep(backoff(attempt, null));
                    continue;
                }
                throw new LlmException("Could not reach " + providerLabel + " at " + uri + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while waiting for " + providerLabel, e);
            }

            int status = response.statusCode();
            if (status / 100 == 2) {
                try {
                    return json.readTree(response.body());
                } catch (JacksonException e) {
                    throw new LlmException(providerLabel + " returned a response that is not JSON", e);
                }
            }
            if (RETRYABLE.contains(status) && attempt < maxRetries) {
                log.warn("{} answered HTTP {}, retrying", providerLabel, status);
                sleep(backoff(attempt, response.headers().firstValue("retry-after").orElse(null)));
                continue;
            }
            throw new LlmException(providerLabel + " answered HTTP " + status + ": " + errorMessage(response.body()));
        }
    }

    private Duration backoff(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                Duration d = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall through to exponential backoff
            }
        }
        Duration d = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while retrying", e);
        }
    }

    /** Extracts {@code error.message} (Anthropic and OpenAI shapes) or a truncated body. */
    private String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        try {
            JsonNode node = json.readTree(body);
            JsonNode error = node.path("error");
            if (error.isString()) {
                return error.asString();
            }
            String message = error.path("message").asString("");
            if (!message.isBlank()) {
                return message;
            }
        } catch (JacksonException ignored) {
            // not JSON
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    /** The provider failed, refused the request at the HTTP level, or was unreachable. */
    static class LlmException extends RuntimeException {

        LlmException(String message) {
            super(message);
        }

        LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
