package io.ynay.agent;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionException;
import io.ynay.action.ActionRegistry;
import io.ynay.security.CurrentUser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory action catalogue resembling the real one: a few budget-scoped tools, budget management
 * tools that the agent must not see, a destructive tool, a non-MCP tool and a tool without budgetId.
 */
final class FakeActionRegistry implements ActionRegistry {

    record Invocation(String name, JsonNode args, UUID userId) {
    }

    static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<ActionDescriptor> descriptors = new ArrayList<>();
    private final List<Invocation> invocations = Collections.synchronizedList(new ArrayList<>());

    FakeActionRegistry() {
        add("list_accounts", "List accounts", true, false, true, true);
        add("list_categories", "List categories", true, false, true, true);
        add("list_payees", "List payees", true, false, true, true);
        add("import_transactions", "Import transactions", false, false, true, true);
        add("create_transaction", "Create a transaction", false, false, true, true);
        add("delete_transaction", "Delete a transaction", false, true, true, true);
        add("get_budget", "Get a budget", true, false, true, true);
        add("delete_budget", "Delete a budget", false, true, true, true);
        add("share_budget", "Share a budget", false, false, true, true);
        add("remove_budget_member", "Remove a member", false, false, true, true);
        add("update_budget", "Rename a budget", false, false, true, true);
        add("list_budgets", "List budgets", true, false, true, false);
        add("create_budget", "Create a budget", false, false, true, false);
        add("whoami", "Who am I", true, false, true, false);
        add("create_api_token", "Mint a token", false, false, false, false);
        descriptors.sort(Comparator.comparing(ActionDescriptor::name));
    }

    private void add(String name, String description, boolean readOnly, boolean destructive, boolean mcp,
                     boolean budgetScoped) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        if (budgetScoped) {
            props.putObject("budgetId").put("type", "string").put("format", "uuid");
            schema.putArray("required").add("budgetId");
        }
        if (name.equals("import_transactions")) {
            props.putObject("accountId").put("type", "string");
            props.putObject("transactions").put("type", "array");
        }
        descriptors.add(new ActionDescriptor(name, description, schema, readOnly, destructive, false, mcp));
    }

    List<Invocation> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    void reset() {
        invocations.clear();
    }

    @Override
    public List<ActionDescriptor> list() {
        return List.copyOf(descriptors);
    }

    @Override
    public Optional<ActionDescriptor> find(String name) {
        return descriptors.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    @Override
    public JsonNode invoke(String name, JsonNode arguments) {
        UUID user = CurrentUser.id(); // like the real registry: actions run as the current user
        invocations.add(new Invocation(name, arguments, user));
        return switch (name) {
            case "list_accounts" -> JSON.readTree("[{\"id\":\"" + ACCOUNT_ID + "\",\"name\":\"Checking\","
                    + "\"type\":\"CHECKING\",\"onBudget\":true,\"closed\":false,\"balance\":0}]");
            case "list_categories", "list_payees" -> JSON.createArrayNode();
            case "import_transactions" -> {
                if (!arguments.path("accountId").asString("").equals(ACCOUNT_ID.toString())) {
                    throw ActionException.notFound("Account " + arguments.path("accountId").asString(""));
                }
                int n = arguments.path("transactions").size();
                yield JSON.readTree("{\"imported\":" + n + ",\"duplicates\":0,\"transactionIds\":[],"
                        + "\"duplicateImportIds\":[]}");
            }
            default -> JSON.readTree("{\"ok\":true}");
        };
    }
}
