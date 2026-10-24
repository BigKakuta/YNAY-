package io.ynay.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.month.BudgetFixture;
import io.ynay.month.BudgetFixture.Line;
import io.ynay.report.ReportActions.IncomeExpenseRow;
import io.ynay.report.ReportActions.NetWorthRow;
import io.ynay.report.ReportActions.ReportInput;
import io.ynay.report.ReportActions.SpendingByCategory;
import io.ynay.report.ReportActions.SpendingRow;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Amounts in milliunits (1_000 = 1.00). */
class ReportActionsTest extends IntegrationTest {

    @Autowired
    ReportActions reports;

    BudgetFixture f;
    UUID groceries;
    UUID dining;
    UUID fun;
    UUID loanPayment;

    @BeforeEach
    void setUp() {
        YnayUser user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
        UUID checking = f.account("Checking", "CHECKING", true);
        UUID savings = f.account("Savings", "SAVINGS", true);
        UUID card = f.account("Visa", "CREDIT_CARD", true);
        UUID house = f.account("House", "ASSET", false);
        UUID loan = f.account("Car loan", "LOAN", false);
        UUID needs = f.group("Needs");
        UUID wants = f.group("Wants");
        groceries = f.category(needs, "Groceries");
        loanPayment = f.category(needs, "Loan payment");
        dining = f.category(wants, "Dining Out");
        fun = f.category(wants, "Fun");

        f.startingBalance(checking, "2026-01-01", 2_000_000);
        f.startingBalance(savings, "2026-01-01", 1_000_000);
        f.startingBalance(card, "2026-01-01", -500_000);
        f.startingBalance(house, "2026-01-01", 300_000_000);
        f.startingBalance(loan, "2026-01-01", -100_000_000);

        // January
        f.txn(checking, "2026-01-03", -100_000, groceries);
        f.txn(card, "2026-01-04", -50_000, dining);
        f.txn(card, "2026-01-05", 10_000, dining); // refund
        f.split(checking, "2026-01-06", new Line(-30_000, groceries), new Line(-20_000, fun));
        f.transfer(checking, savings, "2026-01-07", 200_000, null); // on-budget transfer: not spending
        f.transfer(checking, card, "2026-01-08", 300_000, null); // card payment: not spending
        f.transfer(checking, loan, "2026-01-09", 40_000, loanPayment); // to tracking: spending
        f.txn(checking, "2026-01-25", 1_500_000, f.rtaId, "Employer");
        f.txn(house, "2026-01-26", -1_000, groceries); // tracking account: ignored by budget reports

        // February
        f.txn(checking, "2026-02-02", -80_000, groceries);
        f.txn(checking, "2026-02-25", 2_500_000, f.rtaId, "Employer");
        f.txn(checking, "2026-02-26", -5_000, null); // uncategorized: not in spending
    }

    ReportInput range(String from, String to) {
        return new ReportInput(f.budgetId, YearMonth.parse(from), YearMonth.parse(to));
    }

    @Test
    void spendingByCategory() {
        SpendingByCategory janFeb = reports.spendingByCategory(range("2026-01", "2026-02"));
        assertThat(janFeb.rows()).containsExactly(
                new SpendingRow(groceries, "Groceries", "Needs", 210_000),
                new SpendingRow(dining, "Dining Out", "Wants", 40_000),
                new SpendingRow(loanPayment, "Loan payment", "Needs", 40_000),
                new SpendingRow(fun, "Fun", "Wants", 20_000));
        assertThat(janFeb.total()).isEqualTo(310_000);

        SpendingByCategory feb = reports.spendingByCategory(range("2026-02", "2026-02"));
        assertThat(feb.rows()).containsExactly(new SpendingRow(groceries, "Groceries", "Needs", 80_000));
        assertThat(feb.total()).isEqualTo(80_000);

        SpendingByCategory empty = reports.spendingByCategory(range("2025-01", "2025-12"));
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.total()).isZero();
    }

    @Test
    void incomeVsExpense() {
        assertThat(reports.incomeVsExpense(range("2026-01", "2026-03"))).containsExactly(
                new IncomeExpenseRow(YearMonth.of(2026, 1), 4_500_000, 230_000, 4_270_000),
                new IncomeExpenseRow(YearMonth.of(2026, 2), 2_500_000, 80_000, 2_420_000),
                new IncomeExpenseRow(YearMonth.of(2026, 3), 0, 0, 0));
    }

    @Test
    void netWorth() {
        // Jan end: checking 2_810_000, savings 1_200_000, house 299_999_000, card -240_000, loan -99_960_000
        assertThat(reports.netWorth(range("2025-12", "2026-03"))).containsExactly(
                new NetWorthRow(YearMonth.of(2025, 12), 0, 0, 0),
                new NetWorthRow(YearMonth.of(2026, 1), 304_009_000, -100_200_000, 203_809_000),
                new NetWorthRow(YearMonth.of(2026, 2), 306_424_000, -100_200_000, 206_224_000),
                new NetWorthRow(YearMonth.of(2026, 3), 306_424_000, -100_200_000, 206_224_000));
        // balances before the range are carried in
        assertThat(reports.netWorth(range("2026-02", "2026-02"))).containsExactly(
                new NetWorthRow(YearMonth.of(2026, 2), 306_424_000, -100_200_000, 206_224_000));
    }

    @Test
    void validationAndAccess() {
        assertThatThrownBy(() -> reports.netWorth(range("2026-03", "2026-01"))).isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> reports.netWorth(new ReportInput(f.budgetId, null, YearMonth.of(2026, 1))))
                .isInstanceOf(ActionException.class);
        loginAs(createUser("other-" + UUID.randomUUID()));
        assertThatThrownBy(() -> reports.spendingByCategory(range("2026-01", "2026-01")))
                .isInstanceOf(ActionException.class)
                .extracting(e -> ((ActionException) e).code())
                .isEqualTo(ActionException.Code.NOT_FOUND);
    }
}
