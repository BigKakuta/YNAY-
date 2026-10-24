package io.ynay.month;

import java.time.YearMonth;
import java.util.List;

/**
 * The budget screen for one month. {@code groups} excludes the INTERNAL group; the totals
 * ({@code assigned}, {@code activity}, {@code available}) are over all non-Ready-to-Assign categories.
 * {@code uncategorized} is the (usually negative) sum of uncategorized on-budget transactions in the month.
 */
public record MonthBudget(
        YearMonth month,
        String currencyCode,
        long readyToAssign,
        long income,
        long assigned,
        long activity,
        long available,
        long overspentLastMonth,
        long uncategorized,
        List<GroupMonth> groups) {
}
