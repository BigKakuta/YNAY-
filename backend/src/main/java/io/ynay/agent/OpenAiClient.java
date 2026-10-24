package io.ynay.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI-compatible Chat Completions ({@code POST {base}/chat/completions}) with function calling.
 * Works with Ollama, llama.cpp server, vLLM, LM Studio, LocalAI and hosted OpenAI-compatible APIs.
 */
class OpenAiClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);
    /** Reasoning models served locally (Qwen3, DeepSeek-R1, ...) often inline their reasoning. */
    private static final Pattern THINK_TAGS = Pattern.compile("(?s)<think>.*?</think>\\s*");

    private final ObjectMapper json;
    private final LlmHttp http;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    OpenAiClient(ObjectMapper json, LlmHttp http, String baseUrl, String apiKey, String model, int maxTokens) {
        this.json = json;
        this.http = http;
        String base = (baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl).replaceAll("/+$", "");
        this.endpoint = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Conversation start(SystemPrompt system, List<ToolSpec> tools, List<ChatMessage> history) {
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        request.put("max_tokens", maxTokens);

        ArrayNode toolArray = request.putArray("tools");
        for (ToolSpec tool : tools) {
            ObjectNode fn = toolArray.addObject().put("type", "function").putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.inputSchema());
        }

        ArrayNode messages = request.putArray("messages");
        String systemText = system.perRequest() == null || system.perRequest().isBlank()
                ? system.stable()
                : system.stable() + "\n\n" + system.perRequest();
        messages.addObject().put("role", "system").put("content", systemText);
        for (ChatMessage m : history) {
            messages.addObject().put("role", m.role()).put("content", m.content());
        }
        return new OpenAiConversation(request, messages);
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    private final class OpenAiConversation implements Conversation {

        private final ObjectNode request;
        private final ArrayNode messages;
        private int generatedIds;

        OpenAiConversation(ObjectNode request, ArrayNode messages) {
            this.request = request;
            this.messages = messages;
        }

        @Override
        public Turn next() {
            JsonNode response = http.postJson(endpoint, headers(), request, "LLM server");
            JsonNode choice = response.path("choices").path(0);
            if (choice.isMissingNode()) {
                throw new LlmHttp.LlmException("LLM server returned no choices");
            }
            if (log.isDebugEnabled()) {
                JsonNode u = response.path("usage");
                log.debug("openai {} finish={} in={} out={}", response.path("model").asString(""),
                        choice.path("finish_reason").asString(""), u.path("prompt_tokens").asLong(),
                        u.path("completion_tokens").asLong());
            }
            JsonNode message = choice.path("message");
            String finish = choice.path("finish_reason").asString("");
            String content = message.path("content").isString() ? message.path("content").asString() : "";

            List<ToolCall> calls = new ArrayList<>();
            ArrayNode echoedCalls = json.createArrayNode();
            for (JsonNode tc : message.path("tool_calls")) {
                String id = tc.path("id").asString("");
                if (id.isBlank()) {
                    id = "call_" + (++generatedIds);
                }
                String name = tc.path("function").path("name").asString("");
                JsonNode argsNode = tc.path("function").path("arguments");
                // The spec says arguments is a JSON string; some servers send an object.
                String raw = argsNode.isString() ? argsNode.asString() : json.writeValueAsString(argsNode);
                JsonNode input = parseArguments(raw);
                calls.add(new ToolCall(id, name, input, raw));

                ObjectNode echoed = echoedCalls.addObject();
                echoed.put("id", id);
                echoed.put("type", "function");
                echoed.putObject("function").put("name", name).put("arguments", raw);
            }

            if ("content_filter".equals(finish)) {
                return new Turn(content, List.of(), StopReason.REFUSAL);
            }

            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            if (calls.isEmpty()) {
                assistant.put("content", content);
            } else {
                if (content.isEmpty()) {
                    assistant.putNull("content");
                } else {
                    assistant.put("content", content);
                }
                assistant.set("tool_calls", echoedCalls);
            }

            String text = THINK_TAGS.matcher(content).replaceAll("").strip();
            StopReason reason = "length".equals(finish)
                    ? StopReason.MAX_TOKENS
                    : calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            return new Turn(text, List.copyOf(calls), reason);
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            for (ToolResult r : results) {
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", r.toolCallId())
                        // Chat Completions has no error flag on tool messages.
                        .put("content", r.isError() ? "ERROR: " + r.content() : r.content());
            }
        }
    }

    private JsonNode parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            return node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
