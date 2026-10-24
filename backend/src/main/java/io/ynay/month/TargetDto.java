package io.ynay.month;

import java.time.LocalDate;

/** {@code Target} DTO: {@code {type, amount, date}}. {@code date} is only set for TARGET_BALANCE_BY_DATE. */
public record TargetDto(TargetType type, long amount, LocalDate date) {
}
