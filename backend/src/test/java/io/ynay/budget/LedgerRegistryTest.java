package io.ynay.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.action.ActionDescriptor;
import io.ynay.action.ActionException;
import io.ynay.action.ActionRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The ledger actions as seen through the action registry (JSON in, JSON out, one transaction per call). */
class LedgerRegistryTest extends LedgerTestSupport {

    static final List<String> LEDGER_ACTIONS = List.of(
            "list_budgets", "get_budget", "create_budget", "update_budget", "delete_budget", "list_budget_members",
            "share_budget", "remove_budget_member", "list_accounts", "create_account", "update_account",
            "delete_account", "list_categories", "create_category_group", "update_category_group",
            "delete_category_group", "create_category", "update_category", "delete_category", "list_payees",
            "update_payee", "merge_payees", "list_transactions", "get_transaction", "create_transaction",
            "update_transaction", "delete_transaction", "import_transactions", "approve_transactions",
            "set_transactions_cleared", "reconcile_account");

    @Autowired
    ActionRegistry registry;

    @Autowired
    JsonMapper json;

    private JsonNode call(String name, String arguments) {
        return registry.invoke(name, arguments == null ? null : json.readTree(arguments));
    }

    @Test
    void allLedgerActionsAreRegisteredWithAnnotations() {
        Map<String, ActionDescriptor> byName = registry.list().stream()
                .collect(Collectors.toMap(ActionDescriptor::name, d -> d));
        assertThat(byName.keySet()).containsAll(LEDGER_ACTIONS);
        for (String name : LEDGER_ACTIONS) {
            ActionDescriptor d = byName.get(name);
            assertThat(d.mcp()).as(name).isTrue();
            assertThat(d.description()).as(name).isNotBlank();
            assertThat(d.readOnly()).as(name).isEqualTo(name.startsWith("list_") || name.startsWith("get_"));
            assertThat(d.destructive()).as(name)
                    .isEqualTo(name.startsWith("delete_") || name.equals("merge_payees")
                            || name.equals("remove_budget_member"));
        }
        JsonNode schema = byName.get("import_transactions").inputSchema();
        assertThat(schema.toString()).contains("transactions", "importId", "payeeName");
    }

    @Test
    void ledgerDataFeedsTheMonthBudget() {
        loginAsNewUser();
        java.util.UUID budgetId = newBudget();
        java.util.UUID checking = account(budgetId, "Checking", io.ynay.account.AccountType.CHECKING, 100_000).id();
        java.util.UUID card = account(budgetId, "Visa", io.ynay.account.AccountType.CREDIT_CARD, -40_000).id();
        java.util.UUID house = account(budgetId, "House", io.ynay.account.AccountType.ASSET, 1_000_000).id();
        java.util.UUID groceries = category(budgetId, "Groceries").id();
        spend(budgetId, card, D1, -5_000, "Grocer", groceries);
        transfer(budgetId, checking, card, D1, -10_000, null);
        transfer(budgetId, checking, house, D1, -1_000, groceries);

        JsonNode month = call("get_month_budget", "{\"budgetId\":\"" + budgetId + "\",\"month\":\"2026-10\"}");
        assertThat(month.get("income").longValue()).isEqualTo(100_000);
        assertThat(month.get("uncategorized").longValue()).isZero();
        Map<String, JsonNode> byName = new java.util.HashMap<>();
        month.get("groups").forEach(g -> g.get("categories").forEach(c -> byName.put(c.get("name").stringValue(), c)));
        assertThat(byName.get("Groceries").get("activity").longValue()).isEqualTo(-6_000);
        // spending 5.00 on the card moves 5.00 into its payment category; the 10.00 payment takes it out again
        assertThat(byName.get("Visa").get("activity").longValue()).isEqualTo(5_000 - 10_000);
    }

