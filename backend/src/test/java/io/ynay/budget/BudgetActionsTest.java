package io.ynay.budget;

import static org.assertj.core.api.Assertions.assertThat;

import io.ynay.account.AccountActions;
import io.ynay.account.AccountType;
import io.ynay.action.ActionException.Code;
import io.ynay.category.CategoryActions;
import io.ynay.category.CategoryDto;
import io.ynay.category.CategoryGroupDto;
import io.ynay.payee.PayeeActions;
import io.ynay.security.YnayUser;
import io.ynay.transaction.TransactionActions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BudgetActionsTest extends LedgerTestSupport {

    @Test
    void createBudgetMakesCreatorOwnerAndCreatesSystemAndStarterCategories() {
        YnayUser me = loginAsNewUser();
        BudgetDto budget = budgets.createBudget(new BudgetActions.CreateBudgetInput("Home", null, null));

        assertThat(budget.name()).isEqualTo("Home");
        assertThat(budget.currencyCode()).isEqualTo("USD");
        assertThat(budget.role()).isEqualTo(BudgetAccess.Role.OWNER);
        assertThat(budget.createdAt()).isNotNull();
        assertThat(budgets.listBudgets()).extracting(BudgetDto::id).containsExactly(budget.id());
        assertThat(budgets.listBudgetMembers(new BudgetActions.ListBudgetMembersInput(budget.id())))
                .containsExactly(new MemberDto(me.userId(), me.username(), me.username(), BudgetAccess.Role.OWNER));

        List<CategoryGroupDto> visible = categories.listCategories(
                new CategoryActions.ListCategoriesInput(budget.id(), null));
        assertThat(visible).extracting(CategoryGroupDto::name)
                .containsExactly("Internal", "Credit Card Payments", "Bills", "Needs", "Wants", "Savings");
        CategoryGroupDto internal = visible.getFirst();
        assertThat(internal.hidden()).isTrue();
        assertThat(internal.systemKind()).isEqualTo(CategoryGroupDto.SystemKind.INTERNAL);
        assertThat(internal.categories()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("Inflow: Ready to Assign");
            assertThat(c.systemKind()).isEqualTo(CategoryDto.SystemKind.READY_TO_ASSIGN);
        });
        assertThat(visible.get(1).systemKind()).isEqualTo(CategoryGroupDto.SystemKind.CREDIT_CARD_PAYMENTS);
        assertThat(visible.get(1).categories()).isEmpty();
        assertThat(visible.get(2).categories()).extracting(CategoryDto::name)
                .containsExactly("Rent/Mortgage", "Utilities", "Phone", "Internet", "Insurance");
        assertThat(visible.get(3).categories()).extracting(CategoryDto::name)
                .containsExactly("Groceries", "Transportation", "Medical");
        assertThat(visible.get(4).categories()).extracting(CategoryDto::name)
                .containsExactly("Dining Out", "Entertainment", "Shopping");
        assertThat(visible.get(5).categories()).extracting(CategoryDto::name)
                .containsExactly("Emergency Fund", "Vacation");
    }

    @Test
    void createBudgetWithoutDefaultCategoriesHasOnlySystemGroups() {
        loginAsNewUser();
        BudgetDto budget = budgets.createBudget(new BudgetActions.CreateBudgetInput("Bare", "eur", false));
        assertThat(budget.currencyCode()).isEqualTo("EUR");
        assertThat(allGroups(budget.id())).extracting(CategoryGroupDto::systemKind)
                .containsExactly(CategoryGroupDto.SystemKind.INTERNAL, CategoryGroupDto.SystemKind.CREDIT_CARD_PAYMENTS);
    }

    @Test
    void validatesBudgetInput() {
        loginAsNewUser();
        assertCode(Code.INVALID_INPUT, () -> budgets.createBudget(new BudgetActions.CreateBudgetInput(" ", null, null)));
        assertCode(Code.INVALID_INPUT,
                () -> budgets.createBudget(new BudgetActions.CreateBudgetInput("X", "DOLLARS", null)));
    }

    @Test
    void updateBudget() {
        loginAsNewUser();
        UUID id = newBudget();
        BudgetDto updated = budgets.updateBudget(new BudgetActions.UpdateBudgetInput(id, "Renamed", "CHF"));
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.currencyCode()).isEqualTo("CHF");
        assertThat(budgets.getBudget(new BudgetActions.GetBudgetInput(id))).isEqualTo(updated);
    }

    @Test
    void otherUsersCannotSeeOrTouchTheBudget() {
        loginAsNewUser();
        UUID budgetId = newBudget();
        UUID accountId = account(budgetId, "Checking", AccountType.CHECKING, 1000).id();
        UUID groceries = category(budgetId, "Groceries").id();

        loginAsNewUser();
        assertThat(budgets.listBudgets()).isEmpty();
        assertCode(Code.NOT_FOUND, () -> budgets.getBudget(new BudgetActions.GetBudgetInput(budgetId)));
        assertCode(Code.NOT_FOUND, () -> budgets.updateBudget(new BudgetActions.UpdateBudgetInput(budgetId, "x", null)));
        assertCode(Code.NOT_FOUND, () -> budgets.deleteBudget(new BudgetActions.DeleteBudgetInput(budgetId)));
        assertCode(Code.NOT_FOUND,
                () -> budgets.listBudgetMembers(new BudgetActions.ListBudgetMembersInput(budgetId)));
        assertCode(Code.NOT_FOUND, () -> accounts.listAccounts(new AccountActions.ListAccountsInput(budgetId, null)));
        assertCode(Code.NOT_FOUND,
                () -> categories.listCategories(new CategoryActions.ListCategoriesInput(budgetId, null)));
        assertCode(Code.NOT_FOUND, () -> payees.listPayees(new PayeeActions.ListPayeesInput(budgetId)));
        assertCode(Code.NOT_FOUND, () -> transactions.listTransactions(new TransactionActions.ListTransactionsInput(
                budgetId, null, null, null, null, null, null, null, null, null)));
        assertCode(Code.NOT_FOUND, () -> spend(budgetId, accountId, D1, -100, "x", groceries));

        // ids of the foreign budget can't be smuggled into an own budget either
        UUID mine = newBudget();
        UUID myAccount = account(mine, "Mine", AccountType.CHECKING, 0).id();
        assertCode(Code.NOT_FOUND, () -> spend(mine, accountId, D1, -100, "x", null));
        assertCode(Code.NOT_FOUND, () -> spend(mine, myAccount, D1, -100, "x", groceries));
        assertCode(Code.NOT_FOUND, () -> transfer(mine, myAccount, accountId, D1, -100, null));
    }

    @Test
    void shareBudgetByUsernameAndManageMembers() {
        YnayUser owner = loginAsNewUser();
        UUID budgetId = newBudget();
        YnayUser friend = createUser("friend-" + UUID.randomUUID().toString().substring(0, 6));

        assertCode(Code.NOT_FOUND,
                () -> budgets.shareBudget(new BudgetActions.ShareBudgetInput(budgetId, "nobody-" + UUID.randomUUID(), null)));
        List<MemberDto> members = budgets.shareBudget(
                new BudgetActions.ShareBudgetInput(budgetId, friend.username().toUpperCase(), null));
        assertThat(members).extracting(MemberDto::userId, MemberDto::role).containsExactly(
                org.assertj.core.groups.Tuple.tuple(owner.userId(), BudgetAccess.Role.OWNER),
                org.assertj.core.groups.Tuple.tuple(friend.userId(), BudgetAccess.Role.EDITOR));

        // the editor sees and uses the budget, but can't manage members or delete it
        loginAs(friend);
        assertThat(budgets.listBudgets()).singleElement()
                .satisfies(b -> assertThat(b.role()).isEqualTo(BudgetAccess.Role.EDITOR));
        account(budgetId, "Shared checking", AccountType.CHECKING, 0);
        assertCode(Code.FORBIDDEN,
                () -> budgets.shareBudget(new BudgetActions.ShareBudgetInput(budgetId, owner.username(), null)));
        assertCode(Code.FORBIDDEN,
                () -> budgets.removeBudgetMember(new BudgetActions.RemoveBudgetMemberInput(budgetId, owner.userId())));
        assertCode(Code.FORBIDDEN, () -> budgets.deleteBudget(new BudgetActions.DeleteBudgetInput(budgetId)));

        // the last owner can neither be removed nor demoted
        loginAs(owner);
        assertCode(Code.CONFLICT,
                () -> budgets.removeBudgetMember(new BudgetActions.RemoveBudgetMemberInput(budgetId, owner.userId())));
        assertCode(Code.CONFLICT, () -> budgets.shareBudget(
                new BudgetActions.ShareBudgetInput(budgetId, owner.username(), BudgetAccess.Role.EDITOR)));

        // promote friend, then the original owner may step down
        budgets.shareBudget(new BudgetActions.ShareBudgetInput(budgetId, friend.username(), BudgetAccess.Role.OWNER));
        members = budgets.removeBudgetMember(new BudgetActions.RemoveBudgetMemberInput(budgetId, owner.userId()));
        assertThat(members).extracting(MemberDto::userId).containsExactly(friend.userId());
        assertCode(Code.NOT_FOUND, () -> budgets.getBudget(new BudgetActions.GetBudgetInput(budgetId)));

        loginAs(friend);
        assertCode(Code.NOT_FOUND,
                () -> budgets.removeBudgetMember(new BudgetActions.RemoveBudgetMemberInput(budgetId, owner.userId())));
        assertThat(budgets.deleteBudget(new BudgetActions.DeleteBudgetInput(budgetId))).containsEntry("deleted", true);
        assertThat(budgets.listBudgets()).isEmpty();
    }

    @Test
    void deleteBudgetRemovesEverything() {
        loginAsNewUser();
        UUID budgetId = newBudget();
        UUID checking = account(budgetId, "Checking", AccountType.CHECKING, 10_000).id();
        UUID savings = account(budgetId, "Savings", AccountType.SAVINGS, 0).id();
        transfer(budgetId, checking, savings, D1, -5_000, null);
        budgets.deleteBudget(new BudgetActions.DeleteBudgetInput(budgetId));
        assertThat(jdbc.sql("select count(*) from txn where budget_id = ?").param(budgetId).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from category where budget_id = ?").param(budgetId).query(Long.class)
                .single()).isZero();
    }
}
