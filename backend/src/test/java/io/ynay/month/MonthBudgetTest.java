package io.ynay.month;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.month.BudgetFixture.Line;
import io.ynay.month.MonthActions.GetMonthBudgetInput;
import io.ynay.month.MonthActions.ListMonthSummariesInput;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Month math: amounts are milliunits, so 1_000_000 = 1000.00. */
class MonthBudgetTest extends IntegrationTest {

    @Autowired
    MonthActions actions;

    YnayUser user;
    BudgetFixture f;
    UUID checking;
    UUID needs;
    UUID groceries;
    UUID dining;

    @BeforeEach
    void setUp() {
        user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
        checking = f.account("Checking", "CHECKING", true);
        needs = f.group("Needs");
        groceries = f.category(needs, "Groceries");
        dining = f.category(needs, "Dining Out");
    }

    MonthBudget month(String m) {
        return actions.getMonthBudget(new GetMonthBudgetInput(f.budgetId, YearMonth.parse(m)));
    }

    static CategoryMonth cat(MonthBudget mb, UUID id) {
        return mb.groups().stream().flatMap(g -> g.categories().stream())
                .filter(c -> c.categoryId().equals(id)).findFirst().orElseThrow();
    }

    static GroupMonth group(MonthBudget mb, UUID id) {
        return mb.groups().stream().filter(g -> g.groupId().equals(id)).findFirst().orElseThrow();
    }

    static void assertNumbers(CategoryMonth c, long carryover, long assigned, long activity, long available) {
        assertThat(List.of(c.carryover(), c.assigned(), c.activity(), c.available()))
                .as(c.name() + " carryover/assigned/activity/available")
                .containsExactly(carryover, assigned, activity, available);
    }

