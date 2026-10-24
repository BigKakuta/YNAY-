package io.ynay.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import io.ynay.category.CategoryDto;
import io.ynay.category.CategoryGroupDto;
import io.ynay.payee.PayeeActions;
import io.ynay.payee.PayeeDto;
import io.ynay.transaction.Cleared;
import io.ynay.transaction.TransactionActions;
import io.ynay.transaction.TransactionDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountActionsTest extends LedgerTestSupport {

    UUID budgetId;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
    }

    private List<TransactionDto> txnsOf(UUID accountId) {
        return transactions.listTransactions(new TransactionActions.ListTransactionsInput(budgetId, accountId, null,
                null, null, null, null, null, null, null, null)).transactions();
    }

    private CategoryGroupDto ccGroup() {
        return allGroups(budgetId).stream()
                .filter(g -> g.systemKind() == CategoryGroupDto.SystemKind.CREDIT_CARD_PAYMENTS)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void closingAccountWithBalanceIsRejectedWithReadableAmount() {
        AccountDto checking = account(budgetId, "Checking", AccountType.CHECKING, 300_200_000);

        assertThatThrownBy(() -> accounts.updateAccount(new AccountActions.UpdateAccountInput(budgetId,
                checking.id(), null, null, true)))
                .isInstanceOf(ActionException.class)
                .hasMessageContaining("has a balance of 300200.00 (300200000 milliunits)");
    }

    @Test
    void checkingAccountWithStartingBalanceCountsAsReadyToAssignIncome() {
        AccountDto checking = account(budgetId, "Checking", AccountType.CHECKING, 1_500_000);

        assertThat(checking.onBudget()).isTrue();
        assertThat(checking.closed()).isFalse();
        assertThat(checking.balance()).isEqualTo(1_500_000);
        assertThat(checking.clearedBalance()).isEqualTo(1_500_000);
        assertThat(checking.unclearedBalance()).isZero();

        assertThat(txnsOf(checking.id())).singleElement().satisfies(t -> {
            assertThat(t.payeeName()).isEqualTo("Starting Balance");
            assertThat(t.categoryId()).isEqualTo(rta(budgetId));
            assertThat(t.cleared()).isEqualTo(Cleared.CLEARED);
            assertThat(t.date()).isEqualTo(D1);
            assertThat(t.approved()).isTrue();
        });

        List<PayeeDto> payeeList = payees.listPayees(new PayeeActions.ListPayeesInput(budgetId));
        assertThat(payeeList).extracting(PayeeDto::name).contains("Transfer : Checking", "Starting Balance");
        assertThat(payeeList).filteredOn(p -> p.name().equals("Transfer : Checking"))
                .singleElement().satisfies(p -> assertThat(p.transferAccountId()).isEqualTo(checking.id()));
        assertThat(ccGroup().categories()).isEmpty();
    }

    @Test
    void zeroStartingBalanceCreatesNoTransaction() {
        AccountDto cash = account(budgetId, "Wallet", AccountType.CASH, 0);
        assertThat(txnsOf(cash.id())).isEmpty();
    }

    @Test
    void creditCardGetsPaymentCategoryAndUncategorizedStartingBalance() {
        AccountDto card = account(budgetId, "Visa", AccountType.CREDIT_CARD, -250_000);

        assertThat(card.onBudget()).isTrue();
        assertThat(card.balance()).isEqualTo(-250_000);
        assertThat(ccGroup().categories()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Visa");
            assertThat(c.systemKind()).isEqualTo(CategoryDto.SystemKind.CREDIT_CARD_PAYMENT);
            assertThat(c.linkedAccountId()).isEqualTo(card.id());
        });
        assertThat(txnsOf(card.id())).singleElement().satisfies(t -> {
            assertThat(t.payeeName()).isEqualTo("Starting Balance");
            assertThat(t.categoryId()).isNull();
        });

        // line of credit too, but not a tracking credit card
        account(budgetId, "HELOC", AccountType.LINE_OF_CREDIT, 0);
        accounts.createAccount(new AccountActions.CreateAccountInput(budgetId, "Store card", AccountType.CREDIT_CARD,
                false, -1000L, D1, null));
        assertThat(ccGroup().categories()).extracting(CategoryDto::name).containsExactlyInAnyOrder("Visa", "HELOC");
    }

    @Test
    void creditCardLifecycleRenameCloseReopenDelete() {
        AccountDto card = account(budgetId, "Visa", AccountType.CREDIT_CARD, 0);
        AccountDto renamed = accounts.updateAccount(new AccountActions.UpdateAccountInput(budgetId, card.id(),
                "Visa Gold", "Main card", null));
        assertThat(renamed.name()).isEqualTo("Visa Gold");
        assertThat(renamed.note()).isEqualTo("Main card");
        assertThat(ccGroup().categories()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("Visa Gold"));
        assertThat(payees.listPayees(new PayeeActions.ListPayeesInput(budgetId))).extracting(PayeeDto::name)
                .contains("Transfer : Visa Gold")
                .doesNotContain("Transfer : Visa");

        AccountDto closed = accounts.updateAccount(new AccountActions.UpdateAccountInput(budgetId, card.id(), null,
                null, true));
        assertThat(closed.closed()).isTrue();
        assertThat(ccGroup().categories()).singleElement().satisfies(c -> assertThat(c.hidden()).isTrue());
        assertThat(accounts.listAccounts(new AccountActions.ListAccountsInput(budgetId, null))).isEmpty();
        assertThat(accounts.listAccounts(new AccountActions.ListAccountsInput(budgetId, true))).hasSize(1);
        assertCode(Code.INVALID_INPUT, () -> spend(budgetId, card.id(), D1, -100, "Shop", null));

        accounts.updateAccount(new AccountActions.UpdateAccountInput(budgetId, card.id(), null, null, false));
        assertThat(ccGroup().categories()).singleElement().satisfies(c -> assertThat(c.hidden()).isFalse());

        accounts.deleteAccount(new AccountActions.DeleteAccountInput(budgetId, card.id()));
        assertThat(ccGroup().categories()).isEmpty();
        assertThat(payees.listPayees(new PayeeActions.ListPayeesInput(budgetId))).extracting(PayeeDto::name)
                .doesNotContain("Transfer : Visa Gold");
    }

    @Test
    void cannotCloseAccountWithBalance() {
        AccountDto checking = account(budgetId, "Checking", AccountType.CHECKING, 1000);
        assertCode(Code.INVALID_INPUT, () -> accounts.updateAccount(
                new AccountActions.UpdateAccountInput(budgetId, checking.id(), null, null, true)));
    }

    @Test
    void trackingAccountsDefaultOffBudgetWithUncategorizedStartingBalance() {
        AccountDto house = account(budgetId, "House", AccountType.ASSET, 300_000_000);
        assertThat(house.onBudget()).isFalse();
        assertThat(txnsOf(house.id())).singleElement().satisfies(t -> assertThat(t.categoryId()).isNull());
        AccountDto mortgage = account(budgetId, "Mortgage", AccountType.MORTGAGE, -200_000_000);
        assertThat(mortgage.onBudget()).isFalse();
        assertThat(ccGroup().categories()).isEmpty();
    }

    @Test
    void accountNamesAreUniqueAndValidated() {
        account(budgetId, "Checking", AccountType.CHECKING, 0);
        assertCode(Code.CONFLICT, () -> account(budgetId, "checking", AccountType.SAVINGS, 0));
        assertCode(Code.INVALID_INPUT, () -> account(budgetId, "  ", AccountType.SAVINGS, 0));
        assertCode(Code.INVALID_INPUT, () -> accounts.createAccount(
                new AccountActions.CreateAccountInput(budgetId, "No type", null, null, null, null, null)));
        AccountDto savings = account(budgetId, "Savings", AccountType.SAVINGS, 0);
        assertCode(Code.CONFLICT, () -> accounts.updateAccount(
                new AccountActions.UpdateAccountInput(budgetId, savings.id(), "Checking", null, null)));
    }

    @Test
    void balancesSplitIntoClearedAndUncleared() {
        AccountDto checking = account(budgetId, "Checking", AccountType.CHECKING, 100_000);
        spend(budgetId, checking.id(), D1, -20_000, "Shop", null);
        TransactionDto cleared = spend(budgetId, checking.id(), D1, -5_000, "Cafe", null);
        transactions.setTransactionsCleared(new TransactionActions.SetTransactionsClearedInput(budgetId,
                List.of(cleared.id()), Cleared.CLEARED));

        AccountDto after = accountById(budgetId, checking.id());
        assertThat(after.balance()).isEqualTo(75_000);
        assertThat(after.clearedBalance()).isEqualTo(95_000);
        assertThat(after.unclearedBalance()).isEqualTo(-20_000);
    }

    @Test
    void deletingAccountKeepsOtherSideOfTransfersAsRegularTransactions() {
        AccountDto checking = account(budgetId, "Checking", AccountType.CHECKING, 100_000);
        AccountDto savings = account(budgetId, "Savings", AccountType.SAVINGS, 0);
        transfer(budgetId, checking.id(), savings.id(), D1, -30_000, null);

        accounts.deleteAccount(new AccountActions.DeleteAccountInput(budgetId, savings.id()));

        assertThat(accounts.listAccounts(new AccountActions.ListAccountsInput(budgetId, true)))
                .extracting(AccountDto::name).containsExactly("Checking");
        assertThat(accountById(budgetId, checking.id()).balance()).isEqualTo(70_000);
        assertThat(txnsOf(checking.id())).filteredOn(t -> t.amount() == -30_000).singleElement().satisfies(t -> {
            assertThat(t.transferAccountId()).isNull();
            assertThat(t.transferTransactionId()).isNull();
            assertThat(t.payeeName()).isEqualTo("Savings");
        });
    }
}
