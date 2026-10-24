package io.ynay.budget;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.category.CategoryService;
import io.ynay.security.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Budget and membership actions. */
@Component
public class BudgetActions {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final BudgetAccess access;
    private final CategoryService categories;
    private final JdbcClient jdbc;

    public BudgetActions(BudgetAccess access, CategoryService categories, JdbcClient jdbc) {
        this.access = access;
        this.categories = categories;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ budgets

    @McpAction(name = "list_budgets", readOnly = true, description = """
            List the budgets the current user is a member of (with their role). Start here: almost every \
            other action needs a budgetId.""")
    public List<BudgetDto> listBudgets() {
        return jdbc.sql("""
                        select b.id, b.name, b.currency_code, m.role, b.created_at
                        from budget b join budget_member m on m.budget_id = b.id
                        where m.user_id = ? order by lower(b.name), b.created_at""")
                .param(CurrentUser.id())
                .query(BudgetActions::mapBudget)
                .list();
    }

    public record GetBudgetInput(@ActionParam("Budget id") UUID budgetId) {}

    @McpAction(name = "get_budget", readOnly = true, description = "Get one budget (name, currency, your role).")
    public BudgetDto getBudget(GetBudgetInput in) {
        access.requireMember(in.budgetId());
        return load(in.budgetId());
    }

    public record CreateBudgetInput(
            @ActionParam("Budget name") String name,
            @ActionParam(value = "ISO 4217 currency code (default USD)", required = false) String currencyCode,
            @ActionParam(value = "Create starter category groups (Bills, Needs, Wants, Savings); default true",
                    required = false) Boolean withDefaultCategories) {}

    @McpAction(name = "create_budget", description = """
            Create a new budget owned by the current user. It always gets the system categories \
            ("Inflow: Ready to Assign" in the hidden Internal group, and the Credit Card Payments group) and, \
            unless withDefaultCategories=false, starter groups Bills, Needs, Wants and Savings with typical \
            categories. Add accounts next with create_account.""")
    public BudgetDto createBudget(CreateBudgetInput in) {
        String name = Inputs.requireName(in.name(), "name");
        String currency = currency(in.currencyCode() == null ? "USD" : in.currencyCode());
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into budget (id, name, currency_code) values (?, ?, ?)")
                .params(id, name, currency)
                .update();
        jdbc.sql("insert into budget_member (budget_id, user_id, role) values (?, ?, 'OWNER')")
                .params(id, CurrentUser.id())
                .update();
        categories.initializeBudget(id, in.withDefaultCategories() == null || in.withDefaultCategories());
        return load(id);
    }

    public record UpdateBudgetInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "New ISO 4217 currency code", required = false) String currencyCode) {}

    @McpAction(name = "update_budget", description = "Rename a budget or change its currency code. "
            + "Omitted fields are unchanged. Amounts are not converted.")
    public BudgetDto updateBudget(UpdateBudgetInput in) {
        access.requireMember(in.budgetId());
        if (in.name() != null) {
            jdbc.sql("update budget set name = ? where id = ?")
                    .params(Inputs.requireName(in.name(), "name"), in.budgetId())
                    .update();
        }
        if (in.currencyCode() != null) {
            jdbc.sql("update budget set currency_code = ? where id = ?")
                    .params(currency(in.currencyCode()), in.budgetId())
                    .update();
        }
        return load(in.budgetId());
    }

    public record DeleteBudgetInput(@ActionParam("Budget id") UUID budgetId) {}

    @McpAction(name = "delete_budget", destructive = true, description = """
            Permanently delete a budget with all its accounts, categories, payees, transactions and \
            assignments. Only an owner can do this.""")
    public Map<String, Object> deleteBudget(DeleteBudgetInput in) {
        access.requireOwner(in.budgetId());
        jdbc.sql("delete from budget where id = ?").param(in.budgetId()).update();
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ members

    public record ListBudgetMembersInput(@ActionParam("Budget id") UUID budgetId) {}

    @McpAction(name = "list_budget_members", readOnly = true, description = "List the users who share a budget.")
    public List<MemberDto> listBudgetMembers(ListBudgetMembersInput in) {
        access.requireMember(in.budgetId());
        return members(in.budgetId());
    }

    public record ShareBudgetInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Username of an existing YNAY user (they must have logged in once)") String username,
            @ActionParam(value = "OWNER or EDITOR (default EDITOR)", required = false) BudgetAccess.Role role) {}

    @McpAction(name = "share_budget", description = """
            Share a budget with another user, or change the role of an existing member. Owners can manage \
            members and delete the budget; editors can do everything else. Only an owner can share. The last \
            owner cannot be demoted.""")
    public List<MemberDto> shareBudget(ShareBudgetInput in) {
        access.requireOwner(in.budgetId());
        String username = Inputs.requireName(in.username(), "username");
        BudgetAccess.Role role = in.role() == null ? BudgetAccess.Role.EDITOR : in.role();
        List<UUID> users = jdbc.sql("select id from app_user where lower(username) = lower(?)")
                .param(username)
                .query(UUID.class)
                .list();
        if (users.isEmpty()) {
            throw ActionException.notFound("User '" + username + "'");
        }
        if (users.size() > 1) {
            throw ActionException.conflict("More than one user is named '" + username + "'");
        }
        UUID userId = users.getFirst();
        if (role != BudgetAccess.Role.OWNER) {
            requireAnotherOwner(in.budgetId(), userId);
        }
        jdbc.sql("""
                        insert into budget_member (budget_id, user_id, role) values (?, ?, ?)
                        on conflict (budget_id, user_id) do update set role = excluded.role""")
                .params(in.budgetId(), userId, role.name())
                .update();
        return members(in.budgetId());
    }

    public record RemoveBudgetMemberInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("User id of the member to remove (from list_budget_members)") UUID userId) {}

    @McpAction(name = "remove_budget_member", destructive = true, description = """
            Remove a user from a budget. Only an owner can do this, and the last owner cannot be removed.""")
    public List<MemberDto> removeBudgetMember(RemoveBudgetMemberInput in) {
        access.requireOwner(in.budgetId());
        if (in.userId() == null) {
            throw ActionException.invalid("userId is required");
        }
        if (access.role(in.budgetId(), in.userId()).isEmpty()) {
            throw ActionException.notFound("Member " + in.userId());
        }
        requireAnotherOwner(in.budgetId(), in.userId());
        jdbc.sql("delete from budget_member where budget_id = ? and user_id = ?")
                .params(in.budgetId(), in.userId())
                .update();
        return members(in.budgetId());
    }

    /** Fails if {@code userId} is the budget's only owner (so it can't be demoted or removed). */
    private void requireAnotherOwner(UUID budgetId, UUID userId) {
        long otherOwners = jdbc.sql("""
                        select count(*) from budget_member
                        where budget_id = ? and role = 'OWNER' and user_id <> ?""")
                .params(budgetId, userId)
                .query(Long.class)
                .single();
        if (otherOwners == 0) {
            throw ActionException.conflict("A budget must keep at least one owner");
        }
    }

    // ------------------------------------------------------------------ helpers

    private BudgetDto load(UUID budgetId) {
        return jdbc.sql("""
                        select b.id, b.name, b.currency_code, m.role, b.created_at
                        from budget b join budget_member m on m.budget_id = b.id
                        where b.id = ? and m.user_id = ?""")
                .params(budgetId, CurrentUser.id())
                .query(BudgetActions::mapBudget)
                .optional()
                .orElseThrow(() -> ActionException.notFound("Budget " + budgetId));
    }

    private List<MemberDto> members(UUID budgetId) {
        return jdbc.sql("""
                        select u.id, u.username, u.display_name, m.role
                        from budget_member m join app_user u on u.id = m.user_id
                        where m.budget_id = ? order by m.role desc, lower(u.username)""")
                .param(budgetId)
                .query((rs, n) -> new MemberDto(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        BudgetAccess.Role.valueOf(rs.getString("role"))))
                .list();
    }

    private static String currency(String code) {
        String c = code.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(c).matches()) {
            throw ActionException.invalid("currencyCode must be a 3-letter ISO 4217 code, e.g. USD or EUR");
        }
        return c;
    }

    private static BudgetDto mapBudget(ResultSet rs, int row) throws SQLException {
        return new BudgetDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("currency_code"),
                BudgetAccess.Role.valueOf(rs.getString("role")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
