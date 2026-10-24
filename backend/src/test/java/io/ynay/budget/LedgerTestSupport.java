package io.ynay.budget;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.account.AccountActions;
import io.ynay.account.AccountDto;
import io.ynay.account.AccountType;
import io.ynay.action.ActionException;
import io.ynay.category.CategoryActions;
import io.ynay.category.CategoryDto;
import io.ynay.category.CategoryGroupDto;
import io.ynay.payee.PayeeActions;
import io.ynay.support.IntegrationTest;
import io.ynay.transaction.SplitInput;
import io.ynay.transaction.TransactionActions;
import io.ynay.transaction.TransactionDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared fixtures for the ledger integration tests (budgets, accounts, categories, transactions). */
public abstract class LedgerTestSupport extends IntegrationTest {

    protected static final LocalDate D1 = LocalDate.of(2026, 10, 1);

    @Autowired
    protected BudgetActions budgets;
    @Autowired
    protected AccountActions accounts;
    @Autowired
    protected CategoryActions categories;
    @Autowired
    protected PayeeActions payees;
    @Autowired
    protected TransactionActions transactions;

    protected UUID newBudget() {
        return budgets.createBudget(new BudgetActions.CreateBudgetInput("Test budget", null, null)).id();
    }

    protected AccountDto account(UUID budgetId, String name, AccountType type, long startingBalance) {
        return accounts.createAccount(new AccountActions.CreateAccountInput(budgetId, name, type, null,
                startingBalance, D1, null));
    }

    protected AccountDto accountById(UUID budgetId, UUID accountId) {
        return accounts.listAccounts(new AccountActions.ListAccountsInput(budgetId, true)).stream()
                .filter(a -> a.id().equals(accountId))
                .findFirst()
                .orElseThrow();
    }

    protected List<CategoryGroupDto> allGroups(UUID budgetId) {
        return categories.listCategories(new CategoryActions.ListCategoriesInput(budgetId, true));
    }

    protected CategoryDto category(UUID budgetId, String name) {
        return allGroups(budgetId).stream()
                .flatMap(g -> g.categories().stream())
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No category " + name));
    }

    protected UUID rta(UUID budgetId) {
        return category(budgetId, "Inflow: Ready to Assign").id();
    }

    protected TransactionDto spend(UUID budgetId, UUID accountId, LocalDate date, long amount, String payee,
                                   UUID categoryId) {
        return transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId, accountId, date, null,
                amount, null, payee, categoryId, null, null, null, null, null, null, null));
    }

    protected TransactionDto transfer(UUID budgetId, UUID fromAccountId, UUID toAccountId, LocalDate date, long amount,
                                      UUID categoryId) {
        return transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId, fromAccountId,
                date, null, amount, null, null, categoryId, null, null, null, null, toAccountId, null, null));
    }

    protected TransactionDto split(UUID budgetId, UUID accountId, long amount, List<SplitInput> splits) {
        return transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId, accountId, D1, null,
                amount, null, "Costco", null, null, null, null, null, null, splits, null));
    }

    protected TransactionDto txn(UUID budgetId, UUID transactionId) {
        return transactions.getTransaction(new TransactionActions.GetTransactionInput(budgetId, transactionId));
    }

    /** Mutable mirror of {@link TransactionActions.UpdateTransactionInput} for concise tests. */
    protected static final class Upd {
        public UUID accountId;
        public LocalDate date;
        public String time;
        public Long amount;
        public UUID payeeId;
        public String payeeName;
        public UUID categoryId;
        public String memo;
        public io.ynay.transaction.Cleared cleared;
        public Boolean approved;
        public io.ynay.transaction.FlagColor flagColor;
        public UUID transferAccountId;
        public List<SplitInput> splits;
        public String importId;
        public Boolean clearCategory;
    }

    protected TransactionDto updateTxn(UUID budgetId, UUID transactionId, java.util.function.Consumer<Upd> change) {
        Upd u = new Upd();
        change.accept(u);
        return transactions.updateTransaction(new TransactionActions.UpdateTransactionInput(budgetId, transactionId,
                u.accountId, u.date, u.time, u.amount, u.payeeId, u.payeeName, u.categoryId, u.memo, u.cleared, u.approved,
                u.flagColor, u.transferAccountId, u.splits, u.importId, u.clearCategory));
    }

    protected static void assertCode(ActionException.Code code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ActionException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((ActionException) e).code())
                        .as(e.getMessage())
                        .isEqualTo(code));
    }
}
