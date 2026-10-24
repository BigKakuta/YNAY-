package io.ynay.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountActions;
import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportReconcileTest extends LedgerTestSupport {

    UUID budgetId;
    UUID checking;
    UUID groceries;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
        checking = account(budgetId, "Checking", AccountType.CHECKING, 0).id();
        groceries = category(budgetId, "Groceries").id();
    }

    private TransactionActions.ImportResult importLines(UUID accountId, List<ImportLine> lines) {
        return transactions.importTransactions(
                new TransactionActions.ImportTransactionsInput(budgetId, accountId, lines, null));
    }

    private static ImportLine line(LocalDate date, long amount, String payee) {
        return new ImportLine(date, null, amount, payee, null, null, null, null);
    }

    private List<TransactionDto> all(UUID accountId) {
        return transactions.listTransactions(new TransactionActions.ListTransactionsInput(budgetId, accountId, null,
                null, null, null, null, null, null, null, null)).transactions();
    }

    @Test
    void importingTheSameStatementTwiceReportsDuplicates() {
        List<ImportLine> statement = List.of(
                line(D1, -5_000, "Coffee Shop"),
                line(D1, -5_000, "Coffee Shop"),
                line(D1.plusDays(1), 250_000, "Employer"),
                new ImportLine(D1.plusDays(2), null, -12_000L, "Grocer", "card 1234", groceries, "FITID-77", null));

        TransactionActions.ImportResult first = importLines(checking, statement);
        assertThat(first.imported()).isEqualTo(4);
        assertThat(first.duplicates()).isZero();
        assertThat(first.transactionIds()).hasSize(4);
        assertThat(first.duplicateImportIds()).isEmpty();

        List<TransactionDto> imported = all(checking);
        assertThat(imported).extracting(TransactionDto::importId).containsExactlyInAnyOrder(
                "YNAY:-5000:2026-10-01:1", "YNAY:-5000:2026-10-01:2", "YNAY:250000:2026-10-02:1", "FITID-77");
        assertThat(imported).allSatisfy(t -> {
            assertThat(t.approved()).isFalse();
            assertThat(t.cleared()).isEqualTo(Cleared.CLEARED);
        });
        assertThat(imported).filteredOn(t -> "FITID-77".equals(t.importId())).singleElement().satisfies(t -> {
            assertThat(t.categoryId()).isEqualTo(groceries);
            assertThat(t.memo()).isEqualTo("card 1234");
            assertThat(t.payeeName()).isEqualTo("Grocer");
        });

        TransactionActions.ImportResult second = importLines(checking, statement);
        assertThat(second.imported()).isZero();
        assertThat(second.duplicates()).isEqualTo(4);
        assertThat(second.transactionIds()).isEmpty();
        assertThat(second.duplicateImportIds()).containsExactly(
                "YNAY:-5000:2026-10-01:1", "YNAY:-5000:2026-10-01:2", "YNAY:250000:2026-10-02:1", "FITID-77");
        assertThat(all(checking)).hasSize(4);

        // an overlapping statement with one more coffee only adds the new line
        TransactionActions.ImportResult third = importLines(checking, List.of(
                line(D1, -5_000, "Coffee Shop"), line(D1, -5_000, "Coffee Shop"), line(D1, -5_000, "Coffee Shop")));
        assertThat(third.imported()).isEqualTo(1);
        assertThat(third.duplicates()).isEqualTo(2);
        assertThat(txn(budgetId, third.transactionIds().getFirst()).importId()).isEqualTo("YNAY:-5000:2026-10-01:3");
    }

    @Test
    void dedupeIsPerAccountAndWithinTheBatch() {
        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        ImportLine l = new ImportLine(D1, null, -1_000L, "X", null, null, "ID-1", null);
        assertThat(importLines(checking, List.of(l, l)).duplicates()).isEqualTo(1);
        assertThat(importLines(savings, List.of(l)).imported()).isEqualTo(1);
    }

    @Test
    void importAppliesMemorizedCategoryAndApprovedFlag() {
        spend(budgetId, checking, D1, -3_000, "Grocer", groceries);
        TransactionActions.ImportResult r = transactions.importTransactions(new TransactionActions.ImportTransactionsInput(
                budgetId, checking, List.of(line(D1.plusDays(5), -4_000, "GROCER"),
                new ImportLine(D1.plusDays(5), null, -1_000L, "Grocer", null, null, null, Cleared.UNCLEARED)), true));
        assertThat(r.transactionIds()).extracting(id -> txn(budgetId, id)).allSatisfy(t -> {
            assertThat(t.categoryId()).isEqualTo(groceries);
            assertThat(t.approved()).isTrue();
        });
        assertThat(txn(budgetId, r.transactionIds().get(1)).cleared()).isEqualTo(Cleared.UNCLEARED);
    }

    @Test
    void importCanRecordTransfersAndValidatesInput() {
        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        TransactionActions.ImportResult r = importLines(checking, List.of(line(D1, -10_000, "Transfer : Savings")));
        TransactionDto t = txn(budgetId, r.transactionIds().getFirst());
        assertThat(t.transferAccountId()).isEqualTo(savings);
        assertThat(all(savings)).singleElement().satisfies(s -> assertThat(s.amount()).isEqualTo(10_000));

        UUID foreign = category(newBudget(), "Groceries").id();
        assertCode(Code.NOT_FOUND, () -> importLines(checking, List.of(
                new ImportLine(D1, null, -1L, "X", null, foreign, null, null))));
        assertCode(Code.INVALID_INPUT, () -> importLines(checking, List.of(
                new ImportLine(null, null, -1L, "X", null, null, null, null))));
        assertThat(importLines(checking, List.of()).imported()).isZero();
        // nothing partial was written by the failed batches
        assertThat(all(checking)).hasSize(1);
    }

    @Test
    void reconcileWithMatchingBalanceMarksClearedReconciled() {
        importLines(checking, List.of(line(D1, 100_000, "Employer"), line(D1.plusDays(1), -20_000, "Shop"),
                line(D1.plusDays(20), -1_000, "Later")));
        TransactionDto pending = spend(budgetId, checking, D1.plusDays(2), -7_000, "Pending", null);

        TransactionService.ReconcileResult r = transactions.reconcileAccount(
                new TransactionActions.ReconcileAccountInput(budgetId, checking, 80_000L, D1.plusDays(10)));

        assertThat(r.reconciledCount()).isEqualTo(2);
        assertThat(r.adjustmentTransactionId()).isNull();
        assertThat(all(checking)).filteredOn(t -> t.date().isBefore(D1.plusDays(10)))
                .filteredOn(t -> !t.id().equals(pending.id()))
                .allSatisfy(t -> assertThat(t.cleared()).isEqualTo(Cleared.RECONCILED));
        assertThat(txn(budgetId, pending.id()).cleared()).isEqualTo(Cleared.UNCLEARED);
        assertThat(all(checking)).filteredOn(t -> t.payeeName().equals("Later")).singleElement()
                .satisfies(t -> assertThat(t.cleared()).isEqualTo(Cleared.CLEARED));
    }

    @Test
    void reconcileWithDifferenceCreatesAdjustmentInReadyToAssign() {
        importLines(checking, List.of(line(D1, 100_000, "Employer")));

        TransactionService.ReconcileResult r = transactions.reconcileAccount(
                new TransactionActions.ReconcileAccountInput(budgetId, checking, 98_500L, D1.plusDays(1)));

        assertThat(r.reconciledCount()).isEqualTo(1);
        TransactionDto adj = txn(budgetId, r.adjustmentTransactionId());
        assertThat(adj.amount()).isEqualTo(-1_500);
        assertThat(adj.payeeName()).isEqualTo("Reconciliation Balance Adjustment");
        assertThat(adj.categoryId()).isEqualTo(rta(budgetId));
        assertThat(adj.cleared()).isEqualTo(Cleared.RECONCILED);
        assertThat(adj.date()).isEqualTo(D1.plusDays(1));
        assertThat(accountById(budgetId, checking).clearedBalance()).isEqualTo(98_500);

        // reconciling again at the same balance is a no-op
        TransactionService.ReconcileResult again = transactions.reconcileAccount(
                new TransactionActions.ReconcileAccountInput(budgetId, checking, 98_500L, D1.plusDays(1)));
        assertThat(again.reconciledCount()).isZero();
        assertThat(again.adjustmentTransactionId()).isNull();
    }

    @Test
    void reconcileTrackingAccountAdjustmentIsUncategorized() {
        UUID house = accounts.createAccount(new AccountActions.CreateAccountInput(budgetId, "House",
                AccountType.ASSET, null, 300_000_000L, D1, null)).id();
        TransactionService.ReconcileResult r = transactions.reconcileAccount(
                new TransactionActions.ReconcileAccountInput(budgetId, house, 310_000_000L, D1.plusDays(1)));
        assertThat(r.reconciledCount()).isEqualTo(1);
        assertThat(txn(budgetId, r.adjustmentTransactionId()).categoryId()).isNull();
        assertThat(txn(budgetId, r.adjustmentTransactionId()).amount()).isEqualTo(10_000_000);
    }
}
