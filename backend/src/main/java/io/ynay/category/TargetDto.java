package io.ynay.category;

import java.time.LocalDate;

/** {@code Target}: amount in milliunits, date only for {@code TARGET_BALANCE_BY_DATE}. */
public record TargetDto(TargetType type, long amount, LocalDate date) {
}
