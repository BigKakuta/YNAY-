package io.ynay.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.ynay.action.ActionRegistry;
import io.ynay.agent.LlmClient.ChatMessage;
import io.ynay.agent.LlmClient.Conversation;
import io.ynay.agent.LlmClient.StopReason;
import io.ynay.agent.LlmClient.SystemPrompt;
import io.ynay.agent.LlmClient.ToolCall;
import io.ynay.agent.LlmClient.ToolResult;
import io.ynay.agent.LlmClient.Turn;
import io.ynay.budget.BudgetAccess;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The in-app import agent: a stateless chat turn that runs an LLM tool-use loop over the action
 * catalogue, restricted to one budget, as the logged-in user.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    static final int MAX_MESSAGES = 200;

    private final AgentProperties props;
    private final ObjectMapper json;
    private final ObjectProvider<ActionRegistry> registry;
    private final ObjectProvider<BudgetAccess> budgetAccess;
    private final Clock clock;
    private final LlmClient client;
    private final String disabledReason;

    @Autowired
    public AgentService(AgentProperties props, ObjectMapper json, ObjectProvider<ActionRegistry> registry,
                        ObjectProvider<BudgetAccess> budgetAccess) {
        this(props, json, registry, budgetAccess, Clock.systemDefaultZone(), null);
    }

    /** For tests: {@code client} overrides the client built from {@code props} when non-null. */
    AgentService(AgentProperties props, ObjectMapper json, ObjectProvider<ActionRegistry> registry,
                 ObjectProvider<BudgetAccess> budgetAccess, Clock clock, LlmClient client) {
        this.props = props;
        this.json = json;
        this.registry = registry;
        this.budgetAccess = budgetAccess;
        this.clock = clock;
        String reason = configurationProblem(props);
        this.disabledReason = reason;
        this.client = client != null ? client : reason == null ? createClient(props, json) : null;
        if (reason != null) {
            log.info("Import agent disabled: {}", reason);
        } else {
            log.info("Import agent enabled: provider={}, model={}", this.client.provider(), this.client.model());
        }
    }

    // ---- API types (see docs/ARCHITECTURE.md, "Import agent contract") ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(boolean enabled, String provider, String model, String reason) {
    }

    public record ChatRequest(UUID budgetId, List<ChatMessage> messages) {
    }

    public record ChatResponse(String reply, List<ToolCallLog> toolCalls) {
    }

    public record ToolCallLog(String name, JsonNode input, JsonNode output,
                              @JsonProperty("isError") boolean isError) {
    }

    // ---- status ----

    public Status status() {
        String reason = currentProblem();
        String provider = normalizedProvider(props);
        String model = client != null ? client.model() : defaultModel(props);
        return new Status(reason == null, provider, model, reason);
    }

    private String currentProblem() {
        if (disabledReason != null) {
            return disabledReason;
        }
        if (registry.getIfAvailable() == null) {
            return "the action registry is not available.";
        }
        return null;
    }

    // ---- chat ----

    public ChatResponse chat(ChatRequest request) {
        String problem = currentProblem();
        if (problem != null) {
            throw AgentException.disabled(problem);
        }
        if (request == null || request.budgetId() == null) {
            throw AgentException.invalid("budgetId is required");
        }
        List<ChatMessage> history = normalize(request.messages());
        UUID budgetId = request.budgetId();

        // Fail fast (404, no LLM tokens spent) when the caller cannot see the budget.
        BudgetAccess access = budgetAccess.getIfAvailable();
        if (access != null) {
            access.requireMember(budgetId);
        }

        AgentToolbox toolbox = new AgentToolbox(registry.getObject(), json, props);
        SystemPrompt system = new SystemPrompt(AgentPrompts.STABLE,
                AgentPrompts.perRequest(budgetId, LocalDate.now(clock)));
        List<ToolCallLog> calls = new ArrayList<>();
        String lastText = "";

        try {
            Conversation conversation = client.start(system, toolbox.specs(), history);
            for (int iteration = 1; iteration <= props.getMaxIterations(); iteration++) {
                Turn turn = conversation.next();
                if (!turn.text().isBlank()) {
                    lastText = turn.text();
                }
                if (turn.stopReason() == StopReason.REFUSAL) {
                    return new ChatResponse(withNote(lastText, "The model declined to continue with this request."
                            + " Try rephrasing it, or remove unrelated content from the pasted data."), calls);
                }
                if (turn.toolCalls().isEmpty()) {
                    String reply = turn.text().isBlank() ? lastText : turn.text();
                    if (turn.stopReason() == StopReason.MAX_TOKENS) {
                        reply = withNote(reply, "The reply was cut off at the output token limit"
                                + " (YNAY_AGENT_MAX_TOKENS).");
                    }
                    return new ChatResponse(reply.isBlank() ? "(no reply)" : reply, calls);
                }

                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : turn.toolCalls()) {
                    if (turn.stopReason() == StopReason.MAX_TOKENS) {
                        // The call may have been cut off mid-arguments: never execute it.
                        results.add(new ToolResult(call.id(), "{\"error\":{\"code\":\"OUTPUT_TRUNCATED\",\"message\":"
                                + "\"Your response hit the output token limit, so this tool call was not executed."
                                + " Send fewer transactions per import_transactions call.\"}}", true));
                        continue;
                    }
                    AgentToolbox.Execution exec = toolbox.execute(call, budgetId);
                    results.add(exec.result());
                    calls.add(exec.log());
                }
                conversation.addToolResults(results);
            }
        } catch (LlmHttp.LlmException e) {
            log.warn("Import agent LLM call failed: {}", e.getMessage());
            throw AgentException.upstream(e.getMessage() + summarizeSoFar(calls), e);
        }

        return new ChatResponse(withNote(lastText, "Stopped after " + props.getMaxIterations()
                + " model calls (YNAY_AGENT_MAX_ITERATIONS) before the assistant finished. "
                + calls.size() + " tool call(s) ran; review what was imported and send \"continue\" to resume."),
                calls);
    }

    private static String summarizeSoFar(List<ToolCallLog> calls) {
        long writes = calls.stream().filter(c -> !c.isError() && !c.name().startsWith("list_")
                && !c.name().startsWith("get_")).count();
        return writes == 0 ? "" : " (" + writes + " change(s) were already saved before the failure)";
    }

    private static String withNote(String text, String note) {
        return text == null || text.isBlank() ? note : text + "\n\n" + note;
    }

    /** Validates size limits and merges consecutive messages of the same role. */
    private List<ChatMessage> normalize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw AgentException.invalid("messages must not be empty");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw AgentException.tooLarge("Too many messages (" + messages.size() + ", max " + MAX_MESSAGES
                    + "). Start a new conversation.");
        }
        long total = 0;
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m == null || m.role() == null) {
                throw AgentException.invalid("every message needs a role");
            }
            String role = m.role().toLowerCase(Locale.ROOT);
            if (!role.equals("user") && !role.equals("assistant")) {
                throw AgentException.invalid("message role must be 'user' or 'assistant'");
            }
            String content = m.content() == null ? "" : m.content();
            total += content.length();
            if (content.isBlank()) {
                continue;
            }
            if (!out.isEmpty() && out.getLast().role().equals(role)) {
                ChatMessage prev = out.removeLast();
                out.add(new ChatMessage(role, prev.content() + "\n\n" + content));
            } else {
                out.add(new ChatMessage(role, content));
            }
        }
        if (total > props.getMaxInputChars()) {
            throw AgentException.tooLarge("The conversation is too large (" + total + " characters, max "
                    + props.getMaxInputChars() + "). Import the statement in smaller parts or start a new"
                    + " conversation.");
        }
        if (out.isEmpty() || !out.getLast().role().equals("user")) {
            throw AgentException.invalid("the last message must be a non-empty user message");
        }
        if (!out.getFirst().role().equals("user")) {
            out.removeFirst(); // a leading assistant greeting from the UI carries no information
        }
        return out;
    }

    // ---- configuration ----

    static String normalizedProvider(AgentProperties props) {
        return props.getProvider() == null ? "" : props.getProvider().trim().toLowerCase(Locale.ROOT);
    }

    static String defaultModel(AgentProperties props) {
        if (props.getModel() != null && !props.getModel().isBlank()) {
            return props.getModel().trim();
        }
        return "anthropic".equals(normalizedProvider(props)) ? AnthropicClient.DEFAULT_MODEL : null;
    }

    /** Why the agent cannot run with this configuration, or {@code null} when it can. */
    static String configurationProblem(AgentProperties props) {
        boolean hasKey = props.getApiKey() != null && !props.getApiKey().isBlank();
        boolean hasBaseUrl = props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
        if (hasBaseUrl) {
            try {
                URI uri = URI.create(props.getBaseUrl().trim());
                if (uri.getScheme() == null || !uri.getScheme().startsWith("http") || uri.getHost() == null) {
                    return "YNAY_AGENT_BASE_URL must be an http(s) URL.";
                }
            } catch (IllegalArgumentException e) {
                return "YNAY_AGENT_BASE_URL is not a valid URL.";
            }
        }
        if (props.getMaxIterations() < 1 || props.getMaxTokens() < 1) {
            return "YNAY_AGENT_MAX_ITERATIONS and YNAY_AGENT_MAX_TOKENS must be positive.";
        }
        return switch (normalizedProvider(props)) {
            case "anthropic" -> hasKey ? null : "set YNAY_AGENT_API_KEY to an Anthropic API key.";
            case "openai" -> {
                if (defaultModel(props) == null) {
                    yield "set YNAY_AGENT_MODEL to a tool-capable model served by your OpenAI-compatible server.";
                }
                // A self-hosted server (custom base URL) usually needs no key; api.openai.com does.
                yield hasKey || hasBaseUrl ? null
                        : "set YNAY_AGENT_API_KEY, or YNAY_AGENT_BASE_URL for a local server (e.g. Ollama).";
            }
            default -> "unknown YNAY_AGENT_PROVIDER '" + props.getProvider() + "' (use 'anthropic' or 'openai').";
        };
    }

    private static LlmClient createClient(AgentProperties props, ObjectMapper json) {
        LlmHttp http = new LlmHttp(json, props);
        String baseUrl = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        String apiKey = props.getApiKey() == null ? "" : props.getApiKey().trim();
        return switch (normalizedProvider(props)) {
            case "anthropic" -> new AnthropicClient(json, http, baseUrl, apiKey, defaultModel(props),
                    props.getMaxTokens(), props.getEffort(), props.isRefusalFallback());
            case "openai" -> new OpenAiClient(json, http, baseUrl, apiKey, defaultModel(props), props.getMaxTokens());
            default -> throw new IllegalStateException("unknown provider");
        };
    }
}
