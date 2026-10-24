package io.ynay.category;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountDto;
import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.budget.LedgerTestSupport;
import io.ynay.transaction.SplitInput;
import io.ynay.transaction.TransactionDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryActionsTest extends LedgerTestSupport {

    UUID budgetId;

    @BeforeEach
    void setUp() {
        loginAsNewUser();
        budgetId = newBudget();
    }

    private CategoryGroupDto group(String name) {
        return allGroups(budgetId).stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private void assign(UUID categoryId, String month, long amount) {
        jdbc.sql("insert into month_assignment (budget_id, category_id, month, assigned) values (?, ?, ?, ?)")
                .params(budgetId, categoryId, LocalDate.parse(month + "-01"), amount)
                .update();
    }

    private Long assigned(UUID categoryId, String month) {
        return jdbc.sql("select assigned from month_assignment where category_id = ? and month = ?")
                .params(categoryId, LocalDate.parse(month + "-01"))
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    @Test
    void createUpdateAndListCategories() {
        CategoryGroupDto group = categories.createCategoryGroup(
                new CategoryActions.CreateCategoryGroupInput(budgetId, "Kids"));
        assertThat(group.systemKind()).isNull();
        assertThat(group.categories()).isEmpty();
        assertThat(group.sortOrder()).isGreaterThan(group("Savings").sortOrder());

        CategoryDto school = categories.createCategory(
                new CategoryActions.CreateCategoryInput(budgetId, group.id(), "School", "fees"));
        CategoryDto toys = categories.createCategory(
                new CategoryActions.CreateCategoryInput(budgetId, group.id(), "Toys", null));
        assertThat(school.note()).isEqualTo("fees");
        assertThat(school.target()).isNull();
        assertThat(toys.sortOrder()).isGreaterThan(school.sortOrder());
        assertCode(Code.CONFLICT, () -> categories.createCategory(
                new CategoryActions.CreateCategoryInput(budgetId, group.id(), "school", null)));

        CategoryDto moved = categories.updateCategory(new CategoryActions.UpdateCategoryInput(budgetId, toys.id(),
                "Toys & Games", group("Wants").id(), null, "", null));
        assertThat(moved.groupId()).isEqualTo(group("Wants").id());
        assertThat(moved.name()).isEqualTo("Toys & Games");
        assertThat(moved.note()).isNull();

        categories.updateCategory(new CategoryActions.UpdateCategoryInput(budgetId, school.id(), null, null, true,
                null, null));
        categories.updateCategoryGroup(new CategoryActions.UpdateCategoryGroupInput(budgetId, group("Savings").id(),
                null, true, null));

        List<CategoryGroupDto> visible = categories.listCategories(
                new CategoryActions.ListCategoriesInput(budgetId, false));
        assertThat(visible).extracting(CategoryGroupDto::name)
                .containsExactly("Internal", "Credit Card Payments", "Bills", "Needs", "Wants", "Kids");
        assertThat(visible.getLast().categories()).isEmpty();
        assertThat(allGroups(budgetId)).extracting(CategoryGroupDto::name).contains("Savings");
        assertThat(group("Kids").categories()).extracting(CategoryDto::name).containsExactly("School");

        CategoryGroupDto reordered = categories.updateCategoryGroup(new CategoryActions.UpdateCategoryGroupInput(
                budgetId, group.id(), "Children", null, -1));
        assertThat(reordered.name()).isEqualTo("Children");
        assertThat(allGroups(budgetId).getFirst().name()).isEqualTo("Children");
    }

    @Test
    void systemCategoriesAndGroupsAreProtected() {
        CategoryDto rta = category(budgetId, "Inflow: Ready to Assign");
        CategoryGroupDto internal = group("Internal");
        CategoryGroupDto cc = group("Credit Card Payments");
        AccountDto card = account(budgetId, "Visa", AccountType.CREDIT_CARD, 0);
        CategoryDto payment = category(budgetId, "Visa");
        assertThat(payment.linkedAccountId()).isEqualTo(card.id());

        assertCode(Code.INVALID_INPUT, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, rta.id(), "Income", null, null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, rta.id(), null, null, true, null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, payment.id(), "Other", null, null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, payment.id(), null, group("Bills").id(), null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.deleteCategory(
                new CategoryActions.DeleteCategoryInput(budgetId, rta.id(), null)));
        assertCode(Code.INVALID_INPUT, () -> categories.deleteCategory(
                new CategoryActions.DeleteCategoryInput(budgetId, payment.id(), null)));
        assertCode(Code.INVALID_INPUT, () -> categories.createCategory(
                new CategoryActions.CreateCategoryInput(budgetId, cc.id(), "Fake card", null)));
        assertCode(Code.INVALID_INPUT, () -> categories.createCategory(
                new CategoryActions.CreateCategoryInput(budgetId, internal.id(), "Income 2", null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, category(budgetId, "Groceries").id(), null, cc.id(), null, null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategoryGroup(
                new CategoryActions.UpdateCategoryGroupInput(budgetId, cc.id(), "Cards", null, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.updateCategoryGroup(
                new CategoryActions.UpdateCategoryGroupInput(budgetId, internal.id(), null, false, null)));
        assertCode(Code.INVALID_INPUT, () -> categories.deleteCategoryGroup(
                new CategoryActions.DeleteCategoryGroupInput(budgetId, internal.id(), null)));
        assertCode(Code.INVALID_INPUT, () -> categories.deleteCategoryGroup(
                new CategoryActions.DeleteCategoryGroupInput(budgetId, cc.id(), null)));

        // hiding the payment category and a note on RTA are fine
        assertThat(categories.updateCategory(new CategoryActions.UpdateCategoryInput(budgetId, payment.id(), null,
                null, true, null, null)).hidden()).isTrue();
        assertThat(categories.updateCategory(new CategoryActions.UpdateCategoryInput(budgetId, rta.id(),
                "Inflow: Ready to Assign", null, null, "income", null)).note()).isEqualTo("income");
    }

    @Test
    void deleteCategoryWithReassignMovesTransactionsSplitsAndAssignments() {
        UUID checking = account(budgetId, "Checking", AccountType.CHECKING, 1_000_000).id();
        UUID dining = category(budgetId, "Dining Out").id();
        UUID groceries = category(budgetId, "Groceries").id();
        UUID shopping = category(budgetId, "Shopping").id();

        TransactionDto plain = spend(budgetId, checking, D1, -10_000, "Diner", dining);
        TransactionDto splitTxn = split(budgetId, checking, -30_000, List.of(
                new SplitInput(-20_000L, dining, null, null),
                new SplitInput(-10_000L, shopping, null, null)));
        assign(dining, "2026-09", 50_000);
        assign(dining, "2026-10", 40_000);
        assign(groceries, "2026-10", 100_000);

        assertThat(categories.deleteCategory(new CategoryActions.DeleteCategoryInput(budgetId, dining, groceries)))
                .containsEntry("deleted", true);

        assertThat(txn(budgetId, plain.id()).categoryId()).isEqualTo(groceries);
        assertThat(txn(budgetId, splitTxn.id()).splits()).extracting(s -> s.categoryId())
                .containsExactly(groceries, shopping);
        assertThat(assigned(groceries, "2026-09")).isEqualTo(50_000);
        assertThat(assigned(groceries, "2026-10")).isEqualTo(140_000);
        assertThat(allGroups(budgetId).stream().flatMap(g -> g.categories().stream()))
                .extracting(CategoryDto::name).doesNotContain("Dining Out");
    }

    @Test
    void deleteCategoryWithoutReassignLeavesTransactionsUncategorized() {
        UUID checking = account(budgetId, "Checking", AccountType.CHECKING, 1_000_000).id();
        UUID dining = category(budgetId, "Dining Out").id();
        TransactionDto plain = spend(budgetId, checking, D1, -10_000, "Diner", dining);
        assign(dining, "2026-10", 40_000);

        categories.deleteCategory(new CategoryActions.DeleteCategoryInput(budgetId, dining, null));

        assertThat(txn(budgetId, plain.id()).categoryId()).isNull();
        assertThat(assigned(dining, "2026-10")).isNull();
        assertCode(Code.NOT_FOUND, () -> categories.deleteCategory(
                new CategoryActions.DeleteCategoryInput(budgetId, dining, null)));
    }

    @Test
    void reassigningToReadyToAssignReleasesAssignedMoney() {
        UUID checking = account(budgetId, "Checking", AccountType.CHECKING, 0).id();
        UUID vacation = category(budgetId, "Vacation").id();
        TransactionDto refund = spend(budgetId, checking, D1, 5_000, "Airline", vacation);
        assign(vacation, "2026-10", 40_000);

        categories.deleteCategory(new CategoryActions.DeleteCategoryInput(budgetId, vacation, rta(budgetId)));

        assertThat(txn(budgetId, refund.id()).categoryId()).isEqualTo(rta(budgetId));
        assertThat(jdbc.sql("select count(*) from month_assignment where budget_id = ?").param(budgetId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void deleteGroupReassignsAllItsCategories() {
        UUID checking = account(budgetId, "Checking", AccountType.CHECKING, 1_000_000).id();
        CategoryGroupDto wants = group("Wants");
        UUID dining = category(budgetId, "Dining Out").id();
        UUID fun = category(budgetId, "Entertainment").id();
        UUID groceries = category(budgetId, "Groceries").id();
        TransactionDto a = spend(budgetId, checking, D1, -1_000, "A", dining);
        TransactionDto b = spend(budgetId, checking, D1, -2_000, "B", fun);
        assign(dining, "2026-10", 10_000);
        assign(fun, "2026-10", 5_000);

        assertCode(Code.INVALID_INPUT, () -> categories.deleteCategoryGroup(
                new CategoryActions.DeleteCategoryGroupInput(budgetId, wants.id(), dining)));
        categories.deleteCategoryGroup(new CategoryActions.DeleteCategoryGroupInput(budgetId, wants.id(), groceries));

        assertThat(txn(budgetId, a.id()).categoryId()).isEqualTo(groceries);
        assertThat(txn(budgetId, b.id()).categoryId()).isEqualTo(groceries);
        assertThat(assigned(groceries, "2026-10")).isEqualTo(15_000);
        assertThat(allGroups(budgetId)).extracting(CategoryGroupDto::name).doesNotContain("Wants");
    }

    @Test
    void categoryIdsFromOtherBudgetsAreRejected() {
        UUID otherBudget = newBudget();
        UUID foreignGroceries = category(otherBudget, "Groceries").id();
        UUID foreignGroup = group("Bills").id();
        assertCode(Code.NOT_FOUND, () -> categories.deleteCategory(
                new CategoryActions.DeleteCategoryInput(budgetId, foreignGroceries, null)));
        assertCode(Code.NOT_FOUND, () -> categories.deleteCategory(new CategoryActions.DeleteCategoryInput(
                budgetId, category(budgetId, "Groceries").id(), foreignGroceries)));
        assertCode(Code.NOT_FOUND, () -> categories.createCategory(
                new CategoryActions.CreateCategoryInput(otherBudget, foreignGroup, "X", null)));
        assertCode(Code.NOT_FOUND, () -> categories.updateCategory(new CategoryActions.UpdateCategoryInput(
                budgetId, category(budgetId, "Groceries").id(), null,
                allGroups(otherBudget).getLast().id(), null, null, null)));
    }
}
