package io.ynay.month;

import io.ynay.category.TargetDto;
import java.util.UUID;

/** One category's numbers for one month. All amounts in milliunits. */
public record CategoryMonth(
        UUID categoryId,
        String name,
        boolean hidden,
        String systemKind,
        UUID linkedAccountId,
        long carryover,
        long assigned,
        long activity,
        long available,
        TargetDto target,
        long targetNeeded) {
}
