package io.ynay.category;

import io.ynay.action.ActionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Category and category-group persistence plus the system-category rules (Ready to Assign,
 * credit card payment categories). Public so other streams (month math, targets) can reuse the
 * lookups and the {@link CategoryDto} mapping.
 */
@Service
public class CategoryService {

    public static final String INTERNAL_GROUP_NAME = "Internal";
    public static final String READY_TO_ASSIGN_NAME = "Inflow: Ready to Assign";
    public static final String CREDIT_CARD_PAYMENTS_GROUP_NAME = "Credit Card Payments";

    /** Starter groups and categories created with a new budget (in order). */
    static final Map<String, List<String>> STARTER_CATEGORIES;

    static {
        var m = new LinkedHashMap<String, List<String>>();
        m.put("Bills", List.of("Rent/Mortgage", "Utilities", "Phone", "Internet", "Insurance"));
        m.put("Needs", List.of("Groceries", "Transportation", "Medical"));
        m.put("Wants", List.of("Dining Out", "Entertainment", "Shopping"));
        m.put("Savings", List.of("Emergency Fund", "Vacation"));
        STARTER_CATEGORIES = java.util.Collections.unmodifiableMap(m);
    }

    private static final String CATEGORY_COLUMNS = """
            c.id, c.group_id, c.name, c.hidden, c.note, c.sort_order, c.system_kind, c.linked_account_id,
            c.target_type, c.target_amount, c.target_date""";

    private final JdbcClient jdbc;

    public CategoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ budget setup

    /** Creates the system groups/categories (and optionally the starter categories) of a new budget. */
    public void initializeBudget(UUID budgetId, boolean withDefaultCategories) {
        UUID internal = insertGroup(budgetId, INTERNAL_GROUP_NAME, true, 0, CategoryGroupDto.SystemKind.INTERNAL);
        insertCategory(budgetId, internal, READY_TO_ASSIGN_NAME, 0, CategoryDto.SystemKind.READY_TO_ASSIGN, null);
        insertGroup(budgetId, CREDIT_CARD_PAYMENTS_GROUP_NAME, false, 1, CategoryGroupDto.SystemKind.CREDIT_CARD_PAYMENTS);
        if (withDefaultCategories) {
            int groupOrder = 2;
            for (var entry : STARTER_CATEGORIES.entrySet()) {
                UUID group = insertGroup(budgetId, entry.getKey(), false, groupOrder++, null);
                int order = 0;
                for (String name : entry.getValue()) {
                    insertCategory(budgetId, group, name, order++, null, null);
                }
            }
        }
    }

