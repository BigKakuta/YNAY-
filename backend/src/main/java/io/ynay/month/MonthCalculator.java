package io.ynay.month;

import io.ynay.action.ActionException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The month math of docs/ARCHITECTURE.md ("Month math"). Loads aggregated rows (one row per
 * account x category x month, plus one row per category x month of assignments) with a fixed
 * number of queries and rolls carryover forward in memory from the earliest month with data.
 */
@Component
public class MonthCalculator {

    static final String READY_TO_ASSIGN = "READY_TO_ASSIGN";
    static final String INTERNAL = "INTERNAL";

    /** Payee name of opening-balance transactions; uncategorized ones have no budget effect. */
    static final String STARTING_BALANCE_PAYEE = "Starting Balance";

    record GroupInfo(UUID id, String name, boolean hidden, int sortOrder, String systemKind) {
    }

    record CategoryInfo(UUID id, UUID groupId, String name, boolean hidden, String note, int sortOrder,
                        String systemKind, UUID linkedAccountId, TargetDto target) {

        boolean isReadyToAssign() {
            return READY_TO_ASSIGN.equals(systemKind);
        }

        TargetCategoryDto toDto() {
            return new TargetCategoryDto(id, groupId, name, hidden, note, sortOrder, systemKind, linkedAccountId, target);
        }
    }

    record Values(long carryover, long assigned, long activity, long available) {
        static final Values ZERO = new Values(0, 0, 0, 0);
    }

    record MonthState(YearMonth month, Map<UUID, Values> categories, long income, long assigned, long activity,
                      long available, long uncategorized, long overspent, long overspentLastMonth,
                      long readyToAssign) {

        Values of(UUID categoryId) {
            return categories.getOrDefault(categoryId, Values.ZERO);
        }
    }

    /** Result of a computation over a range of months. */
    record Computation(String currencyCode, List<GroupInfo> groups, List<CategoryInfo> categories,
                       Map<YearMonth, MonthState> months) {

        MonthState month(YearMonth m) {
            return months.get(m);
        }

        CategoryInfo category(UUID id) {
            return categories.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        }
    }

    private record ActivityRow(UUID accountId, UUID categoryId, String kind, YearMonth month, long amount) {
    }

    private record AssignmentRow(UUID categoryId, YearMonth month, long assigned) {
    }

    private final JdbcClient jdbc;

    public MonthCalculator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- loading

    List<GroupInfo> loadGroups(UUID budgetId) {
        return jdbc.sql("""
                        select id, name, hidden, sort_order, system_kind from category_group
                        where budget_id = :b order by sort_order, lower(name), id""")
                .param("b", budgetId)
                .query((rs, n) -> new GroupInfo(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("hidden"), rs.getInt("sort_order"), rs.getString("system_kind")))
                .list();
    }

    List<CategoryInfo> loadCategories(UUID budgetId) {
        return jdbc.sql("""
                        select id, group_id, name, hidden, note, sort_order, system_kind, linked_account_id,
                               target_type, target_amount, target_date
                        from category where budget_id = :b order by sort_order, lower(name), id""")
                .param("b", budgetId)
                .query((rs, n) -> {
                    String type = rs.getString("target_type");
                    TargetDto target = type == null ? null : new TargetDto(TargetType.valueOf(type),
                            rs.getLong("target_amount"), rs.getObject("target_date", LocalDate.class));
                    return new CategoryInfo(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                            rs.getString("name"), rs.getBoolean("hidden"), rs.getString("note"),
                            rs.getInt("sort_order"), rs.getString("system_kind"),
                            rs.getObject("linked_account_id", UUID.class), target);
                })
                .list();
    }

    CategoryInfo requireCategory(UUID budgetId, UUID categoryId) {
        if (categoryId == null) {
            throw ActionException.invalid("categoryId is required");
        }
        return loadCategories(budgetId).stream()
                .filter(c -> c.id().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> ActionException.notFound("Category " + categoryId));
    }

