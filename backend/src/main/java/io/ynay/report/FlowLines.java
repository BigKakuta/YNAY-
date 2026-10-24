package io.ynay.report;

import io.ynay.action.ActionException;
import io.ynay.transaction.TransactionService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * The money-flow lines the newer reports are built on: every transaction and split line on an
 * on-budget account, classified as
 * <ul>
 *   <li>{@code SAVING}: a transfer to or from a tracking account of type ASSET or OTHER (investments,
 *       property), whatever its category;</li>
 *   <li>{@code INCOME}: categorized to Ready to Assign;</li>
 *   <li>{@code SPENDING}: everything else, categorized or not, including payments to tracking debt
 *       accounts (loans, mortgages).</li>
 * </ul>
 * Transfers between on-budget accounts and "Starting Balance" transactions are left out
 * ({@code kind} is null). Split lines take the parent's payee and memo when they have none.
 *
 * <p>{@link #SQL} defines the CTE {@code flow} for budget {@code :b} and dates in
 * {@code [:start, :end)}; append a {@code select ... from flow}.
 */
final class FlowLines {

    static final String SQL = """
            with line as (
                select t.id as transaction_id, cast(null as uuid) as split_id, t.date, t.time, t.account_id,
                       t.amount, t.category_id, t.payee_id, t.memo, t.transfer_account_id
                from txn t
                where t.budget_id = :b and not t.is_split and t.date >= :start and t.date < :end
                union all
                select t.id, s.id, t.date, t.time, t.account_id, s.amount, s.category_id,
                       coalesce(s.payee_id, t.payee_id), coalesce(s.memo, t.memo), null
                from txn_split s
                    join txn t on t.id = s.transaction_id
                where t.budget_id = :b and t.is_split and t.date >= :start and t.date < :end
            ), flow as (
                select l.*, a.name as account_name, p.name as payee_name, p.subscription_id,
                       c.name as category_name, g.id as group_id, g.name as group_name,
                       ta.name as transfer_account_name,
                       case
                           when ta.on_budget then null
                           when ta.type in ('ASSET', 'OTHER') then 'SAVING'
                           when ta.id is null and p.name = '%s' then null
                           when c.system_kind = 'READY_TO_ASSIGN' then 'INCOME'
                           else 'SPENDING'
                       end as kind
                from line l
                    join account a on a.id = l.account_id and a.on_budget
                    left join account ta on ta.id = l.transfer_account_id
                    left join payee p on p.id = l.payee_id
                    left join category c on c.id = l.category_id
                    left join category_group g on g.id = c.group_id
            )
            """.formatted(TransactionService.STARTING_BALANCE_PAYEE);

    static final int MAX_MONTH_RANGE = 1200;

    private FlowLines() {
    }

    /** A validated month range; either end may be open. */
    record Range(YearMonth from, YearMonth to) {

        static Range of(YearMonth from, YearMonth to, boolean required) {
            if (required && (from == null || to == null)) {
                throw ActionException.invalid("fromMonth and toMonth are required");
            }
            if (from != null && from.getYear() < 1900 || to != null && to.getYear() > 9998) {
                throw ActionException.invalid("Month out of range");
            }
            if (from != null && to != null) {
                if (from.isAfter(to)) {
                    throw ActionException.invalid("fromMonth must not be after toMonth");
                }
                if (from.until(to, ChronoUnit.MONTHS) >= MAX_MONTH_RANGE) {
                    throw ActionException.invalid("At most " + MAX_MONTH_RANGE + " months per request");
                }
            }
            return new Range(from, to);
        }

        LocalDate start() {
            return from == null ? LocalDate.of(1, 1, 1) : from.atDay(1);
        }

        LocalDate end() {
            return to == null ? LocalDate.of(9999, 12, 31) : to.plusMonths(1).atDay(1);
        }
    }
}
