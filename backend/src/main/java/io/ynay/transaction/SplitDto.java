package io.ynay.transaction;

import java.util.UUID;

/** {@code Split}: one line of a split transaction (amount in milliunits). */
public record SplitDto(
        UUID id,
        long amount,
        UUID categoryId,
        String categoryName,
        UUID payeeId,
        String payeeName,
        String memo) {
}