    /**
     * Budget-relevant amounts on on-budget accounts dated before {@code end}, aggregated by
     * account, category, kind and month. Kinds: C = categorized (txn or split line),
     * T = transfer to/from another on-budget account (no category), U = uncategorized,
     * S = uncategorized starting balance (no budget effect).
     */
    private List<ActivityRow> loadActivity(UUID budgetId, LocalDate end) {
        return jdbc.sql("""
                        select x.account_id, x.category_id, x.kind, x.month, sum(x.amount) as amount
                        from (
                            select t.account_id, t.category_id,
                                   case when t.category_id is not null then 'C'
                                        when ta.on_budget then 'T'
                                        when t.transfer_account_id is null and p.name = :sb then 'S'
                                        else 'U' end as kind,
                                   cast(date_trunc('month', t.date) as date) as month,
                                   t.amount
                            from txn t
                            join account a on a.id = t.account_id and a.on_budget
                            left join account ta on ta.id = t.transfer_account_id
                            left join payee p on p.id = t.payee_id
                            where t.budget_id = :b and not t.is_split and t.date < :end
                            union all
                            select t.account_id, s.category_id,
                                   case when s.category_id is not null then 'C' else 'U' end,
                                   cast(date_trunc('month', t.date) as date),
                                   s.amount
                            from txn_split s
                            join txn t on t.id = s.transaction_id
                            join account a on a.id = t.account_id and a.on_budget
                            where t.budget_id = :b and t.is_split and t.date < :end
                        ) x
                        group by x.account_id, x.category_id, x.kind, x.month""")
                .param("b", budgetId)
                .param("end", end)
                .param("sb", STARTING_BALANCE_PAYEE)
                .query((rs, n) -> new ActivityRow(rs.getObject("account_id", UUID.class),
                        rs.getObject("category_id", UUID.class), rs.getString("kind"),
                        YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("amount")))
                .list();
    }

    private List<AssignmentRow> loadAssignments(UUID budgetId) {
        return jdbc.sql("select category_id, month, assigned from month_assignment where budget_id = :b")
                .param("b", budgetId)
                .query((rs, n) -> new AssignmentRow(rs.getObject("category_id", UUID.class),
                        YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("assigned")))
                .list();
    }

    String currencyCode(UUID budgetId) {
        return jdbc.sql("select currency_code from budget where id = :b")
                .param("b", budgetId)
                .query(String.class)
                .optional()
                .map(String::trim)
                .orElseThrow(() -> ActionException.notFound("Budget " + budgetId));
    }

    // ---------------------------------------------------------------- computing

    /** Computes every month in {@code [from, to]} (inclusive). */
    Computation compute(UUID budgetId, YearMonth from, YearMonth to) {
        List<GroupInfo> groups = loadGroups(budgetId);
        List<CategoryInfo> categories = loadCategories(budgetId);
        List<ActivityRow> activityRows = loadActivity(budgetId, to.plusMonths(1).atDay(1));
        List<AssignmentRow> assignmentRows = loadAssignments(budgetId);

        Map<UUID, CategoryInfo> byId = new HashMap<>();
        Map<UUID, UUID> paymentCategoryByAccount = new HashMap<>();
        UUID rtaId = null;
        for (CategoryInfo c : categories) {
            byId.put(c.id(), c);
            if (c.isReadyToAssign()) {
                rtaId = c.id();
            }
            if (c.linkedAccountId() != null) {
                paymentCategoryByAccount.put(c.linkedAccountId(), c.id());
            }
        }

        // activity[month][category], uncategorized[month]
        Map<YearMonth, Map<UUID, Long>> activity = new HashMap<>();
        Map<YearMonth, Long> uncategorized = new HashMap<>();
        YearMonth start = from;
        for (ActivityRow r : activityRows) {
            if (r.month().isBefore(start)) {
                start = r.month();
            }
            UUID payment = paymentCategoryByAccount.get(r.accountId());
            switch (r.kind()) {
                case "C" -> {
                    if (!byId.containsKey(r.categoryId())) {
                        continue;
                    }
                    add(activity, r.month(), r.categoryId(), r.amount());
                    // Spending on a credit account moves the money into its payment category.
                    if (payment != null && !r.categoryId().equals(payment) && !r.categoryId().equals(rtaId)) {
                        add(activity, r.month(), payment, -r.amount());
                    }
                }
                case "T" -> {
                    // A payment (+X on the card) reduces the payment category by X; money moved off
                    // the card to another on-budget account (-X) adds X, keeping the books balanced.
                    if (payment != null) {
                        add(activity, r.month(), payment, -r.amount());
                    }
                }
                case "U" -> uncategorized.merge(r.month(), r.amount(), Long::sum);
                default -> {
                    // starting balance on credit/tracking accounts: no budget effect
                }
            }
        }

        Map<YearMonth, Map<UUID, Long>> assigned = new HashMap<>();
        long totalAssigned = 0;
        for (AssignmentRow r : assignmentRows) {
            CategoryInfo c = byId.get(r.categoryId());
            if (c == null || c.isReadyToAssign()) {
                continue;
            }
            totalAssigned += r.assigned();
            if (r.month().isAfter(to)) {
                continue;
            }
            if (r.month().isBefore(start)) {
                start = r.month();
            }
            add(assigned, r.month(), r.categoryId(), r.assigned());
        }

        Map<YearMonth, MonthState> result = new LinkedHashMap<>();
        Map<UUID, Long> prevAvailable = new HashMap<>();
        long cumulativeIncome = 0;
        long cumulativeOverspent = 0; // over months before the current one
        long prevOverspent = 0;
        for (YearMonth m = start; !m.isAfter(to); m = m.plusMonths(1)) {
            Map<UUID, Long> act = activity.getOrDefault(m, Map.of());
            Map<UUID, Long> asg = assigned.getOrDefault(m, Map.of());
            boolean keep = !m.isBefore(from);
            Map<UUID, Values> values = keep ? new HashMap<>() : null;
            long income = rtaId == null ? 0 : act.getOrDefault(rtaId, 0L);
            long sumAssigned = 0, sumActivity = 0, sumAvailable = 0, overspent = 0;
            for (CategoryInfo c : categories) {
                if (c.isReadyToAssign()) {
                    continue;
                }
                long carry = Math.max(prevAvailable.getOrDefault(c.id(), 0L), 0);
                long a = asg.getOrDefault(c.id(), 0L);
                long ac = act.getOrDefault(c.id(), 0L);
                long avail = carry + a + ac;
                prevAvailable.put(c.id(), avail);
                sumAssigned += a;
                sumActivity += ac;
                sumAvailable += avail;
                overspent += Math.max(-avail, 0);
                if (keep) {
                    values.put(c.id(), new Values(carry, a, ac, avail));
                }
            }
            long uncat = uncategorized.getOrDefault(m, 0L);
            overspent += Math.max(-uncat, 0);
            cumulativeIncome += income;
            if (keep) {
                long rta = cumulativeIncome - totalAssigned - cumulativeOverspent;
                result.put(m, new MonthState(m, values, income, sumAssigned, sumActivity, sumAvailable, uncat,
                        overspent, prevOverspent, rta));
            }
            cumulativeOverspent += overspent;
            prevOverspent = overspent;
        }
        return new Computation(currencyCode(budgetId), groups, categories, result);
    }