    @Test
    void basicAssignSpendAvailable() {
        UUID rent = f.category(needs, "Rent");
        f.startingBalance(checking, "2026-01-05", 1_000_000);
        f.assign(groceries, "2026-01", 300_000);
        f.assign(rent, "2026-01", 500_000);
        f.txn(checking, "2026-01-10", -120_000, groceries, "Supermarket");
        f.txn(checking, "2026-01-01", -500_000, rent, "Landlord");

        MonthBudget jan = month("2026-01");
        assertThat(jan.month()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(jan.currencyCode()).isEqualTo("EUR");
        assertThat(jan.income()).isEqualTo(1_000_000);
        assertThat(jan.assigned()).isEqualTo(800_000);
        assertThat(jan.activity()).isEqualTo(-620_000);
        assertThat(jan.available()).isEqualTo(180_000);
        assertThat(jan.readyToAssign()).isEqualTo(200_000);
        assertThat(jan.overspentLastMonth()).isZero();
        assertThat(jan.uncategorized()).isZero();
        assertNumbers(cat(jan, groceries), 0, 300_000, -120_000, 180_000);
        assertNumbers(cat(jan, rent), 0, 500_000, -500_000, 0);
        GroupMonth g = group(jan, needs);
        assertThat(List.of(g.assigned(), g.activity(), g.available())).containsExactly(800_000L, -620_000L, 180_000L);
        assertThat(g.categories()).extracting(CategoryMonth::name).containsExactly("Groceries", "Dining Out", "Rent");
        // INTERNAL group (with Ready to Assign) is not listed; CC payments group is
        assertThat(jan.groups()).extracting(GroupMonth::groupId).containsExactly(f.ccGroupId, needs);
        assertThat(jan.groups()).flatExtracting(GroupMonth::categories).extracting(CategoryMonth::categoryId)
                .doesNotContain(f.rtaId);

        // nothing in the month before: RTA already reduced by January's assignments
        MonthBudget dec = month("2025-12");
        assertThat(dec.income()).isZero();
        assertThat(dec.readyToAssign()).isEqualTo(-800_000);
        assertNumbers(cat(dec, groceries), 0, 0, 0, 0);
    }

    @Test
    void carryoverAndOverspendingReducesNextMonthsReadyToAssign() {
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.assign(groceries, "2026-01", 100_000);
        f.assign(dining, "2026-01", 50_000);
        f.txn(checking, "2026-01-12", -80_000, groceries);
        f.txn(checking, "2026-01-13", -70_000, dining);
        f.assign(groceries, "2026-02", 100_000);
        f.txn(checking, "2026-02-03", -30_000, groceries);

        MonthBudget jan = month("2026-01");
        assertNumbers(cat(jan, groceries), 0, 100_000, -80_000, 20_000);
        assertNumbers(cat(jan, dining), 0, 50_000, -70_000, -20_000);
        assertThat(jan.readyToAssign()).isEqualTo(750_000); // Feb's 100 is already counted

        MonthBudget feb = month("2026-02");
        assertNumbers(cat(feb, groceries), 20_000, 100_000, -30_000, 90_000);
        assertNumbers(cat(feb, dining), 0, 0, 0, 0); // overspending doesn't roll over in the category
        assertThat(feb.overspentLastMonth()).isEqualTo(20_000);
        assertThat(feb.readyToAssign()).isEqualTo(1_000_000 - 250_000 - 20_000);
        assertThat(feb.income()).isZero();

        MonthBudget mar = month("2026-03");
        assertNumbers(cat(mar, groceries), 90_000, 0, 0, 90_000);
        assertThat(mar.overspentLastMonth()).isZero();
        assertThat(mar.readyToAssign()).isEqualTo(730_000);

        List<MonthSummary> summaries = actions.listMonthSummaries(new ListMonthSummariesInput(
                f.budgetId, YearMonth.of(2026, 1), YearMonth.of(2026, 3)));
        assertThat(summaries).containsExactly(
                new MonthSummary(YearMonth.of(2026, 1), 1_000_000, 150_000, -150_000, 750_000),
                new MonthSummary(YearMonth.of(2026, 2), 0, 100_000, -30_000, 730_000),
                new MonthSummary(YearMonth.of(2026, 3), 0, 0, 0, 730_000));
    }

    @Test
    void futureAssignmentsReduceCurrentReadyToAssign() {
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.assign(groceries, "2026-01", 100_000);
        f.assign(groceries, "2026-03", 200_000);

        MonthBudget jan = month("2026-01");
        assertThat(jan.readyToAssign()).isEqualTo(700_000);
        assertThat(jan.assigned()).isEqualTo(100_000);
        assertNumbers(cat(month("2026-02"), groceries), 100_000, 0, 0, 100_000);
        assertNumbers(cat(month("2026-03"), groceries), 100_000, 200_000, 0, 300_000);
        assertThat(month("2026-03").readyToAssign()).isEqualTo(700_000);
    }

    @Test
    void splitLinesCountTowardsTheirCategories() {
        f.startingBalance(checking, "2026-01-01", 500_000);
        f.split(checking, "2026-01-15", new Line(-60_000, groceries), new Line(-40_000, dining));
        // paycheck with a deduction: +200 income, -50 groceries
        f.split(checking, "2026-01-25", new Line(200_000, f.rtaId), new Line(-50_000, groceries));

        MonthBudget jan = month("2026-01");
        assertThat(jan.income()).isEqualTo(700_000);
        assertNumbers(cat(jan, groceries), 0, 0, -110_000, -110_000);
        assertNumbers(cat(jan, dining), 0, 0, -40_000, -40_000);
        assertThat(jan.activity()).isEqualTo(-150_000);
        assertThat(jan.uncategorized()).isZero();
        assertThat(jan.readyToAssign()).isEqualTo(700_000);
        assertThat(month("2026-02").readyToAssign()).isEqualTo(700_000 - 150_000);
    }

    @Test
    void transfersBetweenOnBudgetAccountsHaveNoEffect() {
        UUID savings = f.account("Savings", "SAVINGS", true);
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.startingBalance(savings, "2026-01-01", 500_000);
        f.transfer(checking, savings, "2026-01-10", 200_000, null);

        MonthBudget jan = month("2026-01");
        assertThat(jan.income()).isEqualTo(1_500_000);
        assertThat(jan.activity()).isZero();
        assertThat(jan.uncategorized()).isZero();
        assertThat(jan.readyToAssign()).isEqualTo(1_500_000);
        assertThat(month("2026-02").readyToAssign()).isEqualTo(1_500_000);
    }

    @Test
    void transferToTrackingAccountUsesItsCategoryAndTrackingTransactionsAreIgnored() {
        UUID mortgage = f.account("Mortgage", "MORTGAGE", false);
        UUID brokerage = f.account("Brokerage", "ASSET", false);
        UUID mortgagePayment = f.category(needs, "Mortgage payment");
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.startingBalance(mortgage, "2026-01-01", -200_000_000);
        f.startingBalance(brokerage, "2026-01-01", 50_000_000);
        f.assign(mortgagePayment, "2026-01", 150_000);
        f.transfer(checking, mortgage, "2026-01-15", 150_000, mortgagePayment);
        // anything on tracking accounts is ignored, categorized or not
        f.txn(brokerage, "2026-01-20", -999_000, groceries);
        f.txn(brokerage, "2026-01-21", -50_000, null);
        f.txn(brokerage, "2026-01-22", 70_000, f.rtaId);

        MonthBudget jan = month("2026-01");
        assertThat(jan.income()).isEqualTo(1_000_000);
        assertNumbers(cat(jan, mortgagePayment), 0, 150_000, -150_000, 0);
        assertNumbers(cat(jan, groceries), 0, 0, 0, 0);
        assertThat(jan.uncategorized()).isZero();
        assertThat(jan.readyToAssign()).isEqualTo(850_000);
        assertThat(month("2026-02").overspentLastMonth()).isZero();
    }

    @Test
    void creditCardSpendingMovesMoneyToPaymentCategoryAndPaymentsReduceIt() {
        UUID card = f.account("Visa", "CREDIT_CARD", true);
        UUID payment = f.paymentCategory(card);
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.startingBalance(card, "2026-01-01", -300_000); // existing debt: no budget effect
        f.assign(groceries, "2026-01", 200_000);
        f.assign(payment, "2026-01", 300_000);
        f.txn(card, "2026-01-05", -50_000, groceries);
        f.txn(card, "2026-01-06", 10_000, groceries); // refund
        f.txn(card, "2026-01-07", 5_000, f.rtaId); // rewards as income: no effect on the payment category
        f.transfer(checking, card, "2026-01-20", 100_000, null); // payment

        MonthBudget jan = month("2026-01");
        assertThat(jan.income()).isEqualTo(1_005_000);
        assertThat(jan.uncategorized()).isZero();
        assertNumbers(cat(jan, groceries), 0, 200_000, -40_000, 160_000);
        assertNumbers(cat(jan, payment), 0, 300_000, 40_000 - 100_000, 240_000);
        CategoryMonth p = cat(jan, payment);
        assertThat(p.systemKind()).isEqualTo("CREDIT_CARD_PAYMENT");
        assertThat(p.linkedAccountId()).isEqualTo(card);
        assertThat(group(jan, f.ccGroupId).categories()).extracting(CategoryMonth::categoryId).containsExactly(payment);
        assertThat(jan.readyToAssign()).isEqualTo(1_005_000 - 500_000);

        // Feb: unfunded dining on the card; and moving money off the card (cash advance)
        f.txn(card, "2026-02-02", -20_000, dining);
        f.transfer(card, checking, "2026-02-03", 30_000, null);
        MonthBudget feb = month("2026-02");
        assertNumbers(cat(feb, dining), 0, 0, -20_000, -20_000);
        assertNumbers(cat(feb, payment), 240_000, 0, 50_000, 290_000);
        assertThat(feb.readyToAssign()).isEqualTo(505_000);
        MonthBudget mar = month("2026-03");
        assertThat(mar.overspentLastMonth()).isEqualTo(20_000);
        assertThat(mar.readyToAssign()).isEqualTo(485_000);
    }

    @Test
    void uncategorizedOutflowsCountAsOverspending() {
        f.startingBalance(checking, "2026-01-01", 1_000_000);
        f.txn(checking, "2026-01-10", -70_000, null, "Mystery shop");
        f.split(checking, "2026-01-11", new Line(-10_000, null), new Line(-20_000, groceries));
        f.txn(checking, "2026-02-10", 30_000, null);

        MonthBudget jan = month("2026-01");
        assertThat(jan.uncategorized()).isEqualTo(-80_000);
        assertThat(jan.activity()).isEqualTo(-20_000); // category totals exclude uncategorized
        assertThat(jan.readyToAssign()).isEqualTo(1_000_000);

        MonthBudget feb = month("2026-02");
        assertThat(feb.overspentLastMonth()).isEqualTo(80_000 + 20_000);
        assertThat(feb.uncategorized()).isEqualTo(30_000);
        assertThat(feb.readyToAssign()).isEqualTo(900_000);
        // positive uncategorized is not overspending (and not income)
        assertThat(month("2026-03").overspentLastMonth()).isZero();
        assertThat(month("2026-03").readyToAssign()).isEqualTo(900_000);
    }

    @Test
    void targetNeededForEachTargetType() {
        UUID savings = f.category(needs, "Savings");
        UUID spending = f.category(needs, "Spending");
        UUID balance = f.category(needs, "Balance");
        UUID byDate = f.category(needs, "By date");
        UUID overfunded = f.category(needs, "Overfunded");
        f.target(savings, TargetType.MONTHLY_SAVINGS, 100_000, null);
        f.target(spending, TargetType.MONTHLY_SPENDING, 100_000, null);
        f.target(balance, TargetType.TARGET_BALANCE, 1_000_000, null);
        f.target(byDate, TargetType.TARGET_BALANCE_BY_DATE, 1_000_001, java.time.LocalDate.of(2026, 7, 15));
        f.target(overfunded, TargetType.MONTHLY_SAVINGS, 100_000, null);
        for (UUID c : List.of(savings, spending, balance, byDate)) {
            f.assign(c, "2026-02", 50_000);
            f.assign(c, "2026-03", 30_000);
        }
        f.assign(overfunded, "2026-03", 150_000);

        MonthBudget mar = month("2026-03");
        assertThat(cat(mar, savings).targetNeeded()).isEqualTo(70_000);
        assertThat(cat(mar, spending).targetNeeded()).isEqualTo(20_000);
        assertThat(cat(mar, balance).targetNeeded()).isEqualTo(920_000);
        // Mar..Jul = 5 months: ceil((1_000_001 - 50_000) / 5) - 30_000
        assertThat(cat(mar, byDate).targetNeeded()).isEqualTo(160_001);
        assertThat(cat(mar, overfunded).targetNeeded()).isZero();
        assertThat(cat(mar, groceries).targetNeeded()).isZero();
        assertThat(cat(mar, groceries).target()).isNull();
        assertThat(cat(mar, byDate).target())
                .isEqualTo(new TargetDto(TargetType.TARGET_BALANCE_BY_DATE, 1_000_001, java.time.LocalDate.of(2026, 7, 15)));

        // in the target month all that's left is due; after it nothing
        assertThat(cat(month("2026-07"), byDate).targetNeeded()).isEqualTo(1_000_001 - 80_000);
        assertThat(cat(month("2026-08"), byDate).targetNeeded()).isZero();
        // monthly savings resets every month regardless of balance
        assertThat(cat(month("2026-04"), savings).targetNeeded()).isEqualTo(100_000);
        assertThat(cat(month("2026-04"), spending).targetNeeded()).isEqualTo(20_000);
    }

    @Test
    void requiresMembership() {
        YnayUser other = createUser("intruder-" + UUID.randomUUID());
        loginAs(other);
        assertThatThrownBy(() -> month("2026-01"))
                .isInstanceOf(ActionException.class)
                .extracting(e -> ((ActionException) e).code())
                .isEqualTo(ActionException.Code.NOT_FOUND);
    }

    @Test
    void validatesMonthRange() {
        assertThatThrownBy(() -> actions.listMonthSummaries(new ListMonthSummariesInput(
                f.budgetId, YearMonth.of(2026, 3), YearMonth.of(2026, 1))))
                .isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> actions.getMonthBudget(new GetMonthBudgetInput(f.budgetId, null)))
                .isInstanceOf(ActionException.class);
    }
}
