package io.ynay.payee;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import io.ynay.transaction.SplitInput;
import io.ynay.transaction.TransactionDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayeeActionsTest extends LedgerTestSupport {

    UUID budgetId;
    UUID checking;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
        checking = account(budgetId, "Checking", AccountType.CHECKING, 0).id();
    }

    private PayeeDto payee(String name) {
        return payees.listPayees(new PayeeActions.ListPayeesInput(budgetId)).stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No payee " + name));
    }

    @Test
    void payeesAreCreatedFromNamesAndMatchedCaseInsensitively() {
        TransactionDto first = spend(budgetId, checking, D1, -1_000, "Amazon", null);
        TransactionDto second = spend(budgetId, checking, D1, -2_000, "AMAZON", null);
        assertThat(second.payeeId()).isEqualTo(first.payeeId());
        assertThat(second.payeeName()).isEqualTo("Amazon");
        assertThat(payees.listPayees(new PayeeActions.ListPayeesInput(budgetId))).extracting(PayeeDto::name)
                .containsExactly("Amazon", "Transfer : Checking");
    }

    @Test
    void mergePayeesMovesTransactionsAndSplitLines() {
        TransactionDto a = spend(budgetId, checking, D1, -1_000, "AMZN Mktp US*123", null);
        TransactionDto b = spend(budgetId, checking, D1, -2_000, "Amazon.com", null);
        TransactionDto s = split(budgetId, checking, -3_000, List.of(
                new SplitInput(-1_000L, null, "AMZN Mktp US*123", null),
                new SplitInput(-2_000L, null, "Other shop", null)));
        PayeeDto target = payee("Amazon.com");
        PayeeDto source = payee("AMZN Mktp US*123");

        PayeeDto result = payees.mergePayees(new PayeeActions.MergePayeesInput(budgetId,
                List.of(source.id(), target.id()), target.id()));

        assertThat(result).isEqualTo(target);
        assertThat(txn(budgetId, a.id()).payeeName()).isEqualTo("Amazon.com");
        assertThat(txn(budgetId, b.id()).payeeName()).isEqualTo("Amazon.com");
        assertThat(txn(budgetId, s.id()).splits()).extracting(x -> x.payeeName())
                .containsExactly("Amazon.com", "Other shop");
        assertThat(payees.listPayees(new PayeeActions.ListPayeesInput(budgetId))).extracting(PayeeDto::name)
                .doesNotContain("AMZN Mktp US*123");
    }

    @Test
    void transferPayeesCannotBeRenamedOrMerged() {
        spend(budgetId, checking, D1, -1_000, "Shop", null);
        PayeeDto transferPayee = payee("Transfer : Checking");
        PayeeDto shop = payee("Shop");
        assertCode(Code.INVALID_INPUT, () -> payees.updatePayee(
                new PayeeActions.UpdatePayeeInput(budgetId, transferPayee.id(), "Something")));
        assertCode(Code.INVALID_INPUT, () -> payees.mergePayees(
                new PayeeActions.MergePayeesInput(budgetId, List.of(transferPayee.id()), shop.id())));
        assertCode(Code.INVALID_INPUT, () -> payees.mergePayees(
                new PayeeActions.MergePayeesInput(budgetId, List.of(shop.id()), transferPayee.id())));
    }

    @Test
    void renamePayee() {
        spend(budgetId, checking, D1, -1_000, "Shop", null);
        spend(budgetId, checking, D1, -1_000, "Store", null);
        PayeeDto shop = payee("Shop");
        assertThat(payees.updatePayee(new PayeeActions.UpdatePayeeInput(budgetId, shop.id(), "Corner Shop")).name())
                .isEqualTo("Corner Shop");
        assertCode(Code.CONFLICT, () -> payees.updatePayee(
                new PayeeActions.UpdatePayeeInput(budgetId, shop.id(), "store")));
        assertCode(Code.INVALID_INPUT, () -> payees.updatePayee(
                new PayeeActions.UpdatePayeeInput(budgetId, shop.id(), "Transfer : Fake")));
        // changing only the case of its own name is fine
        assertThat(payees.updatePayee(new PayeeActions.UpdatePayeeInput(budgetId, shop.id(), "CORNER SHOP")).name())
                .isEqualTo("CORNER SHOP");
    }

    @Test
    void payeeIdsFromOtherBudgetsAreRejected() {
        spend(budgetId, checking, D1, -1_000, "Shop", null);
        UUID shop = payee("Shop").id();
        UUID other = newBudget();
        assertCode(Code.NOT_FOUND, () -> payees.updatePayee(new PayeeActions.UpdatePayeeInput(other, shop, "X")));
        UUID otherAccount = account(other, "Other", AccountType.CHECKING, 0).id();
        assertCode(Code.NOT_FOUND, () -> transactions.createTransaction(
                new io.ynay.transaction.TransactionActions.CreateTransactionInput(other, otherAccount, D1, null, -1L, shop,
                        null, null, null, null, null, null, null, null, null)));
    }
}
