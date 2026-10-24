package io.ynay.category;

import java.util.List;
import java.util.UUID;

/** {@code CategoryGroup}. {@code systemKind} is INTERNAL, CREDIT_CARD_PAYMENTS or null. */
public record CategoryGroupDto(
        UUID id,
        String name,
        boolean hidden,
        int sortOrder,
        SystemKind systemKind,
        List<CategoryDto> categories) {

    public enum SystemKind { INTERNAL, CREDIT_CARD_PAYMENTS }

    public CategoryGroupDto withCategories(List<CategoryDto> categories) {
        return new CategoryGroupDto(id, name, hidden, sortOrder, systemKind, categories);
    }
}
