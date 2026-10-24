package io.ynay.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionActionsTest extends LedgerTestSupport {

    UUID budgetId;
    UUID checking;
    UUID groceries;
    UUID dining;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
        checking = account(budgetId, "Checking", AccountType.CHECKING, 0).id();
        groceries = category(budgetId, "Groceries").id();
        dining = category(budgetId, "Dining Out").id();
    }

    private TransactionService.Page list(UUID accountId, UUID categoryId, UUID payeeId, LocalDate since,
                                         LocalDate until, String search, Boolean unapproved, Integer limit,
                                         Integer offset) {
        return transactions.listTransactions(new TransactionActions.ListTransactionsInput(budgetId, accountId,
                categoryId, payeeId, since, until, search, unapproved, limit, offset));
    }

    @Test
    void createAndGetTransaction() {
        TransactionDto t = transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId,
                checking, D1, -12_340L, null, "  Whole Foods ", groceries, "weekly shop", Cleared.CLEARED, null,
                FlagColor.RED, null, null, "bank-1"));

        assertThat(t.budgetId()).isEqualTo(budgetId);
        assertThat(t.accountId()).isEqualTo(checking);
        assertThat(t.accountName()).isEqualTo("Checking");
        assertThat(t.date()).isEqualTo(D1);
        assertThat(t.amount()).isEqualTo(-12_340);
        assertThat(t.payeeName()).isEqualTo("Whole Foods");
        assertThat(t.categoryId()).isEqualTo(groceries);
        assertThat(t.categoryName()).isEqualTo("Groceries");
        assertThat(t.memo()).isEqualTo("weekly shop");
        assertThat(t.cleared()).isEqualTo(Cleared.CLEARED);
        assertThat(t.approved()).isTrue();
        assertThat(t.flagColor()).isEqualTo("RED");
        assertThat(t.transferAccountId()).isNull();
        assertThat(t.importId()).isEqualTo("bank-1");
        assertThat(t.splits()).isEmpty();
        assertThat(txn(budgetId, t.id())).isEqualTo(t);

        assertCode(Code.CONFLICT, () -> transactions.createTransaction(new TransactionActions.CreateTransactionInput(
                budgetId, checking, D1, -1L, null, null, null, null, null, null, null, null, null, "bank-1")));
        assertCode(Code.INVALID_INPUT, () -> transactions.createTransaction(
                new TransactionActions.CreateTransactionInput(budgetId, checking, null, -1L, null, null, null, null,
                        null, null, null, null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> transactions.createTransaction(
                new TransactionActions.CreateTransactionInput(budgetId, checking, D1, null, null, null, null, null,
                        null, null, null, null, null, null)));
        assertCode(Code.NOT_FOUND, () -> txn(budgetId, UUID.randomUUID()));
    }

    @Test
    void updateTransactionFieldsAndClearing() {
        TransactionDto t = spend(budgetId, checking, D1, -5_000, "Cafe", dining);
        TransactionDto u = updateTxn(budgetId, t.id(), x -> {
            x.amount = -6_000L;
            x.date = D1.plusDays(2);
            x.memo = "latte";
            x.flagColor = FlagColor.BLUE;
            x.approved = false;
            x.cleared = Cleared.CLEARED;
        });
        assertThat(u.amount()).isEqualTo(-6_000);
        assertThat(u.date()).isEqualTo(D1.plusDays(2));
        assertThat(u.memo()).isEqualTo("latte");
        assertThat(u.flagColor()).isEqualTo("BLUE");
        assertThat(u.approved()).isFalse();
        assertThat(u.cleared()).isEqualTo(Cleared.CLEARED);
        assertThat(u.categoryId()).isEqualTo(dining);
        assertThat(u.payeeName()).isEqualTo("Cafe");

        u = updateTxn(budgetId, t.id(), x -> {
            x.memo = "";
            x.flagColor = FlagColor.NONE;
            x.payeeName = "";
            x.clearCategory = true;
        });
        assertThat(u.memo()).isNull();
        assertThat(u.flagColor()).isNull();
        assertThat(u.payeeId()).isNull();
        assertThat(u.categoryId()).isNull();
        assertThat(u.amount()).isEqualTo(-6_000);

        u = updateTxn(budgetId, t.id(), x -> {
            x.payeeName = "Bakery";
            x.categoryId = groceries;
        });
        assertThat(u.payeeName()).isEqualTo("Bakery");
        assertThat(u.categoryId()).isEqualTo(groceries);

        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        u = updateTxn(budgetId, t.id(), x -> x.accountId = savings);
        assertThat(u.accountId()).isEqualTo(savings);
        assertThat(u.accountName()).isEqualTo("Savings");
    }

    @Test
    void deleteTransaction() {
        TransactionDto t = spend(budgetId, checking, D1, -5_000, "Cafe", dining);
        assertThat(transactions.deleteTransaction(new TransactionActions.DeleteTransactionInput(budgetId, t.id())))
                .containsEntry("deleted", true);
        assertCode(Code.NOT_FOUND, () -> txn(budgetId, t.id()));
        assertCode(Code.NOT_FOUND, () -> transactions.deleteTransaction(
                new TransactionActions.DeleteTransactionInput(budgetId, t.id())));
    }

    @Test
    void memorizedCategoryIsAppliedWhenCategoryOmitted() {
        spend(budgetId, checking, D1, -5_000, "Cafe", groceries);
        spend(budgetId, checking, D1.plusDays(1), -5_000, "Cafe", dining);
        TransactionDto next = spend(budgetId, checking, D1.plusDays(2), -4_000, "cafe", null);
        assertThat(next.categoryId()).isEqualTo(dining);

        // unknown payee: stays uncategorized
        assertThat(spend(budgetId, checking, D1, -1_000, "New place", null).categoryId()).isNull();

        // not applied on tracking accounts (which are never categorized)
        UUID asset = account(budgetId, "House", AccountType.ASSET, 0).id();
        assertThat(spend(budgetId, asset, D1, -1_000, "Cafe", null).categoryId()).isNull();
        assertThat(spend(budgetId, asset, D1, -1_000, "Cafe", groceries).categoryId()).isNull();
    }

    @Test
    void splitTransactions() {
        TransactionDto s = split(budgetId, checking, -10_000, List.of(
                new SplitInput(-7_000L, groceries, null, "food"),
                new SplitInput(-3_000L, dining, "Food court", null)));
        assertThat(s.categoryId()).isNull();
        assertThat(s.splits()).hasSize(2);
        assertThat(s.splits().get(0).amount()).isEqualTo(-7_000);
        assertThat(s.splits().get(0).categoryName()).isEqualTo("Groceries");
        assertThat(s.splits().get(0).memo()).isEqualTo("food");
        assertThat(s.splits().get(0).payeeId()).isNull();
        assertThat(s.splits().get(1).payeeName()).isEqualTo("Food court");
        assertThat(jdbc.sql("select is_split from txn where id = ?").param(s.id()).query(Boolean.class).single())
                .isTrue();

        // amounts must sum to the parent; no parent category; no transfers
        assertCode(Code.INVALID_INPUT, () -> split(budgetId, checking, -10_000, List.of(
                new SplitInput(-7_000L, groceries, null, null), new SplitInput(-2_000L, dining, null, null))));
        assertCode(Code.INVALID_INPUT, () -> transactions.createTransaction(new TransactionActions.CreateTransactionInput(
                budgetId, checking, D1, -10_000L, null, null, groceries, null, null, null, null, null,
                List.of(new SplitInput(-10_000L, groceries, null, null)), null)));
        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        assertCode(Code.INVALID_INPUT, () -> transactions.createTransaction(new TransactionActions.CreateTransactionInput(
                budgetId, checking, D1, -10_000L, null, null, null, null, null, null, null, savings,
                List.of(new SplitInput(-10_000L, groceries, null, null)), null)));
        assertCode(Code.INVALID_INPUT, () -> split(budgetId, checking, -10_000, List.of(
                new SplitInput(-10_000L, null, "Transfer : Savings", null))));
        assertCode(Code.INVALID_INPUT, () -> split(budgetId, checking, -10_000, List.of(
                new SplitInput(null, groceries, null, null))));

        // changing the amount needs matching splits
        assertCode(Code.INVALID_INPUT, () -> updateTxn(budgetId, s.id(), x -> x.amount = -11_000L));
        TransactionDto u = updateTxn(budgetId, s.id(), x -> {
            x.amount = -11_000L;
            x.splits = List.of(new SplitInput(-8_000L, groceries, null, null),
                    new SplitInput(-3_000L, dining, null, null));
        });
        assertThat(u.splits()).extracting(SplitDto::amount).containsExactly(-8_000L, -3_000L);
        // other fields can change without re-sending splits
        u = updateTxn(budgetId, s.id(), x -> x.memo = "monthly");
        assertThat(u.splits()).hasSize(2);
        assertCode(Code.INVALID_INPUT, () -> updateTxn(budgetId, s.id(), x -> x.categoryId = groceries));

        // un-split
        u = updateTxn(budgetId, s.id(), x -> {
            x.splits = List.of();
            x.categoryId = groceries;
        });
        assertThat(u.splits()).isEmpty();
        assertThat(u.categoryId()).isEqualTo(groceries);
        assertThat(jdbc.sql("select count(*) from txn_split where transaction_id = ?").param(s.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void foreignSplitCategoryIsRejected() {
        UUID other = newBudget();
        UUID foreign = category(other, "Groceries").id();
        assertCode(Code.NOT_FOUND, () -> split(budgetId, checking, -1_000, List.of(
                new SplitInput(-1_000L, foreign, null, null))));
    }

    @Test
    void listTransactionsFiltersAndPaginates() {
        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        for (int i = 1; i <= 5; i++) {
            spend(budgetId, checking, D1.plusDays(i), -1_000L * i, "Shop " + i, groceries);
        }
        TransactionDto cafe = spend(budgetId, savings, D1.plusDays(10), -500, "Cafe", dining);
        TransactionDto s = split(budgetId, checking, -3_000, List.of(
                new SplitInput(-1_000L, dining, null, "pizza"), new SplitInput(-2_000L, groceries, null, null)));
        updateTxn(budgetId, cafe.id(), x -> x.approved = false);

        TransactionService.Page all = list(null, null, null, null, null, null, null, null, null);
        assertThat(all.total()).isEqualTo(7);
        assertThat(all.transactions().getFirst().id()).isEqualTo(cafe.id()); // newest first
        assertThat(all.transactions()).extracting(TransactionDto::date).isSortedAccordingTo((a, b) -> b.compareTo(a));

        assertThat(list(savings, null, null, null, null, null, null, null, null).transactions())
                .extracting(TransactionDto::id).containsExactly(cafe.id());
        assertThat(list(null, dining, null, null, null, null, null, null, null).transactions())
                .extracting(TransactionDto::id).containsExactlyInAnyOrder(cafe.id(), s.id());
        assertThat(list(null, null, cafe.payeeId(), null, null, null, null, null, null).total()).isEqualTo(1);
        assertThat(list(null, null, null, D1.plusDays(2), D1.plusDays(4), null, null, null, null).total())
                .isEqualTo(3);
        assertThat(list(null, null, null, null, null, "shop 3", null, null, null).transactions())
                .singleElement().satisfies(t -> assertThat(t.payeeName()).isEqualTo("Shop 3"));
        assertThat(list(null, null, null, null, null, "PIZZA", null, null, null).transactions())
                .extracting(TransactionDto::id).containsExactly(s.id());
        assertThat(list(null, null, null, null, null, "100%_", null, null, null).total()).isZero();
        assertThat(list(null, null, null, null, null, null, true, null, null).transactions())
                .extracting(TransactionDto::id).containsExactly(cafe.id());

        TransactionService.Page page1 = list(null, null, null, null, null, null, null, 3, 0);
        TransactionService.Page page3 = list(null, null, null, null, null, null, null, 3, 6);
        assertThat(page1.total()).isEqualTo(7);
        assertThat(page1.transactions()).hasSize(3);
        assertThat(page3.transactions()).hasSize(1);
        assertThat(page3.transactions().getFirst().id()).isEqualTo(all.transactions().getLast().id());
        // split lines are included in the list
        assertThat(all.transactions()).filteredOn(t -> t.id().equals(s.id())).singleElement()
                .satisfies(t -> assertThat(t.splits()).hasSize(2));

        assertCode(Code.INVALID_INPUT, () -> list(null, null, null, null, null, null, null, 0, null));
        assertCode(Code.INVALID_INPUT, () -> list(null, null, null, null, null, null, null, 5000, null));
        assertCode(Code.INVALID_INPUT, () -> list(null, null, null, null, null, null, null, null, -1));
    }

    @Test
    void approveAndClearInBulk() {
        TransactionDto a = transactions.createTransaction(new TransactionActions.CreateTransactionInput(budgetId,
                checking, D1, -1L, null, "A", null, null, null, false, null, null, null, null));
        TransactionDto b = spend(budgetId, checking, D1, -2L, "B", null);
        assertThat(a.approved()).isFalse();

        Map<String, Object> approved = transactions.approveTransactions(
                new TransactionActions.ApproveTransactionsInput(budgetId, List.of(a.id(), b.id())));
        assertThat(approved).containsEntry("approved", 1);
        assertThat(txn(budgetId, a.id()).approved()).isTrue();

        Map<String, Object> cleared = transactions.setTransactionsCleared(
                new TransactionActions.SetTransactionsClearedInput(budgetId, List.of(a.id(), b.id()), Cleared.CLEARED));
        assertThat(cleared).containsEntry("updated", 2);
        assertThat(txn(budgetId, b.id()).cleared()).isEqualTo(Cleared.CLEARED);

        UUID other = newBudget();
        UUID foreign = spend(other, account(other, "X", AccountType.CHECKING, 0).id(), D1, -1, "x", null).id();
        assertCode(Code.NOT_FOUND, () -> transactions.approveTransactions(
                new TransactionActions.ApproveTransactionsInput(budgetId, List.of(a.id(), foreign))));
        assertCode(Code.NOT_FOUND, () -> transactions.setTransactionsCleared(
                new TransactionActions.SetTransactionsClearedInput(budgetId, List.of(foreign), Cleared.UNCLEARED)));
        assertThat(txn(other, foreign).cleared()).isEqualTo(Cleared.UNCLEARED);
    }

    @Test
    void transactionIdsFromOtherBudgetsAreNotFound() {
        TransactionDto t = spend(budgetId, checking, D1, -1_000, "Shop", null);
        UUID other = newBudget();
        assertCode(Code.NOT_FOUND, () -> txn(other, t.id()));
        assertCode(Code.NOT_FOUND, () -> updateTxn(other, t.id(), x -> x.memo = "x"));
        assertCode(Code.NOT_FOUND, () -> transactions.deleteTransaction(
                new TransactionActions.DeleteTransactionInput(other, t.id())));
        assertThat(txn(budgetId, t.id()).memo()).isNull();
    }
}
