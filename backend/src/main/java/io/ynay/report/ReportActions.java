package io.ynay.report;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-only reports over a range of months. */
@Component
public class ReportActions {

    static final int MAX_MONTH_RANGE = 1200;

    public record ReportInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("First month, YYYY-MM (inclusive)") YearMonth fromMonth,
            @ActionParam("Last month, YYYY-MM (inclusive)") YearMonth toMonth) {
    }

    public record SpendingRow(UUID categoryId, String categoryName, String groupName, long total) {
    }

    public record SpendingByCategory(List<SpendingRow> rows, long total) {
    }

    public record IncomeExpenseRow(YearMonth month, long income, long expense, long net) {
    }

    public record NetWorthRow(YearMonth month, long assets, long liabilities, long netWorth) {
    }

    /**
     * Categorized amounts on on-budget accounts (transactions and split lines), excluding transfers
     * between on-budget accounts, per category and month within [:start, :end).
     */
    private static final String CATEGORIZED_ACTIVITY = """
            select x.category_id, x.month, sum(x.amount) as amount
            from (
                select t.category_id, cast(date_trunc('month', t.date) as date) as month, t.amount
                from txn t
                join account a on a.id = t.account_id and a.on_budget
                left join account ta on ta.id = t.transfer_account_id
                where t.budget_id = :b and not t.is_split and t.category_id is not null
                  and (ta.id is null or not ta.on_budget)
                  and t.date >= :start and t.date < :end
                union all
                select s.category_id, cast(date_trunc('month', t.date) as date), s.amount
                from txn_split s
                join txn t on t.id = s.transaction_id
                join account a on a.id = t.account_id and a.on_budget
                where t.budget_id = :b and t.is_split and s.category_id is not null
                  and t.date >= :start and t.date < :end
            ) x
            group by x.category_id, x.month""";

    private record CategoryActivity(UUID categoryId, YearMonth month, long amount) {
    }

    private record CategoryName(String categoryName, String groupName, String systemKind) {
    }

    private final BudgetAccess access;
    private final JdbcClient jdbc;

    public ReportActions(BudgetAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @McpAction(name = "report_spending_by_category", readOnly = true, description = """
            Spending per category from fromMonth to toMonth inclusive, as positive milliunits (net of \
            refunds categorized to the same category). Counts categorized transactions and split lines \
            on on-budget accounts; excludes income (Ready to Assign), transfers between on-budget \
            accounts and uncategorized transactions. Rows with a non-zero total, largest first.""")
    public SpendingByCategory spendingByCategory(ReportInput in) {
        access.requireMember(in.budgetId());
        Range range = range(in);
        Map<UUID, CategoryName> names = categoryNames(in.budgetId());
        Map<UUID, Long> totals = new HashMap<>();
        for (CategoryActivity a : categorizedActivity(in.budgetId(), range)) {
            CategoryName n = names.get(a.categoryId());
            if (n == null || "READY_TO_ASSIGN".equals(n.systemKind())) {
                continue;
            }
            totals.merge(a.categoryId(), -a.amount(), Long::sum);
        }
        List<SpendingRow> rows = new ArrayList<>();
        long total = 0;
        for (var e : totals.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            CategoryName n = names.get(e.getKey());
            rows.add(new SpendingRow(e.getKey(), n.categoryName(), n.groupName(), e.getValue()));
            total += e.getValue();
        }
        rows.sort(Comparator.comparingLong(SpendingRow::total).reversed()
                .thenComparing(SpendingRow::categoryName, String.CASE_INSENSITIVE_ORDER));
        return new SpendingByCategory(rows, total);
    }

    @McpAction(name = "report_income_vs_expense", readOnly = true, description = """
            Income vs expense for every month from fromMonth to toMonth inclusive (milliunits). \
            income = inflows to Ready to Assign on on-budget accounts; expense = net categorized \
            spending as a positive number (same basis as report_spending_by_category); \
            net = income - expense.""")
    public List<IncomeExpenseRow> incomeVsExpense(ReportInput in) {
        access.requireMember(in.budgetId());
        Range range = range(in);
        Map<UUID, CategoryName> names = categoryNames(in.budgetId());
        Map<YearMonth, Long> income = new HashMap<>();
        Map<YearMonth, Long> expense = new HashMap<>();
        for (CategoryActivity a : categorizedActivity(in.budgetId(), range)) {
            CategoryName n = names.get(a.categoryId());
            if (n == null) {
                continue;
            }
            if ("READY_TO_ASSIGN".equals(n.systemKind())) {
                income.merge(a.month(), a.amount(), Long::sum);
            } else {
                expense.merge(a.month(), -a.amount(), Long::sum);
            }
        }
        List<IncomeExpenseRow> out = new ArrayList<>();
        for (YearMonth m = range.from(); !m.isAfter(range.to()); m = m.plusMonths(1)) {
            long i = income.getOrDefault(m, 0L);
            long e = expense.getOrDefault(m, 0L);
            out.add(new IncomeExpenseRow(m, i, e, i - e));
        }
        return out;
    }

    @McpAction(name = "report_net_worth", readOnly = true, description = """
            Net worth at the end of every month from fromMonth to toMonth inclusive, over all accounts \
            (on-budget and tracking, open and closed). assets = sum of positive account balances; \
            liabilities = sum of negative account balances (a negative number or 0); \
            netWorth = assets + liabilities. Milliunits.""")
    public List<NetWorthRow> netWorth(ReportInput in) {
        access.requireMember(in.budgetId());
        Range range = range(in);
        record Row(UUID accountId, YearMonth month, long amount) {
        }
        List<Row> rows = jdbc.sql("""
                        select t.account_id, cast(date_trunc('month', t.date) as date) as month, sum(t.amount) as amount
                        from txn t
                        where t.budget_id = :b and t.date < :end
                        group by t.account_id, cast(date_trunc('month', t.date) as date)
                        order by month""")
                .param("b", in.budgetId())
                .param("end", range.end())
                .query((rs, n) -> new Row(rs.getObject("account_id", UUID.class),
                        YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("amount")))
                .list();
        Map<UUID, Long> balances = new HashMap<>();
        int i = 0;
        // everything before the range is the opening balance
        while (i < rows.size() && rows.get(i).month().isBefore(range.from())) {
            balances.merge(rows.get(i).accountId(), rows.get(i).amount(), Long::sum);
            i++;
        }
        List<NetWorthRow> out = new ArrayList<>();
        for (YearMonth m = range.from(); !m.isAfter(range.to()); m = m.plusMonths(1)) {
            while (i < rows.size() && rows.get(i).month().equals(m)) {
                balances.merge(rows.get(i).accountId(), rows.get(i).amount(), Long::sum);
                i++;
            }
            long assets = 0, liabilities = 0;
            for (long b : balances.values()) {
                if (b > 0) {
                    assets += b;
                } else {
                    liabilities += b;
                }
            }
            out.add(new NetWorthRow(m, assets, liabilities, assets + liabilities));
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private record Range(YearMonth from, YearMonth to) {
        LocalDate start() {
            return from.atDay(1);
        }

        LocalDate end() {
            return to.plusMonths(1).atDay(1);
        }
    }

    private static Range range(ReportInput in) {
        YearMonth from = in.fromMonth();
        YearMonth to = in.toMonth();
        if (from == null || to == null) {
            throw ActionException.invalid("fromMonth and toMonth are required");
        }
        if (from.getYear() < 1900 || to.getYear() > 9998) {
            throw ActionException.invalid("Month out of range");
        }
        if (from.isAfter(to)) {
            throw ActionException.invalid("fromMonth must not be after toMonth");
        }
        if (from.until(to, ChronoUnit.MONTHS) >= MAX_MONTH_RANGE) {
            throw ActionException.invalid("At most " + MAX_MONTH_RANGE + " months per request");
        }
        return new Range(from, to);
    }

    private List<CategoryActivity> categorizedActivity(UUID budgetId, Range range) {
        return jdbc.sql(CATEGORIZED_ACTIVITY)
                .param("b", budgetId)
                .param("start", range.start())
                .param("end", range.end())
                .query((rs, n) -> new CategoryActivity(rs.getObject("category_id", UUID.class),
                        YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("amount")))
                .list();
    }

    private Map<UUID, CategoryName> categoryNames(UUID budgetId) {
        Map<UUID, CategoryName> out = new HashMap<>();
        jdbc.sql("""
                        select c.id, c.name, g.name as group_name, c.system_kind
                        from category c join category_group g on g.id = c.group_id
                        where c.budget_id = :b""")
                .param("b", budgetId)
                .query((RowCallbackHandler) rs -> out.put(rs.getObject("id", UUID.class),
                        new CategoryName(rs.getString("name"), rs.getString("group_name"),
                                rs.getString("system_kind"))));
        return out;
    }
}