    UUID insertGroup(UUID budgetId, String name, boolean hidden, int sortOrder, CategoryGroupDto.SystemKind kind) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into category_group (id, budget_id, name, hidden, sort_order, system_kind)
                values (?, ?, ?, ?, ?, ?)""")
                .params(id, budgetId, name, hidden, sortOrder, kind == null ? null : kind.name())
                .update();
        return id;
    }

    UUID insertCategory(UUID budgetId, UUID groupId, String name, int sortOrder, CategoryDto.SystemKind kind,
                        UUID linkedAccountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into category (id, budget_id, group_id, name, sort_order, system_kind, linked_account_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, budgetId, groupId, name, sortOrder, kind == null ? null : kind.name(), linkedAccountId)
                .update();
        return id;
    }

    // ------------------------------------------------------------------ system categories

    /** Id of the "Inflow: Ready to Assign" category of the budget. */
    public UUID readyToAssignId(UUID budgetId) {
        return jdbc.sql("select id from category where budget_id = ? and system_kind = 'READY_TO_ASSIGN'")
                .param(budgetId)
                .query(UUID.class)
                .single();
    }

    UUID systemGroupId(UUID budgetId, CategoryGroupDto.SystemKind kind) {
        return jdbc.sql("select id from category_group where budget_id = ? and system_kind = ?")
                .params(budgetId, kind.name())
                .query(UUID.class)
                .single();
    }

    /** Creates the payment category for an on-budget credit account (idempotent). */
    public UUID ensureCreditCardPaymentCategory(UUID budgetId, UUID accountId, String accountName) {
        Optional<UUID> existing = paymentCategoryId(accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID group = systemGroupId(budgetId, CategoryGroupDto.SystemKind.CREDIT_CARD_PAYMENTS);
        return insertCategory(budgetId, group, accountName, nextCategoryOrder(group),
                CategoryDto.SystemKind.CREDIT_CARD_PAYMENT, accountId);
    }

    /** The credit card payment category linked to an account, if any. */
    public Optional<UUID> paymentCategoryId(UUID accountId) {
        return jdbc.sql("select id from category where linked_account_id = ? and system_kind = 'CREDIT_CARD_PAYMENT'")
                .param(accountId)
                .query(UUID.class)
                .optional();
    }

    /** Keeps the payment category name in sync with its account. */
    public void renameLinkedCategory(UUID accountId, String name) {
        jdbc.sql("update category set name = ? where linked_account_id = ? and system_kind = 'CREDIT_CARD_PAYMENT'")
                .params(name, accountId)
                .update();
    }

    /** Hides the payment category of a closed account (and shows it again on reopen). */
    public void setLinkedCategoryHidden(UUID accountId, boolean hidden) {
        jdbc.sql("update category set hidden = ? where linked_account_id = ? and system_kind = 'CREDIT_CARD_PAYMENT'")
                .params(hidden, accountId)
                .update();
    }

    // ------------------------------------------------------------------ lookups

    public Optional<CategoryDto> find(UUID budgetId, UUID categoryId) {
        if (categoryId == null) {
            return Optional.empty();
        }
        return jdbc.sql("select " + CATEGORY_COLUMNS + " from category c where c.budget_id = ? and c.id = ?")
                .params(budgetId, categoryId)
                .query(CategoryService::mapCategory)
                .optional();
    }

    /**
     * Loads a category of the given budget, failing with NOT_FOUND if it doesn't exist there.
     * Use this to validate category ids coming from callers.
     */
    public CategoryDto require(UUID budgetId, UUID categoryId) {
        if (categoryId == null) {
            throw ActionException.invalid("categoryId is required");
        }
        return find(budgetId, categoryId).orElseThrow(() -> ActionException.notFound("Category " + categoryId));
    }

    public Optional<CategoryGroupDto> findGroup(UUID budgetId, UUID groupId) {
        if (groupId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select id, name, hidden, sort_order, system_kind from category_group
                        where budget_id = ? and id = ?""")
                .params(budgetId, groupId)
                .query(CategoryService::mapGroup)
                .optional()
                .map(g -> g.withCategories(categoriesOfGroup(g.id())));
    }

    public CategoryGroupDto requireGroup(UUID budgetId, UUID groupId) {
        if (groupId == null) {
            throw ActionException.invalid("groupId is required");
        }
        return findGroup(budgetId, groupId).orElseThrow(() -> ActionException.notFound("Category group " + groupId));
    }

    List<CategoryDto> categoriesOfGroup(UUID groupId) {
        return jdbc.sql("select " + CATEGORY_COLUMNS + " from category c where c.group_id = ? order by c.sort_order, c.name")
                .param(groupId)
                .query(CategoryService::mapCategory)
                .list();
    }

    /** All groups with their categories, ordered. The INTERNAL group is always included. */
    public List<CategoryGroupDto> list(UUID budgetId, boolean includeHidden) {
        List<CategoryGroupDto> groups = jdbc.sql("""
                        select id, name, hidden, sort_order, system_kind from category_group
                        where budget_id = ? order by sort_order, name""")
                .param(budgetId)
                .query(CategoryService::mapGroup)
                .list();
        Map<UUID, List<CategoryDto>> byGroup = new LinkedHashMap<>();
        for (CategoryDto c : jdbc.sql("select " + CATEGORY_COLUMNS
                        + " from category c where c.budget_id = ? order by c.sort_order, c.name")
                .param(budgetId)
                .query(CategoryService::mapCategory)
                .list()) {
            byGroup.computeIfAbsent(c.groupId(), k -> new ArrayList<>()).add(c);
        }
        List<CategoryGroupDto> result = new ArrayList<>();
        for (CategoryGroupDto g : groups) {
            boolean internal = g.systemKind() == CategoryGroupDto.SystemKind.INTERNAL;
            if (g.hidden() && !includeHidden && !internal) {
                continue;
            }
            List<CategoryDto> cats = byGroup.getOrDefault(g.id(), List.of()).stream()
                    .filter(c -> includeHidden || !c.hidden() || c.isReadyToAssign())
                    .toList();
            result.add(g.withCategories(cats));
        }
        return result;
    }

    int nextCategoryOrder(UUID groupId) {
        return jdbc.sql("select coalesce(max(sort_order) + 1, 0) from category where group_id = ?")
                .param(groupId)
                .query(Integer.class)
                .single();
    }

    int nextGroupOrder(UUID budgetId) {
        return jdbc.sql("select coalesce(max(sort_order) + 1, 0) from category_group where budget_id = ?")
                .param(budgetId)
                .query(Integer.class)
                .single();
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes a (non-system) category. With {@code reassignTo}, its transactions, split lines and
     * month assignments move to that category (assignments are summed per month; when the target is
     * Ready to Assign the assignments are simply dropped, which returns the money to Ready to Assign).
     * Without, its transactions become uncategorized and its assignments are dropped.
     */
    void delete(UUID budgetId, CategoryDto category, CategoryDto reassignTo) {
        UUID target = reassignTo == null ? null : reassignTo.id();
        jdbc.sql("update txn set category_id = ?, updated_at = now() where budget_id = ? and category_id = ?")
                .params(target, budgetId, category.id())
                .update();
        jdbc.sql("""
                        update txn_split s set category_id = ? from txn t
                        where s.transaction_id = t.id and t.budget_id = ? and s.category_id = ?""")
                .params(target, budgetId, category.id())
                .update();
        if (reassignTo != null && !reassignTo.isReadyToAssign()) {
            jdbc.sql("""
                            insert into month_assignment (budget_id, category_id, month, assigned)
                            select budget_id, ?, month, assigned from month_assignment where category_id = ?
                            on conflict (category_id, month)
                            do update set assigned = month_assignment.assigned + excluded.assigned""")
                    .params(target, category.id())
                    .update();
        }
        jdbc.sql("delete from category where id = ?").param(category.id()).update();
    }

    // ------------------------------------------------------------------ mapping

    static CategoryDto mapCategory(ResultSet rs, int row) throws SQLException {
        String kind = rs.getString("system_kind");
        String targetType = rs.getString("target_type");
        TargetDto target = null;
        if (targetType != null) {
            LocalDate date = rs.getObject("target_date", LocalDate.class);
            long amount = rs.getLong("target_amount");
            target = new TargetDto(TargetType.valueOf(targetType), amount, date);
        }
        return new CategoryDto(
                rs.getObject("id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("hidden"),
                rs.getString("note"),
                rs.getInt("sort_order"),
                kind == null ? null : CategoryDto.SystemKind.valueOf(kind),
                rs.getObject("linked_account_id", UUID.class),
                target);
    }

    static CategoryGroupDto mapGroup(ResultSet rs, int row) throws SQLException {
        String kind = rs.getString("system_kind");
        return new CategoryGroupDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("hidden"),
                rs.getInt("sort_order"),
                kind == null ? null : CategoryGroupDto.SystemKind.valueOf(kind),
                List.of());
    }
}
