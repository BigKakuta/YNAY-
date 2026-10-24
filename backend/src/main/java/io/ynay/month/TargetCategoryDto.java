package io.ynay.month;

import java.util.UUID;

/**
 * Same JSON shape as the {@code Category} DTO of docs/ARCHITECTURE.md. Kept as a separate type in
 * the budgeting package so it compiles independently of {@code io.ynay.category}; the two can be
 * unified later.
 */
public record TargetCategoryDto(
        UUID id,
        UUID groupId,
        String name,
        boolean hidden,
        String note,
        int sortOrder,
        String systemKind,
        UUID linkedAccountId,
        TargetDto target) {
}
