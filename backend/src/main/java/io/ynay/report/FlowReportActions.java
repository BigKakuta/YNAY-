package io.ynay.report;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import io.ynay.report.FlowLines.Range;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Cash flow (income, spending, saving) and line-level spending, the basis of the Reports page. */
@Component
public class FlowReportActions {

    static final int DEFAULT_PAYMENT_LIMIT = 2000;
    static final int MAX_PAYMENT_LIMIT = 10000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String KINDS = """
            Every transaction and split line on a budget account is one of: INCOME (categorized to Ready to \
            Assign), SAVING (a transfer to or from a tracking account of type ASSET or OTHER, such as an \
            investment account, whatever its category) or SPENDING (everything else, uncategorized included, \
            and payments to tracking loan/mortgage accounts). Transfers between budget accounts and \
            "Starting Balance" transactions are left out. Split lines use the parent's payee and memo when \
            they have none.""";

    public enum FlowKind { INCOME, SPENDING, SAVING }

    public record CashFlowInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("First month, YYYY-MM (inclusive)") YearMonth fromMonth,
            @ActionParam("Last month, YYYY-MM (inclusive)") YearMonth toMonth) {
    }

    /**
     * Per month: income received, spending (positive = spent, net of refunds), saving (positive = put into
     * savings/investments, net of withdrawals) and the part of spending that went to subscriptions of kind
     * SUBSCRIPTION.
     */
    public record CashFlowMonth(YearMonth month, long income, long spending, long saving, long subscriptions) {
    }

    /**
     * Amounts per month, kind, category, payee and (for SAVING) tracking account, signed like the month
     * totals: income and spending and saving are positive in their own direction.
     */
    public record CashFlowRow(YearMonth month, FlowKind kind, UUID categoryId, String categoryName, UUID groupId,
                              String groupName, UUID payeeId, String payeeName, UUID subscriptionId,
                              UUID accountId, String accountName, long amount, int count) {
    }

    public record CashFlow(List<CashFlowMonth> months, List<CashFlowRow> rows) {
    }

    public record PaymentsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "First month, YYYY-MM (inclusive); omit for no lower bound", required = false)
            YearMonth fromMonth,
            @ActionParam(value = "Last month, YYYY-MM (inclusive); omit for no upper bound", required = false)
            YearMonth toMonth,
            @ActionParam(value = "Only lines in these categories", required = false) List<UUID> categoryIds,
            @ActionParam(value = "Only lines with these payees", required = false) List<UUID> payeeIds,
            @ActionParam(value = "Case-insensitive text matched against memo, payee and category", required = false)
            String search,
            @ActionParam(value = "Only lines of at least this much spending (milliunits, e.g. 20000 = 20.00); "
                    + "leaves out refunds", required = false) Long minAmount,
            @ActionParam(value = "Maximum number of lines (default 2000, max 10000)", required = false)
            Integer limit) {
    }

    public record Review(Integer rating, String sensibleReason) {
    }

    /** One spending line; amount is positive for money spent and negative for a refund. */
    public record Payment(UUID transactionId, UUID splitId, LocalDate date, String time, UUID accountId,
                          String accountName, UUID payeeId, String payeeName, UUID categoryId, String categoryName,
                          String groupName, String memo, long amount, UUID subscriptionId, Review review) {
    }

    public record Payments(List<Payment> payments, boolean truncated) {
    }

    private final BudgetAccess access;
    private final JdbcClient jdbc;

    public FlowReportActions(BudgetAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @McpAction(name = "report_cash_flow", readOnly = true, description = """
            Where the money comes from and goes, from fromMonth to toMonth inclusive. """ + KINDS + """
             Returns {months: [{month, income, spending, saving, subscriptions}], rows: [{month, kind, \
            categoryId, categoryName, groupId, groupName, payeeId, payeeName, subscriptionId, accountId, \
            accountName, amount, count}]}. Amounts are milliunits and positive in their own direction: income \
            received, money spent (net of refunds), money put into savings (net of withdrawals). For SAVING \
            rows accountId/accountName is the tracking account. subscriptions is the spending on payees of \
            subscriptions of kind SUBSCRIPTION (see list_subscriptions).""")
    public CashFlow cashFlow(CashFlowInput in) {
        access.requireMember(in.budgetId());
        Range range = Range.of(in.fromMonth(), in.toMonth(), true);
        Set<UUID> plans = new HashSet<>(jdbc.sql("select id from subscription where budget_id = ? and kind = 'SUBSCRIPTION'")
                .param(in.budgetId())
                .query(UUID.class)
                .list());
        List<CashFlowRow> rows = jdbc.sql(FlowLines.SQL + """
                        select cast(date_trunc('month', date) as date) as month, kind, category_id, category_name,
                               group_id, group_name, payee_id, payee_name, subscription_id,
                               case when kind = 'SAVING' then transfer_account_id end as saving_account_id,
                               case when kind = 'SAVING' then transfer_account_name end as saving_account_name,
                               sum(amount) as amount, count(*) as n
                        from flow
                        where kind is not null
                        group by 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
                        order by 1, 2, sum(amount)""")
                .param("b", in.budgetId())
                .param("start", range.start())
                .param("end", range.end())
                .query((rs, n) -> {
                    FlowKind kind = FlowKind.valueOf(rs.getString("kind"));
                    long amount = rs.getLong("amount");
                    return new CashFlowRow(YearMonth.from(rs.getObject("month", LocalDate.class)), kind,
                            rs.getObject("category_id", UUID.class), rs.getString("category_name"),
                            rs.getObject("group_id", UUID.class), rs.getString("group_name"),
                            rs.getObject("payee_id", UUID.class), rs.getString("payee_name"),
                            rs.getObject("subscription_id", UUID.class),
                            rs.getObject("saving_account_id", UUID.class), rs.getString("saving_account_name"),
                            kind == FlowKind.INCOME ? amount : -amount, rs.getInt("n"));
                })
                .list();
        Map<YearMonth, long[]> totals = new HashMap<>();
        for (CashFlowRow r : rows) {
            long[] t = totals.computeIfAbsent(r.month(), m -> new long[4]);
            t[r.kind().ordinal()] += r.amount();
            if (r.kind() == FlowKind.SPENDING && r.subscriptionId() != null && plans.contains(r.subscriptionId())) {
                t[3] += r.amount();
            }
        }
        List<CashFlowMonth> months = new ArrayList<>();
        for (YearMonth m = range.from(); !m.isAfter(range.to()); m = m.plusMonths(1)) {
            long[] t = totals.getOrDefault(m, new long[4]);
            months.add(new CashFlowMonth(m, t[0], t[1], t[2], t[3]));
        }
        return new CashFlow(months, rows);
    }

    @McpAction(name = "report_payments", readOnly = true, description = """
            Individual spending lines (transactions and split lines), newest first, with optional filters \
            combined with AND: a month range, categories, payees, a text search and a minimum amount. \
            Spending as in report_cash_flow: """ + KINDS + """
             amount is positive milliunits for money spent and negative for a refund. Each line carries \
            its time of day when known, its subscriptionId (when the payee belongs to a subscription) and \
            the user's purchase review ({rating 1-5, sensibleReason}) or null; see review_purchase. \
            Returns {payments, truncated}; truncated is true when more lines matched than limit.""")
    public Payments payments(PaymentsInput in) {
        access.requireMember(in.budgetId());
        Range range = Range.of(in.fromMonth(), in.toMonth(), false);
        int limit = in.limit() == null ? DEFAULT_PAYMENT_LIMIT : in.limit();
        if (limit < 1 || limit > MAX_PAYMENT_LIMIT) {
            throw ActionException.invalid("limit must be between 1 and " + MAX_PAYMENT_LIMIT);
        }
        StringBuilder where = new StringBuilder(" where f.kind = 'SPENDING'");
        Map<String, Object> params = new HashMap<>();
        params.put("b", in.budgetId());
        params.put("start", range.start());
        params.put("end", range.end());
        if (in.categoryIds() != null) {
            if (in.categoryIds().isEmpty()) {
                return new Payments(List.of(), false);
            }
            where.append(" and f.category_id in (:categories)");
            params.put("categories", new LinkedHashSet<>(in.categoryIds()));
        }
        if (in.payeeIds() != null) {
            if (in.payeeIds().isEmpty()) {
                return new Payments(List.of(), false);
            }
            where.append(" and f.payee_id in (:payees)");
            params.put("payees", new LinkedHashSet<>(in.payeeIds()));
        }
        String search = Inputs.blankToNull(in.search());
        if (search != null) {
            where.append(" and (f.memo ilike :q or f.payee_name ilike :q or f.category_name ilike :q)");
            params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (in.minAmount() != null) {
            where.append(" and -f.amount >= :min");
            params.put("min", in.minAmount());
        }
        params.put("limit", limit + 1);
        List<Payment> rows = jdbc.sql(FlowLines.SQL + """
                        select f.*, r.rating, r.sensible_reason, r.transaction_id as reviewed
                        from flow f
                            left join purchase_review r on r.transaction_id = f.transaction_id
                                and r.split_id is not distinct from f.split_id"""
                        + where + """

                        order by f.date desc, f.time desc nulls last, f.amount, f.transaction_id, f.split_id
                        limit :limit""")
                .params(params)
                .query((rs, n) -> new Payment(
                        rs.getObject("transaction_id", UUID.class),
                        rs.getObject("split_id", UUID.class),
                        rs.getObject("date", LocalDate.class),
                        formatTime(rs.getObject("time", LocalTime.class)),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getObject("payee_id", UUID.class),
                        rs.getString("payee_name"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getString("group_name"),
                        rs.getString("memo"),
                        -rs.getLong("amount"),
                        rs.getObject("subscription_id", UUID.class),
                        rs.getObject("reviewed", UUID.class) == null ? null
                                : new Review(rs.getObject("rating", Integer.class), rs.getString("sensible_reason"))))
                .list();
        boolean truncated = rows.size() > limit;
        return new Payments(truncated ? rows.subList(0, limit) : rows, truncated);
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME);
    }
}
