package io.ynay.category;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Category and category-group actions. */
@Component
public class CategoryActions {

    private final BudgetAccess access;
    private final CategoryService categories;
    private final JdbcClient jdbc;

    public CategoryActions(BudgetAccess access, CategoryService categories, JdbcClient jdbc) {
        this.access = access;
        this.categories = categories;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ list

    public record ListCategoriesInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "Also return hidden groups and categories (default false)", required = false)
            Boolean includeHidden) {}

    @McpAction(name = "list_categories", readOnly = true, description = """
            List the budget's category groups with their categories, in display order. Use this to find \
            category ids before categorizing transactions. Includes the system groups: the hidden INTERNAL \
            group (always returned) holds the "Inflow: Ready to Assign" category (systemKind READY_TO_ASSIGN) \
            which is the category for income; the CREDIT_CARD_PAYMENTS group holds one payment category per \
            on-budget credit card (systemKind CREDIT_CARD_PAYMENT, linkedAccountId = the card's account). \
            Hidden groups/categories are omitted unless includeHidden=true. Target amounts are milliunits.""")
    public java.util.List<CategoryGroupDto> listCategories(ListCategoriesInput in) {
        access.requireMember(in.budgetId());
        return categories.list(in.budgetId(), Boolean.TRUE.equals(in.includeHidden()));
    }

    // ------------------------------------------------------------------ groups

    public record CreateCategoryGroupInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Group name, e.g. \"Bills\"") String name) {}

    @McpAction(name = "create_category_group", description = """
            Create a new (empty) category group at the end of the list. Add categories to it with create_category.""")
    public CategoryGroupDto createCategoryGroup(CreateCategoryGroupInput in) {
        access.requireMember(in.budgetId());
        String name = Inputs.requireName(in.name(), "name");
        UUID id = categories.insertGroup(in.budgetId(), name, false, categories.nextGroupOrder(in.budgetId()), null);
        return categories.requireGroup(in.budgetId(), id);
    }

    public record UpdateCategoryGroupInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category group id") UUID groupId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "Hide or show the group", required = false) Boolean hidden,
            @ActionParam(value = "New sort position (lower comes first)", required = false) Integer sortOrder) {}

    @McpAction(name = "update_category_group", description = """
            Rename, hide/show or reorder a category group. Omitted fields are left unchanged. System groups \
            (INTERNAL, CREDIT_CARD_PAYMENTS) cannot be renamed and the INTERNAL group cannot be shown.""")
    public CategoryGroupDto updateCategoryGroup(UpdateCategoryGroupInput in) {
        access.requireMember(in.budgetId());
        CategoryGroupDto group = categories.requireGroup(in.budgetId(), in.groupId());
        if (in.name() != null) {
            String name = Inputs.requireName(in.name(), "name");
            if (group.systemKind() != null && !name.equals(group.name())) {
                throw ActionException.invalid("System group '" + group.name() + "' cannot be renamed");
            }
            jdbc.sql("update category_group set name = ? where id = ?").params(name, group.id()).update();
        }
        if (in.hidden() != null) {
            if (group.systemKind() == CategoryGroupDto.SystemKind.INTERNAL && !in.hidden()) {
                throw ActionException.invalid("The internal group is always hidden");
            }
            jdbc.sql("update category_group set hidden = ? where id = ?").params(in.hidden(), group.id()).update();
        }
        if (in.sortOrder() != null) {
            jdbc.sql("update category_group set sort_order = ? where id = ?").params(in.sortOrder(), group.id()).update();
        }
        return categories.requireGroup(in.budgetId(), group.id());
    }

    public record DeleteCategoryGroupInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category group id") UUID groupId,
            @ActionParam(value = "Category (outside this group) that receives the transactions and assigned "
                    + "money of every category in the group. Omit to leave those transactions uncategorized.",
                    required = false) UUID reassignToCategoryId) {}

    @McpAction(name = "delete_category_group", destructive = true, description = """
            Delete a category group and all of its categories. With reassignToCategoryId, every transaction, \
            split line and monthly assigned amount of the deleted categories moves to that category (assigned \
            amounts are added per month); without it the transactions become uncategorized and the assigned \
            money returns to Ready to Assign. System groups cannot be deleted.""")
    public Map<String, Object> deleteCategoryGroup(DeleteCategoryGroupInput in) {
        access.requireMember(in.budgetId());
        CategoryGroupDto group = categories.requireGroup(in.budgetId(), in.groupId());
        if (group.systemKind() != null) {
            throw ActionException.invalid("System group '" + group.name() + "' cannot be deleted");
        }
        CategoryDto target = null;
        if (in.reassignToCategoryId() != null) {
            target = categories.require(in.budgetId(), in.reassignToCategoryId());
            if (target.groupId().equals(group.id())) {
                throw ActionException.invalid("reassignToCategoryId must be a category outside the deleted group");
            }
        }
        for (CategoryDto c : group.categories()) {
            categories.delete(in.budgetId(), c, target);
        }
        jdbc.sql("delete from category_group where id = ?").param(group.id()).update();
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ categories

    public record CreateCategoryInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Id of the (non-system) category group to add the category to") UUID groupId,
            @ActionParam("Category name, e.g. \"Groceries\"") String name,
            @ActionParam(value = "Free-text note", required = false) String note) {}

    @McpAction(name = "create_category", description = """
            Create a category at the end of a category group. Names must be unique within the group. \
            Categories cannot be added to the system groups (credit card payment categories are created \
            automatically with credit card accounts).""")
    public CategoryDto createCategory(CreateCategoryInput in) {
        access.requireMember(in.budgetId());
        CategoryGroupDto group = categories.requireGroup(in.budgetId(), in.groupId());
        if (group.systemKind() != null) {
            throw ActionException.invalid("Categories cannot be added to the system group '" + group.name() + "'");
        }
        String name = Inputs.requireName(in.name(), "name");
        requireUniqueName(group, name, null);
        UUID id = categories.insertCategory(in.budgetId(), group.id(), name, categories.nextCategoryOrder(group.id()),
                null, null);
        if (in.note() != null) {
            jdbc.sql("update category set note = ? where id = ?").params(Inputs.blankToNull(in.note()), id).update();
        }
        return categories.require(in.budgetId(), id);
    }

    public record UpdateCategoryInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category id") UUID categoryId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "Move to this (non-system) category group", required = false) UUID groupId,
            @ActionParam(value = "Hide or show the category", required = false) Boolean hidden,
            @ActionParam(value = "New note (empty string clears it)", required = false) String note,
            @ActionParam(value = "New sort position within its group (lower comes first)", required = false)
            Integer sortOrder) {}

    @McpAction(name = "update_category", description = """
            Rename, move, hide/show, annotate or reorder a category. Omitted fields are unchanged. System \
            categories (Ready to Assign, credit card payments) cannot be renamed or moved; Ready to Assign \
            cannot be hidden. Credit card payment categories follow their account's name automatically.""")
    public CategoryDto updateCategory(UpdateCategoryInput in) {
        access.requireMember(in.budgetId());
        CategoryDto category = categories.require(in.budgetId(), in.categoryId());
        CategoryGroupDto group = categories.requireGroup(in.budgetId(), category.groupId());
        if (in.groupId() != null && !in.groupId().equals(category.groupId())) {
            if (category.isSystem()) {
                throw ActionException.invalid("System category '" + category.name() + "' cannot be moved");
            }
            group = categories.requireGroup(in.budgetId(), in.groupId());
            if (group.systemKind() != null) {
                throw ActionException.invalid("Categories cannot be moved into the system group '" + group.name() + "'");
            }
            requireUniqueName(group, in.name() != null ? Inputs.requireName(in.name(), "name") : category.name(),
                    category.id());
            jdbc.sql("update category set group_id = ?, sort_order = ? where id = ?")
                    .params(group.id(), categories.nextCategoryOrder(group.id()), category.id())
                    .update();
        }
        if (in.name() != null) {
            String name = Inputs.requireName(in.name(), "name");
            if (category.isSystem() && !name.equals(category.name())) {
                throw ActionException.invalid("System category '" + category.name() + "' cannot be renamed");
            }
            requireUniqueName(group, name, category.id());
            jdbc.sql("update category set name = ? where id = ?").params(name, category.id()).update();
        }
        if (in.hidden() != null) {
            if (category.isReadyToAssign() && in.hidden()) {
                throw ActionException.invalid("Ready to Assign cannot be hidden");
            }
            jdbc.sql("update category set hidden = ? where id = ?").params(in.hidden(), category.id()).update();
        }
        if (in.note() != null) {
            jdbc.sql("update category set note = ? where id = ?")
                    .params(Inputs.blankToNull(in.note()), category.id())
                    .update();
        }
        if (in.sortOrder() != null) {
            jdbc.sql("update category set sort_order = ? where id = ?").params(in.sortOrder(), category.id()).update();
        }
        return categories.require(in.budgetId(), category.id());
    }

    public record DeleteCategoryInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category id") UUID categoryId,
            @ActionParam(value = "Category that receives this category's transactions, split lines and assigned "
                    + "money. Omit to leave the transactions uncategorized.", required = false)
            UUID reassignToCategoryId) {}

    @McpAction(name = "delete_category", destructive = true, description = """
            Delete a category. With reassignToCategoryId, its transactions, split lines and monthly assigned \
            amounts move to that category (assigned amounts are added to the target's per month; reassigning to \
            Ready to Assign just releases the assigned money). Without it, its transactions become uncategorized \
            and its assigned money returns to Ready to Assign. System categories cannot be deleted.""")
    public Map<String, Object> deleteCategory(DeleteCategoryInput in) {
        access.requireMember(in.budgetId());
        CategoryDto category = categories.require(in.budgetId(), in.categoryId());
        if (category.isSystem()) {
            throw ActionException.invalid("System category '" + category.name() + "' cannot be deleted");
        }
        CategoryDto target = null;
        if (in.reassignToCategoryId() != null) {
            target = categories.require(in.budgetId(), in.reassignToCategoryId());
            if (target.id().equals(category.id())) {
                throw ActionException.invalid("reassignToCategoryId must be a different category");
            }
        }
        categories.delete(in.budgetId(), category, target);
        return Map.of("deleted", true);
    }

    private void requireUniqueName(CategoryGroupDto group, String name, UUID except) {
        boolean clash = group.categories().stream()
                .anyMatch(c -> !c.id().equals(except) && c.name().equalsIgnoreCase(name));
        if (clash) {
            throw ActionException.conflict("Category group '" + group.name() + "' already has a category named '"
                    + name + "'");
        }
    }
}
