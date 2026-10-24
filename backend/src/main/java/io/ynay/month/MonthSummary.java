package io.ynay.month;

import java.time.YearMonth;

/** Compact per-month figures for month pickers and charts. */
public record MonthSummary(YearMonth month, long income, long assigned, long activity, long readyToAssign) {
}