    @Test
    void endToEndThroughTheRegistry() {
        loginAsNewUser();
        String budgetId = call("create_budget", "{\"name\":\"Registry\"}").get("id").stringValue();
        JsonNode groups = call("list_categories", "{\"budgetId\":\"" + budgetId + "\"}");
        String groceries = null;
        for (JsonNode g : groups) {
            for (JsonNode c : g.get("categories")) {
                if (c.get("name").stringValue().equals("Groceries")) {
                    groceries = c.get("id").stringValue();
                }
            }
        }
        assertThat(groceries).isNotNull();
        JsonNode account = call("create_account", """
                {"budgetId":"%s","name":"Checking","type":"CHECKING","startingBalance":100000,"startingDate":"2026-10-01"}"""
                .formatted(budgetId));
        String accountId = account.get("id").stringValue();
        assertThat(account.get("balance").longValue()).isEqualTo(100_000);

        String importJson = """
                {"budgetId":"%s","accountId":"%s","transactions":[
                  {"date":"2026-10-02","amount":-12340,"payeeName":"Grocer","categoryId":"%s"},
                  {"date":"2026-10-03","amount":-500,"payeeName":"Kiosk","memo":"gum"}]}"""
                .formatted(budgetId, accountId, groceries);
        JsonNode first = call("import_transactions", importJson);
        assertThat(first.get("imported").intValue()).isEqualTo(2);
        JsonNode second = call("import_transactions", importJson);
        assertThat(second.get("imported").intValue()).isZero();
        assertThat(second.get("duplicates").intValue()).isEqualTo(2);

        JsonNode page = call("list_transactions", """
                {"budgetId":"%s","unapprovedOnly":true}""".formatted(budgetId));
        assertThat(page.get("total").longValue()).isEqualTo(2);
        JsonNode newest = page.get("transactions").get(0);
        assertThat(newest.get("payeeName").stringValue()).isEqualTo("Kiosk");
        assertThat(newest.get("date").stringValue()).isEqualTo("2026-10-03");
        assertThat(newest.get("cleared").stringValue()).isEqualTo("CLEARED");

        JsonNode split = call("update_transaction", """
                {"budgetId":"%s","transactionId":"%s","approved":true,"flagColor":"GREEN",
                 "splits":[{"amount":-300,"categoryId":"%s"},{"amount":-200,"memo":"rest"}]}"""
                .formatted(budgetId, newest.get("id").stringValue(), groceries));
        assertThat(split.get("splits")).hasSize(2);
        assertThat(split.get("flagColor").stringValue()).isEqualTo("GREEN");
        assertThat(split.get("categoryId").isNull()).isTrue();

        JsonNode reconciled = call("reconcile_account", """
                {"budgetId":"%s","accountId":"%s","statementBalance":87160,"statementDate":"2026-10-31"}"""
                .formatted(budgetId, accountId));
        assertThat(reconciled.get("reconciledCount").intValue()).isEqualTo(3);
        assertThat(reconciled.get("adjustmentTransactionId").isNull()).isTrue();

        // invalid input is reported as INVALID_INPUT and nothing is written
        call("create_transaction", """
                {"budgetId":"%s","accountId":"%s","date":"2026-10-04","amount":-1,"payeeName":"Kiosk"}"""
                .formatted(budgetId, accountId));
        assertCode(ActionException.Code.INVALID_INPUT, () -> call("create_transaction", """
                {"budgetId":"%s","accountId":"%s","date":"2026-10-04","amount":-1,
                 "splits":[{"amount":-2}]}""".formatted(budgetId, accountId)));
        assertThat(call("list_transactions", "{\"budgetId\":\"" + budgetId + "\"}").get("total").longValue())
                .isEqualTo(4);
        assertCode(ActionException.Code.INVALID_INPUT,
                () -> call("create_account", "{\"budgetId\":\"" + budgetId + "\",\"name\":\"X\",\"type\":\"BOGUS\"}"));
    }
}
