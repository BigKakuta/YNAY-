package io.ynay.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountType;
import io.ynay.category.CategoryActions;
import io.ynay.category.CategoryGroupDto;
import io.ynay.payee.PayeeActions;
import io.ynay.transaction.SplitInput;
import io.ynay.transaction.TransactionActions;
import io.ynay.transaction.TransactionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pins the JSON shape of the ledger DTOs to the TypeScript types in ARCHITECTURE.md. */
class LedgerJsonShapeTest extends LedgerTestSupport {

    @Autowired
    JsonMapper json;

    private List<String> fields(Object value) {
        JsonNode node = json.valueToTree(value);
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void dtoFieldNamesMatchTheContract() {
        loginAsNewUser();
        BudgetDto budget = budgets.createBudget(new BudgetActions.CreateBudgetInput("Shape", null, null));
        UUID budgetId = budget.id();
        assertThat(fields(budget)).containsExactlyInAnyOrder("id", "name", "currencyCode", "role", "createdAt");
        JsonNode budgetJson = json.valueToTree(budget);
        assertThat(budgetJson.get("role").asString()).isEqualTo("OWNER");
        assertThat(budgetJson.get("createdAt").isString()).isTrue();

        assertThat(fields(budgets.listBudgetMembers(new BudgetActions.ListBudgetMembersInput(budgetId)).getFirst()))
                .containsExactlyInAnyOrder("userId", "username", "displayName", "role");

        var account = account(budgetId, "Visa", AccountType.CREDIT_CARD, -1000);
        assertThat(fields(account)).containsExactlyInAnyOrder("id", "budgetId", "name", "type", "onBudget", "closed",
                "note", "sortOrder", "balance", "clearedBalance", "unclearedBalance");

        CategoryGroupDto group = categories.listCategories(new CategoryActions.ListCategoriesInput(budgetId, true))
                .get(1);
        assertThat(fields(group)).containsExactlyInAnyOrder("id", "name", "hidden", "sortOrder", "systemKind",
                "categories");
        assertThat(fields(group.categories().getFirst())).containsExactlyInAnyOrder("id", "groupId", "name", "hidden",
                "note", "sortOrder", "systemKind", "linkedAccountId", "target");

        assertThat(fields(payees.listPayees(new PayeeActions.ListPayeesInput(budgetId)).getFirst()))
                .containsExactlyInAnyOrder("id", "name", "transferAccountId");

        UUID groceries = category(budgetId, "Groceries").id();
        TransactionDto t = split(budgetId, account.id(), -2_000, List.of(
                new SplitInput(-1_000L, groceries, null, null), new SplitInput(-1_000L, null, "Other", "m")));
        JsonNode txnJson = json.valueToTree(t);
        assertThat(fields(t)).containsExactlyInAnyOrder("id", "budgetId", "accountId", "accountName", "date", "time",
                "amount", "payeeId", "payeeName", "categoryId", "categoryName", "memo", "cleared", "approved", "flagColor",
                "transferAccountId", "transferTransactionId", "importId", "splits");
        assertThat(txnJson.get("date").asString()).isEqualTo("2026-10-01");
        assertThat(txnJson.get("amount").asLong()).isEqualTo(-2_000);
        assertThat(fields(t.splits().getFirst())).containsExactlyInAnyOrder("id", "amount", "categoryId",
                "categoryName", "payeeId", "payeeName", "memo");

        assertThat(fields(transactions.listTransactions(new TransactionActions.ListTransactionsInput(budgetId, null,
                null, null, null, null, null, null, null, null, null)))).containsExactlyInAnyOrder("transactions", "total");
        assertThat(fields(transactions.importTransactions(new TransactionActions.ImportTransactionsInput(budgetId,
                account.id(), List.of(), null)))).containsExactlyInAnyOrder("imported", "duplicates", "transactionIds",
                "duplicateImportIds");
        assertThat(fields(transactions.reconcileAccount(new TransactionActions.ReconcileAccountInput(budgetId,
                account.id(), -1000L, null)))).containsExactlyInAnyOrder("reconciledCount", "adjustmentTransactionId");
    }
}
