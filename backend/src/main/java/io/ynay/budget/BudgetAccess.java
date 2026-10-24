package io.ynay.budget;

import io.ynay.action.ActionException;
import io.ynay.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Authorization checks for budget-scoped actions. Every action that takes a {@code budgetId}
 * must call {@link #requireMember(UUID)} (or {@link #requireOwner(UUID)}) before touching data.
 */
@Component
public class BudgetAccess {

    public enum Role { OWNER, EDITOR }

    private final JdbcClient jdbc;

    public BudgetAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Role requireMember(UUID budgetId) {
        if (budgetId == null) {
            throw ActionException.invalid("budgetId is required");
        }
        return role(budgetId, CurrentUser.id())
                .orElseThrow(() -> ActionException.notFound("Budget " + budgetId));
    }

    public void requireOwner(UUID budgetId) {
        if (requireMember(budgetId) != Role.OWNER) {
            throw ActionException.forbidden("Only the budget owner can do this");
        }
    }

    public Optional<Role> role(UUID budgetId, UUID userId) {
        return jdbc.sql("select role from budget_member where budget_id = ? and user_id = ?")
                .param(budgetId)
                .param(userId)
                .query(String.class)
                .optional()
                .map(Role::valueOf);
    }
}
