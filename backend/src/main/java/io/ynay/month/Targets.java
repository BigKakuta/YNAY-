package io.ynay.month;

import io.ynay.category.TargetDto;
import java.time.YearMonth;

/** {@code targetNeeded(c, m)}: how much still has to be assigned in month m to meet the target. */
final class Targets {

    private Targets() {
    }

    static long needed(TargetDto target, YearMonth month, long carryover, long assigned) {
        if (target == null) {
            return 0;
        }
        long x = target.amount();
        return switch (target.type()) {
            case MONTHLY_SAVINGS -> Math.max(x - assigned, 0);
            case MONTHLY_SPENDING, TARGET_BALANCE -> Math.max(x - (carryover + assigned), 0);
            case TARGET_BALANCE_BY_DATE -> {
                if (target.date() == null) {
                    yield 0;
                }
                YearMonth due = YearMonth.from(target.date());
                if (month.isAfter(due)) {
                    yield 0;
                }
                long months = Math.max(month.until(due, java.time.temporal.ChronoUnit.MONTHS) + 1, 1);
                yield Math.max(Math.ceilDiv(x - carryover, months) - assigned, 0);
            }
        };
    }
}
