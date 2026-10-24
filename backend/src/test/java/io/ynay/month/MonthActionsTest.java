package io.ynay.month;

import io.ynay.category.CategoryDto;
import io.ynay.category.TargetDto;
import io.ynay.category.TargetType;
import static io.ynay.month.MonthBudgetTest.cat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.month.MonthActions.AssignToCategoryInput;
import io.ynay.month.MonthActions.AssignUnderfundedInput;
import io.ynay.month.MonthActions.ClearCategoryTargetInput;
import io.ynay.month.MonthActions.GetMonthBudgetInput;
import io.ynay.month.MonthActions.MoveMoneyInput;
import io.ynay.month.MonthActions.SetCategoryTargetInput;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MonthActionsTest extends IntegrationTest {

    static final YearMonth JAN = YearMonth.of(2026, 1);

    @Autowired
    MonthActions actions;

    @Autowired
    JsonMapper json;

    YnayUser user;
    BudgetFixture f;
    UUID checking;
    UUID group;
    UUID groceries;
    UUID dining;

    @BeforeEach
    void setUp() {
        user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
        checking = f.account("Checking", "CHECKING", true);
        group = f.group("Everyday");
        groceries = f.category(group, "Groceries");
        dining = f.category(group, "Dining Out");
        f.startingBalance(checking, "2026-01-01", 1_000_000);
    }

    MonthBudget jan() {
        return actions.getMonthBudget(new GetMonthBudgetInput(f.budgetId, JAN));
    }

    static void assertCode(ThrowingCallable call, ActionException.Code code) {
        assertThatThrownBy(call).isInstanceOf(ActionException.class)
                .extracting(e -> ((ActionException) e).code()).isEqualTo(code);
    }

    @Test
    void assignToCategorySetsAbsoluteAmount() {
        CategoryMonth first = actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, groceries, 100_000L));
        assertThat(first.assigned()).isEqualTo(100_000);
        assertThat(first.available()).isEqualTo(100_000);
        assertThat(first.categoryId()).isEqualTo(groceries);
        assertThat(first.name()).isEqualTo("Groceries");

        f.txn(checking, "2026-01-03", -25_000, groceries);
        CategoryMonth second = actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, groceries, 40_000L));
        assertThat(second.assigned()).isEqualTo(40_000);
        assertThat(second.activity()).isEqualTo(-25_000);
        assertThat(second.available()).isEqualTo(15_000);
        assertThat(jan().readyToAssign()).isEqualTo(960_000);

        // negative assignment is allowed (YNAB-style "unassign")
        actions.assignToCategory(new AssignToCategoryInput(f.budgetId, YearMonth.of(2026, 2), groceries, -10_000L));
        assertThat(jan().readyToAssign()).isEqualTo(970_000);
    }

    @Test
    void assignToCategoryValidation() {
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, f.rtaId, 1L)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, null, groceries, 1L)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, groceries, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, UUID.randomUUID(), 1L)),
                ActionException.Code.NOT_FOUND);

        // a category of another budget is not found in this one
        BudgetFixture other = BudgetFixture.create(jdbc, user.userId());
        UUID foreign = other.category(other.group("G"), "Foreign");
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, foreign, 1L)),
                ActionException.Code.NOT_FOUND);

        // non-members can't see the budget at all
        loginAs(createUser("other-" + UUID.randomUUID()));
        assertCode(() -> actions.assignToCategory(new AssignToCategoryInput(f.budgetId, JAN, groceries, 1L)),
                ActionException.Code.NOT_FOUND);
    }

    @Test
    void moveMoneyBetweenCategoriesAndReadyToAssign() {
        MonthBudget mb = actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 300_000L, null, groceries));
        assertThat(cat(mb, groceries).assigned()).isEqualTo(300_000);
        assertThat(mb.readyToAssign()).isEqualTo(700_000);

        mb = actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 100_000L, groceries, dining));
        assertThat(cat(mb, groceries).assigned()).isEqualTo(200_000);
        assertThat(cat(mb, dining).assigned()).isEqualTo(100_000);
        assertThat(mb.readyToAssign()).isEqualTo(700_000);

        mb = actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 50_000L, dining, null));
        assertThat(cat(mb, dining).assigned()).isEqualTo(50_000);
        assertThat(mb.readyToAssign()).isEqualTo(750_000);

        // the Ready to Assign category id works like null
        mb = actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 10_000L, f.rtaId, dining));
        assertThat(cat(mb, dining).assigned()).isEqualTo(60_000);
        assertThat(mb.readyToAssign()).isEqualTo(740_000);

        // moving more than is available makes the source negative
        mb = actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 100_000L, dining, groceries));
        assertThat(cat(mb, dining).available()).isEqualTo(-40_000);
        assertThat(cat(mb, groceries).available()).isEqualTo(300_000);
    }

    @Test
    void moveMoneyValidation() {
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 0L, null, groceries)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, -5L, null, groceries)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 5L, null, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 5L, f.rtaId, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 5L, groceries, groceries)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.moveMoney(new MoveMoneyInput(f.budgetId, JAN, 5L, UUID.randomUUID(), groceries)),
                ActionException.Code.NOT_FOUND);
    }

    @Test
    void assignUnderfundedFundsTargetsInOrderUntilReadyToAssignRunsOut() {
        // RTA 1000; use 750 elsewhere so 250 remain, minus C's existing 20 => 230
        f.assign(groceries, "2026-01", 750_000);
        UUID a = f.category(group, "A");
        UUID b = f.category(group, "B hidden", true);
        UUID c = f.category(group, "C");
        UUID d = f.category(group, "D");
        UUID e = f.category(group, "E no target");
        UUID hiddenGroup = f.group("Archived", true);
        UUID inHiddenGroup = f.category(hiddenGroup, "In hidden group");
        f.target(a, TargetType.MONTHLY_SAVINGS, 100_000, null);
        f.target(b, TargetType.MONTHLY_SAVINGS, 50_000, null);
        f.target(c, TargetType.TARGET_BALANCE, 120_000, null);
        f.target(d, TargetType.MONTHLY_SAVINGS, 100_000, null);
        f.target(inHiddenGroup, TargetType.MONTHLY_SAVINGS, 10_000, null);
        f.assign(c, "2026-01", 20_000);
        assertThat(jan().readyToAssign()).isEqualTo(230_000);

        MonthBudget mb = actions.assignUnderfunded(new AssignUnderfundedInput(f.budgetId, JAN));
        assertThat(cat(mb, a).assigned()).isEqualTo(100_000);
        assertThat(cat(mb, b).assigned()).isZero();
        assertThat(cat(mb, c).assigned()).isEqualTo(120_000);
        assertThat(cat(mb, d).assigned()).isEqualTo(30_000); // partially funded
        assertThat(cat(mb, d).targetNeeded()).isEqualTo(70_000);
        assertThat(cat(mb, e).assigned()).isZero();
        assertThat(cat(mb, inHiddenGroup).assigned()).isZero();
        assertThat(mb.readyToAssign()).isZero();

        // nothing left to assign: no-op
        MonthBudget again = actions.assignUnderfunded(new AssignUnderfundedInput(f.budgetId, JAN));
        assertThat(again).isEqualTo(mb);
    }

    @Test
    void setAndClearCategoryTarget() {
        CategoryDto dto = actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, groceries, TargetType.MONTHLY_SPENDING, 400_000L, LocalDate.of(2027, 1, 1)));
        assertThat(dto.id()).isEqualTo(groceries);
        assertThat(dto.groupId()).isEqualTo(group);
        assertThat(dto.name()).isEqualTo("Groceries");
        // the date only applies to TARGET_BALANCE_BY_DATE
        assertThat(dto.target()).isEqualTo(new TargetDto(TargetType.MONTHLY_SPENDING, 400_000, null));
        assertThat(cat(jan(), groceries).targetNeeded()).isEqualTo(400_000);

        dto = actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, groceries, TargetType.TARGET_BALANCE_BY_DATE, 1_200_000L, LocalDate.of(2026, 12, 31)));
        assertThat(dto.target()).isEqualTo(
                new TargetDto(TargetType.TARGET_BALANCE_BY_DATE, 1_200_000, LocalDate.of(2026, 12, 31)));
        assertThat(cat(jan(), groceries).targetNeeded()).isEqualTo(100_000);

        dto = actions.clearCategoryTarget(new ClearCategoryTargetInput(f.budgetId, groceries));
        assertThat(dto.target()).isNull();
        assertThat(cat(jan(), groceries).target()).isNull();
        assertThat(cat(jan(), groceries).targetNeeded()).isZero();
    }

    @Test
    void setCategoryTargetValidation() {
        assertCode(() -> actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, groceries, TargetType.TARGET_BALANCE_BY_DATE, 1_000L, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, groceries, TargetType.MONTHLY_SAVINGS, 0L, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, groceries, null, 10L, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.setCategoryTarget(new SetCategoryTargetInput(
                f.budgetId, f.rtaId, TargetType.MONTHLY_SAVINGS, 10L, null)),
                ActionException.Code.INVALID_INPUT);
        assertCode(() -> actions.clearCategoryTarget(new ClearCategoryTargetInput(f.budgetId, UUID.randomUUID())),
                ActionException.Code.NOT_FOUND);
    }

    @Test
    void jsonShapesMatchTheContract() {
        f.target(groceries, TargetType.TARGET_BALANCE_BY_DATE, 500_000, LocalDate.of(2026, 6, 1));
        JsonNode mb = json.valueToTree(jan());
        assertThat(fieldNames(mb)).containsExactly("month", "currencyCode", "readyToAssign", "income", "assigned",
                "activity", "available", "overspentLastMonth", "uncategorized", "groups");
        assertThat(mb.get("month").asString()).isEqualTo("2026-01");
        JsonNode g = mb.get("groups").get(1);
        assertThat(fieldNames(g)).containsExactly("groupId", "name", "hidden", "systemKind", "assigned", "activity",
                "available", "categories");
        assertThat(mb.get("groups").get(0).get("systemKind").asString()).isEqualTo("CREDIT_CARD_PAYMENTS");
        assertThat(g.get("systemKind").isNull()).isTrue();
        JsonNode c = g.get("categories").get(0);
        assertThat(fieldNames(c)).containsExactly("categoryId", "name", "hidden", "systemKind", "linkedAccountId",
                "carryover", "assigned", "activity", "available", "target", "targetNeeded");
        assertThat(fieldNames(c.get("target"))).containsExactly("type", "amount", "date");
        assertThat(c.get("target").get("date").asString()).isEqualTo("2026-06-01");
        assertThat(c.get("target").get("type").asString()).isEqualTo("TARGET_BALANCE_BY_DATE");

        JsonNode summary = json.valueToTree(new MonthSummary(JAN, 1, 2, 3, 4));
        assertThat(fieldNames(summary)).containsExactly("month", "income", "assigned", "activity", "readyToAssign");

        JsonNode category = json.valueToTree(actions.clearCategoryTarget(new ClearCategoryTargetInput(f.budgetId, dining)));
        assertThat(fieldNames(category)).containsExactly("id", "groupId", "name", "hidden", "note", "sortOrder",
                "systemKind", "linkedAccountId", "target");
    }

    static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> it = node.propertyNames().iterator();
        it.forEachRemaining(names::add);
        return names;
    }
}
