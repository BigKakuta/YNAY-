package io.ynay.month;

import java.util.List;
import java.util.UUID;

/** A category group's totals for one month plus its categories. */
public record GroupMonth(
        UUID groupId,
        String name,
        boolean hidden,
        String systemKind,
        long assigned,
        long activity,
        long available,
        List<CategoryMonth> categories) {
}
