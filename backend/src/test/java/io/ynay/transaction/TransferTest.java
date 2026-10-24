package io.ynay.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountActions;
import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class TransferTest extends LedgerTestSupport {

    @Autowired
    TransactionTemplate tx;

    UUID budgetId;
    UUID checking;
    UUID savings;
    UUID card;
    UUID house;
    UUID groceries;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
        checking = account(budgetId, "Checking", AccountType.CHECKING, 1_000_000).id();
        savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        card = account(budgetId, "Visa", AccountType.CREDIT_CARD, -200_000).id();
        house = account(budgetId, "House", AccountType.ASSET, 0).id();
        groceries = category(budgetId, "Groceries").id();
    }

    private TransactionDto other(TransactionDto t) {
        assertThat(t.transferTransactionId()).isNotNull();
        return txn(budgetId, t.transferTransactionId());
    }

    private long count() {
        return jdbc.sql("select count(*) from txn where budget_id = ?").param(budgetId).query(Long.class).single();
    }

    @Test
    void transferBetweenBudgetAccountsCreatesLinkedUncategorizedPair() {
        long before = count();
        TransactionDto out = transfer(budgetId, checking, savings, D1, -100_000, groceries);
        TransactionDto in = other(out);

        assertThat(count()).isEqualTo(before + 2);
        assertThat(out.transferAccountId()).isEqualTo(savings);
        assertThat(out.payeeName()).isEqualTo("Transfer : Savings");
        assertThat(out.categoryId()).as("on-budget transfers carry no category").isNull();
        assertThat(in.accountId()).isEqualTo(savings);
        assertThat(in.amount()).isEqualTo(100_000);
        assertThat(in.date()).isEqualTo(D1);
        assertThat(in.transferAccountId()).isEqualTo(checking);
        assertThat(in.transferTransactionId()).isEqualTo(out.id());
        assertThat(in.payeeName()).isEqualTo("Transfer : Checking");
        assertThat(in.categoryId()).isNull();
        assertThat(in.cleared()).isEqualTo(Cleared.UNCLEARED);

        assertThat(accountById(budgetId, checking).balance()).isEqualTo(900_000);
        assertThat(accountById(budgetId, savings).balance()).isEqualTo(100_000);
    }

    @Test
    void transferPayeeNameOrPayeeIdAlsoMakesATransfer() {
        TransactionDto byName = spend(budgetId, checking, D1, -50_000, "transfer : visa", null);
        assertThat(byName.transferAccountId()).isEqualTo(card);
        assertThat(other(byName).amount()).isEqualTo(50_000);

        UUID payeeId = other(byName).payeeId(); // "Transfer : Checking"
        TransactionDto byId = transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId,
                savings, D1, -1_000L, payeeId, null, null, null, null, null, null, null, null, null));
        assertThat(byId.transferAccountId()).isEqualTo(checking);
        assertThat(other(byId).accountId()).isEqualTo(checking);

        assertCode(Code.INVALID_INPUT, () -> spend(budgetId, checking, D1, -1, "Transfer : Nowhere", null));
        assertCode(Code.INVALID_INPUT, () -> transfer(budgetId, checking, checking, D1, -1, null));
        assertCode(Code.INVALID_INPUT, () -> transactions.createTransaction(
                new TransactionActions.CreateTransactionInput(budgetId, checking, D1, -1L, null, "Transfer : Visa",
                        null, null, null, null, null, savings, null, null)));
    }

    @Test
    void transferToTrackingAccountKeepsCategoryOnBudgetSide() {
        TransactionDto out = transfer(budgetId, checking, house, D1, -300_000, groceries);
        assertThat(out.categoryId()).isEqualTo(groceries);
        assertThat(other(out).categoryId()).isNull();

        // entered from the tracking side, the category still lands on the budget side
        TransactionDto fromTracking = transfer(budgetId, house, checking, D1, -20_000, groceries);
        assertThat(fromTracking.categoryId()).isNull();
        assertThat(other(fromTracking).categoryId()).isEqualTo(groceries);
        assertThat(other(fromTracking).amount()).isEqualTo(20_000);
    }

    @Test
    void updatingAmountOrDateUpdatesTheOtherSide() {
        TransactionDto out = transfer(budgetId, checking, savings, D1, -100_000, null);
        updateTxn(budgetId, out.id(), x -> {
            x.amount = -120_000L;
            x.date = D1.plusDays(3);
            x.memo = "rainy day";
            x.cleared = Cleared.CLEARED;
        });
        TransactionDto in = other(txn(budgetId, out.id()));
        assertThat(in.amount()).isEqualTo(120_000);
        assertThat(in.date()).isEqualTo(D1.plusDays(3));
        assertThat(in.cleared()).as("cleared is per side").isEqualTo(Cleared.UNCLEARED);

        // editing from the other side works the same way
        updateTxn(budgetId, in.id(), x -> x.amount = 90_000L);
        assertThat(txn(budgetId, out.id()).amount()).isEqualTo(-90_000);
        assertThat(accountById(budgetId, savings).balance()).isEqualTo(90_000);
    }

    @Test
    void movingTransferSideToAnotherAccountRelinksTheOtherSide() {
        TransactionDto out = transfer(budgetId, checking, savings, D1, -100_000, null);
        UUID cash = account(budgetId, "Wallet", AccountType.CASH, 0).id();
        TransactionDto moved = updateTxn(budgetId, out.id(), x -> x.accountId = cash);
        TransactionDto in = other(moved);
        assertThat(in.id()).isEqualTo(out.transferTransactionId());
        assertThat(in.transferAccountId()).isEqualTo(cash);
        assertThat(in.payeeName()).isEqualTo("Transfer : Wallet");

        TransactionDto retargeted = updateTxn(budgetId, out.id(), x -> x.transferAccountId = card);
        assertThat(retargeted.transferAccountId()).isEqualTo(card);
        assertThat(other(retargeted).accountId()).isEqualTo(card);
        assertThat(other(retargeted).amount()).isEqualTo(100_000);
        assertThat(jdbc.sql("select count(*) from txn where account_id = ?").param(savings).query(Long.class)
                .single()).isZero();

        assertCode(Code.INVALID_INPUT, () -> updateTxn(budgetId, out.id(), x -> x.accountId = card));
    }

    @Test
    void deletingEitherSideDeletesBoth() {
        TransactionDto out = transfer(budgetId, checking, savings, D1, -100_000, null);
        long before = count();
        transactions.deleteTransaction(new TransactionActions.DeleteTransactionInput(budgetId, out.transferTransactionId()));
        assertThat(count()).isEqualTo(before - 2);
        assertCode(Code.NOT_FOUND, () -> txn(budgetId, out.id()));
    }

    @Test
    void convertingToAndFromTransfer() {
        TransactionDto t = spend(budgetId, checking, D1, -40_000, "Bank", groceries);
        long before = count();

        TransactionDto asTransfer = updateTxn(budgetId, t.id(), x -> x.transferAccountId = savings);
        assertThat(count()).isEqualTo(before + 1);
        assertThat(asTransfer.transferAccountId()).isEqualTo(savings);
        assertThat(asTransfer.payeeName()).isEqualTo("Transfer : Savings");
        assertThat(asTransfer.categoryId()).isNull();
        assertThat(other(asTransfer).amount()).isEqualTo(40_000);

        TransactionDto back = updateTxn(budgetId, t.id(), x -> {
            x.payeeName = "Bank";
            x.categoryId = groceries;
        });
        assertThat(count()).isEqualTo(before);
        assertThat(back.transferAccountId()).isNull();
        assertThat(back.transferTransactionId()).isNull();
        assertThat(back.payeeName()).isEqualTo("Bank");
        assertThat(back.categoryId()).isEqualTo(groceries);
        assertThat(accountById(budgetId, savings).balance()).isZero();

        // a split can't become a transfer without being un-split
        TransactionDto s = split(budgetId, checking, -2_000, List.of(
                new SplitInput(-1_000L, groceries, null, null), new SplitInput(-1_000L, null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> updateTxn(budgetId, s.id(), x -> x.transferAccountId = savings));
        TransactionDto unsplit = updateTxn(budgetId, s.id(), x -> {
            x.transferAccountId = savings;
            x.splits = List.of();
        });
        assertThat(unsplit.transferAccountId()).isEqualTo(savings);
        assertThat(unsplit.splits()).isEmpty();
    }

    @Test
    void worksInsideOneDatabaseTransactionLikeTheActionRegistry() {
        TransactionDto out = tx.execute(s -> {
            TransactionDto t = transfer(budgetId, checking, savings, D1, -10_000, null);
            updateTxn(budgetId, t.id(), x -> x.transferAccountId = card);
            return txn(budgetId, t.id());
        });
        assertThat(other(out).accountId()).isEqualTo(card);
        tx.executeWithoutResult(s -> transactions.deleteTransaction(
                new TransactionActions.DeleteTransactionInput(budgetId, out.id())));
        assertCode(Code.NOT_FOUND, () -> txn(budgetId, out.transferTransactionId()));
    }

    @Test
    void creditCardPaymentIsATransferIntoTheCard() {
        TransactionDto payment = transfer(budgetId, checking, card, D1, -200_000, null);
        TransactionDto onCard = other(payment);
        assertThat(onCard.amount()).isEqualTo(200_000);
        assertThat(onCard.categoryId()).isNull();
        assertThat(accountById(budgetId, card).balance()).isZero();
        // card can be closed now
        accounts.updateAccount(new AccountActions.UpdateAccountInput(budgetId, card, null, null, true));
        assertCode(Code.INVALID_INPUT, () -> transfer(budgetId, checking, card, D1, -1, null));
    }
}