    private static void add(Map<YearMonth, Map<UUID, Long>> map, YearMonth m, UUID category, long amount) {
        map.computeIfAbsent(m, k -> new HashMap<>()).merge(category, amount, Long::sum);
    }

    // ---------------------------------------------------------------- DTOs

    CategoryMonth categoryMonth(CategoryInfo c, MonthState state) {
        Values v = state.of(c.id());
        return new CategoryMonth(c.id(), c.name(), c.hidden(), c.systemKind(), c.linkedAccountId(),
                v.carryover(), v.assigned(), v.activity(), v.available(), c.target(),
                Targets.needed(c.target(), state.month(), v.carryover(), v.assigned()));
    }

    MonthBudget monthBudget(Computation comp, YearMonth month) {
        MonthState state = comp.month(month);
        Map<UUID, List<CategoryInfo>> byGroup = new HashMap<>();
        for (CategoryInfo c : comp.categories()) {
            byGroup.computeIfAbsent(c.groupId(), k -> new ArrayList<>()).add(c);
        }
        List<GroupMonth> groups = new ArrayList<>();
        for (GroupInfo g : comp.groups()) {
            if (INTERNAL.equals(g.systemKind())) {
                continue;
            }
            List<CategoryMonth> cats = new ArrayList<>();
            long asg = 0, act = 0, avail = 0;
            for (CategoryInfo c : byGroup.getOrDefault(g.id(), List.of())) {
                if (c.isReadyToAssign()) {
                    continue;
                }
                CategoryMonth cm = categoryMonth(c, state);
                cats.add(cm);
                asg += cm.assigned();
                act += cm.activity();
                avail += cm.available();
            }
            groups.add(new GroupMonth(g.id(), g.name(), g.hidden(), g.systemKind(), asg, act, avail, cats));
        }
        return new MonthBudget(month, comp.currencyCode(), state.readyToAssign(), state.income(), state.assigned(),
                state.activity(), state.available(), state.overspentLastMonth(), state.uncategorized(), groups);
    }

    MonthBudget monthBudget(UUID budgetId, YearMonth month) {
        return monthBudget(compute(budgetId, month, month), month);
    }

    /** Categories in display order: by group order, then category order. */
    static List<CategoryInfo> displayOrder(Computation comp) {
        Map<UUID, Integer> groupIndex = new HashMap<>();
        for (int i = 0; i < comp.groups().size(); i++) {
            groupIndex.put(comp.groups().get(i).id(), i);
        }
        List<CategoryInfo> sorted = new ArrayList<>(comp.categories());
        sorted.sort(Comparator.comparingInt(c -> groupIndex.getOrDefault(c.groupId(), Integer.MAX_VALUE)));
        return sorted;
    }
}
