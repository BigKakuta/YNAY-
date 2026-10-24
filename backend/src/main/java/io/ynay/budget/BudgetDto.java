package io.ynay.budget;

import java.time.Instant;
import java.util.UUID;

/** {@code Budget} as seen by the current user. */
public record BudgetDto(UUID id, String name, String currencyCode, BudgetAccess.Role role, Instant createdAt) {
}
