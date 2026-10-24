package io.ynay.agent;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionException;
import io.ynay.action.ActionRegistry;
import io.ynay.agent.LlmClient.ToolCall;
import io.ynay.agent.LlmClient.ToolResult;
import io.ynay.agent.LlmClient.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools the import agent may use, derived from the action catalogue, and their execution
 * restricted to one budget.
 *
 * <p>A tool is offered when it is an MCP action, not in {@link AgentProperties#getExcludedTools()},
 * not destructive (unless {@link AgentProperties#isAllowDestructive()}) and budget-scoped (its input
 * schema has a {@code budgetId} property), so the agent cannot reach other budgets. Calls run
 * in-process through {@link ActionRegistry#invoke} on the caller's thread, i.e. as the logged-in user.
 */
class AgentToolbox {

    private static final Logger log = LoggerFactory.getLogger(AgentToolbox.class);
    static final String BUDGET_ID = "budgetId";

    private final ActionRegistry registry;
    private final ObjectMapper json;
    private final int maxResultChars;
    private final Map<String, ActionDescriptor> tools;
    private final List<ToolSpec> specs;

    AgentToolbox(ActionRegistry registry, ObjectMapper json, AgentProperties props) {
        this.registry = registry;
        this.json = json;
        this.maxResultChars = props.getMaxToolResultChars();
        Set<String> excluded = Set.copyOf(props.getExcludedTools());
        Map<String, ActionDescriptor> allowed = new LinkedHashMap<>();
        registry.list().stream()
                .filter(ActionDescriptor::mcp)
                .filter(d -> !excluded.contains(d.name()))
                .filter(d -> props.isAllowDestructive() || !d.destructive())
                .filter(AgentToolbox::isBudgetScoped)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(d -> allowed.put(d.name(), d));
        this.tools = Map.copyOf(allowed);
        this.specs = allowed.values().stream()
                .map(d -> new ToolSpec(d.name(), d.description(), d.inputSchema()))
                .toList();
    }

    static boolean isBudgetScoped(ActionDescriptor d) {
        return d.inputSchema() != null && d.inputSchema().path("properties").has(BUDGET_ID);
    }

    /** Tool definitions in a stable order (sorted by name), so the prompt prefix stays cacheable. */
    List<ToolSpec> specs() {
        return specs;
    }

    /** Result of one call: what goes back to the model and what is reported to the client. */
    record Execution(ToolResult result, AgentService.ToolCallLog log) {
    }

    Execution execute(ToolCall call, UUID budgetId) {
        JsonNode loggedInput = call.input() != null ? call.input() : json.getNodeFactory().stringNode(call.rawInput());
        ActionDescriptor descriptor = tools.get(call.name());
        if (descriptor == null) {
            return error(call, loggedInput, "NOT_AVAILABLE",
                    "Tool '" + call.name() + "' is not available to the import assistant.");
        }
        if (call.input() == null) {
            return error(call, loggedInput, "INVALID_INPUT", "Tool arguments must be a JSON object.");
        }
        ObjectNode args = (ObjectNode) call.input().deepCopy();
        JsonNode given = args.get(BUDGET_ID);
        if (given == null || given.isNull()) {
            args.put(BUDGET_ID, budgetId.toString());
        } else if (!sameBudget(given, budgetId)) {
            return error(call, loggedInput, "FORBIDDEN",
                    "This conversation is restricted to budget " + budgetId + "; budgetId " + given
                            + " is not allowed. Use budgetId " + budgetId + ".");
        }

        try {
            JsonNode output = registry.invoke(call.name(), args);
            if (output == null) {
                output = json.getNodeFactory().nullNode();
            }
            return new Execution(new ToolResult(call.id(), truncate(json.writeValueAsString(output)), false),
                    new AgentService.ToolCallLog(call.name(), args, output, false));
        } catch (ActionException e) {
            return error(call, args, e.code().name(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Agent tool {} failed", call.name(), e);
            return error(call, args, "INTERNAL_ERROR", "The tool failed unexpectedly: " + e.getClass().getSimpleName());
        }
    }

    private static boolean sameBudget(JsonNode given, UUID budgetId) {
        if (!given.isString()) {
            return false;
        }
        try {
            return UUID.fromString(given.asString().trim()).equals(budgetId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Execution error(ToolCall call, JsonNode input, String code, String message) {
        ObjectNode body = json.createObjectNode();
        ObjectNode err = body.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return new Execution(new ToolResult(call.id(), json.writeValueAsString(body), true),
                new AgentService.ToolCallLog(call.name(), input, body, true));
    }

    private String truncate(String s) {
        if (s.length() <= maxResultChars) {
            return s;
        }
        return s.substring(0, maxResultChars) + "\n[... truncated " + (s.length() - maxResultChars)
                + " characters; narrow the query (e.g. limit/offset or filters) to see more]";
    }
}
