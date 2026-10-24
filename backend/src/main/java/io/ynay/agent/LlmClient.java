package io.ynay.agent;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A chat model with function calling. Implementations translate the provider-neutral types below
 * to one wire protocol and keep the provider's native transcript inside a {@link Conversation}, so
 * provider-specific blocks (e.g. Anthropic thinking blocks) are echoed back verbatim.
 */
public interface LlmClient {

    /** Provider name as configured ({@code anthropic}, {@code openai}). */
    String provider();

    /** Model id sent to the provider. */
    String model();

    /**
     * Starts a tool-use loop.
     *
     * @param system  system prompt: a stable part (cacheable, identical for every request) and a
     *                per-request part (budget id, today's date)
     * @param tools   tool definitions, in a deterministic order
     * @param history prior chat turns, oldest first, ending with a user message
     */
    Conversation start(SystemPrompt system, List<ToolSpec> tools, List<ChatMessage> history);

    /** One in-flight tool-use loop. Not thread-safe; used on a single request thread. */
    interface Conversation {

        /** Calls the model with the transcript so far and appends its reply to the transcript. */
        Turn next();

        /**
         * Appends results for every tool call of the last {@link Turn} (in any order, but one per
         * call id) to the transcript.
         */
        void addToolResults(List<ToolResult> results);
    }

    record SystemPrompt(String stable, String perRequest) {
    }

    record ToolSpec(String name, String description, ObjectNode inputSchema) {
    }

    record ChatMessage(String role, String content) {
    }

    /** {@code input} is the parsed argument object, or {@code null} when it was not valid JSON. */
    record ToolCall(String id, String name, JsonNode input, String rawInput) {
    }

    record ToolResult(String toolCallId, String content, boolean isError) {
    }

    enum StopReason {
        /** The model finished its turn. */
        END_TURN,
        /** The model wants the tool calls of this turn executed. */
        TOOL_USE,
        /** Output was cut off at {@code max_tokens}; tool calls of this turn may be incomplete. */
        MAX_TOKENS,
        /** The provider declined the request (safety classifier / content filter). */
        REFUSAL
    }

    record Turn(String text, List<ToolCall> toolCalls, StopReason stopReason) {
    }
}
