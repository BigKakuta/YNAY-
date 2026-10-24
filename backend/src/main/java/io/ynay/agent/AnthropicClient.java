package io.ynay.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Claude Messages API ({@code POST /v1/messages}) over plain HTTP.
 *
 * <p>Request layout, optimized for prompt caching (render order is tools, system, messages):
 * <ol>
 *   <li>{@code tools}: the action catalogue, sorted by name (identical for every request);</li>
 *   <li>{@code system[0]}: the stable import instructions, with an explicit {@code cache_control}
 *       breakpoint that caches tools + instructions across all users and budgets;</li>
 *   <li>{@code system[1]}: per-request context (budget id, today's date), after the breakpoint;</li>
 *   <li>{@code messages}: covered by top-level automatic caching, so every iteration of the tool loop
 *       reads the previous iteration's prefix (the pasted statement is only paid for once).</li>
 * </ol>
 * The assistant's {@code content} is echoed back unchanged (including thinking blocks), and the
 * transcript is append-only within a turn.
 */
class AnthropicClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String DEFAULT_MODEL = "claude-opus-5-5";
    static final String API_VERSION = "2026-10-21";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /** Models that accept {@code fallbacks: "default"}. */
    private static final Set<String> FALLBACK_MODELS =
            Set.of("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5");

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private final ObjectMapper json;
    private final LlmHttp http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String effort;
    private final boolean fallbacks;

    AnthropicClient(ObjectMapper json, LlmHttp http, String baseUrl, String apiKey, String model, int maxTokens,
                    String effort, boolean refusalFallback) {
        this.json = json;
        this.http = http;
        this.endpoint = messagesEndpoint(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.maxTokens = maxTokens;
        this.effort = effort == null ? "" : effort.trim();
        this.fallbacks = refusalFallback && FALLBACK_MODELS.contains(this.model);
    }

    static URI messagesEndpoint(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        return URI.create(base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages");
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Conversation start(SystemPrompt system, List<ToolSpec> tools, List<ChatMessage> history) {
        ObjectNode base = json.createObjectNode();
        base.put("model", model);
        base.put("max_tokens", maxTokens);

        ArrayNode systemBlocks = base.putArray("system");
        ObjectNode stable = systemBlocks.addObject();
        stable.put("type", "text");
        stable.put("text", system.stable());
        stable.putObject("cache_control").put("type", "ephemeral");
        if (system.perRequest() != null && !system.perRequest().isBlank()) {
            ObjectNode perRequest = systemBlocks.addObject();
            perRequest.put("type", "text");
            perRequest.put("text", system.perRequest());
        }

        ArrayNode toolArray = base.putArray("tools");
        for (ToolSpec tool : tools) {
            ObjectNode t = toolArray.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("input_schema", tool.inputSchema());
        }

        // Automatic caching of the growing conversation tail (one breakpoint slot).
        base.putObject("cache_control").put("type", "ephemeral");
        if (!effort.isEmpty()) {
            base.putObject("output_config").put("effort", effort);
        }
        if (fallbacks) {
            base.put("fallbacks", "default");
        }

        ArrayNode messages = base.putArray("messages");
        for (ChatMessage m : history) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role());
            msg.put("content", m.content());
        }
        return new AnthropicConversation(base, messages);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        if (fallbacks) {
            headers.put("anthropic-beta", FALLBACK_BETA);
        }
        return headers;
    }

    private final class AnthropicConversation implements Conversation {

        private final ObjectNode request;
        private final ArrayNode messages;

        AnthropicConversation(ObjectNode request, ArrayNode messages) {
            this.request = request;
            this.messages = messages;
        }

        @Override
        public Turn next() {
            JsonNode response = http.postJson(endpoint, headers(), request, "Anthropic API");
            logUsage(response);
            String stopReason = response.path("stop_reason").asString("");
            ArrayNode content = echoableContent(response.path("content"));

            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode block : content) {
                switch (block.path("type").asString("")) {
                    case "text" -> {
                        if (!text.isEmpty()) {
                            text.append("\n\n");
                        }
                        text.append(block.path("text").asString(""));
                    }
                    case "tool_use" -> {
                        JsonNode input = block.get("input");
                        calls.add(new ToolCall(block.path("id").asString(), block.path("name").asString(),
                                input != null && input.isObject() ? input : null,
                                input == null ? "" : json.writeValueAsString(input)));
                    }
                    default -> {
                        // thinking, redacted_thinking, fallback markers: kept in the transcript only
                    }
                }
            }

            if ("refusal".equals(stopReason)) {
                return new Turn(text.toString(), List.of(), StopReason.REFUSAL);
            }
            if (!content.isEmpty()) {
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.set("content", content);
            }
            StopReason reason = switch (stopReason) {
                case "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS;
                default -> calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            };
            return new Turn(text.toString(), List.copyOf(calls), reason);
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            if (results.isEmpty()) {
                return;
            }
            // All results of one assistant turn go into a single user message.
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode blocks = user.putArray("content");
            for (ToolResult r : results) {
                ObjectNode b = blocks.addObject();
                b.put("type", "tool_result");
                b.put("tool_use_id", r.toolCallId());
                b.put("content", r.content());
                if (r.isError()) {
                    b.put("is_error", true);
                }
            }
        }
    }

    /**
     * Content to echo back as the assistant turn. After a server-side refusal fallback, blocks of
     * the declined model before the last {@code fallback} marker are dropped except text, as the
     * fallback documentation requires.
     */
    private ArrayNode echoableContent(JsonNode content) {
        ArrayNode out = json.createArrayNode();
        if (!content.isArray()) {
            return out;
        }
        int lastFallback = -1;
        for (int i = 0; i < content.size(); i++) {
            if ("fallback".equals(content.get(i).path("type").asString(""))) {
                lastFallback = i;
            }
        }
        for (int i = 0; i < content.size(); i++) {
            JsonNode block = content.get(i);
            String type = block.path("type").asString("");
            if (i < lastFallback && !"text".equals(type)) {
                continue;
            }
            out.add(block);
        }
        return out;
    }

    private void logUsage(JsonNode response) {
        if (log.isDebugEnabled()) {
            JsonNode u = response.path("usage");
            log.debug("anthropic {} stop={} in={} cacheRead={} cacheWrite={} out={}",
                    response.path("model").asString(""), response.path("stop_reason").asString(""),
                    u.path("input_tokens").asLong(), u.path("cache_read_input_tokens").asLong(),
                    u.path("cache_creation_input_tokens").asLong(), u.path("output_tokens").asLong());
        }
    }
}
