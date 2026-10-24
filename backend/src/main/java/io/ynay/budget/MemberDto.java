package io.ynay.budget;

import java.util.UUID;

/** {@code Member} of a budget. */
public record MemberDto(UUID userId, String username, String displayName, BudgetAccess.Role role) {
}
