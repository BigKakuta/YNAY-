package io.ynay.subscription;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Subscriptions and fixed contracts. A subscription is linked to payees; its charges are the spending
 * lines of those payees on budget accounts (as in report_payments), from which the last charge,
 * monthly cost, 12-month total, next renewal and status are derived.
 */
@Component
public class SubscriptionActions {

    public enum Kind { SUBSCRIPTION, FIXED }

    public enum Cadence { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

    public enum Status { ACTIVE, OVERDUE, ENDED }

    public record PayeeRef(UUID id, String name) {
    }

    /**
     * {@code Subscription}. Amounts are positive milliunits. monthlyCost: the last charge per month
     * (yearly / 12, quarterly / 3, weekly * 52 / 12), or for variable plans the average of the last three
     * full months. last12Months: net charges in the last 12 full months. nextCharge: last charge + one
     * period (null when ended or never charged). OVERDUE: no charge for a while after nextCharge.
     */
    public record SubscriptionDto(UUID id, String name, Kind kind, Cadence cadence, String groupName, String note,
                                  boolean variable, LocalDate cancelledOn, List<PayeeRef> payees, Status status,
                                  int chargeCount, LocalDate firstCharge, LocalDate lastCharge, long lastAmount,
                                  Long previousAmount, long monthlyCost, long last12Months, LocalDate nextCharge) {
    }

    private static final String DERIVED = """
             Each subscription lists its payees and derived figures (positive milliunits): chargeCount, \
            firstCharge, lastCharge, lastAmount (all charges on the last charge date), previousAmount (the \
            charge before that, to spot price changes), monthlyCost (last charge per month by cadence; for \
            variable plans the average of the last three full months), last12Months (net charges in the \
            last 12 full months), nextCharge (last charge plus one period) and status: ENDED once \
            cancelledOn is set, OVERDUE when no charge came for a while after nextCharge, else ACTIVE.""";

    private final BudgetAccess access;
    private final JdbcClient jdbc;

    public SubscriptionActions(BudgetAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ list

    public record ListSubscriptionsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "Day to evaluate status and totals on (default today)", required = false)
            LocalDate asOf) {
    }

    @McpAction(name = "list_subscriptions", readOnly = true, description = """
            List the budget's subscriptions (kind SUBSCRIPTION: plans you could cancel) and fixed contracts \
            (kind FIXED: rent, utilities, phone, insurance), including ended ones, most expensive first. \
            Charges are the spending of the linked payees on budget accounts.""" + DERIVED)
    public List<SubscriptionDto> listSubscriptions(ListSubscriptionsInput in) {
        access.requireMember(in.budgetId());
        LocalDate asOf = in.asOf() == null ? LocalDate.now() : in.asOf();
        List<SubscriptionDto> out = new ArrayList<>(load(in.budgetId(), null, asOf));
        out.sort(Comparator.comparing((SubscriptionDto s) -> s.status() == Status.ENDED)
                .thenComparing(Comparator.comparingLong(SubscriptionDto::monthlyCost).reversed())
                .thenComparing(SubscriptionDto::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ------------------------------------------------------------------ create / update / delete

    public record CreateSubscriptionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Name, e.g. \"Spotify\"") String name,
            @ActionParam(value = "SUBSCRIPTION (default) or FIXED (rent, utilities, insurance)", required = false)
            Kind kind,
            @ActionParam(value = "How often it is billed (default MONTHLY)", required = false) Cadence cadence,
            @ActionParam(value = "Free-form group, e.g. \"Media\" or \"AI\"", required = false) String groupName,
            @ActionParam(value = "Note shown next to it, e.g. \"Promo price until May\"", required = false)
            String note,
            @ActionParam(value = "Plan plus one-off purchases (app stores): monthly cost = 3-month average",
                    required = false) Boolean variable,
            @ActionParam(value = "Payees whose charges belong to it; a payee moves over from another "
                    + "subscription", required = false) List<UUID> payeeIds,
            @ActionParam(value = "Date it was cancelled (marks it ENDED)", required = false) LocalDate cancelledOn) {
    }

    @McpAction(name = "create_subscription", description = """
            Track a subscription or fixed contract and link the payees it is charged under (find them with \
            list_payees; a payee belongs to at most one subscription). Returns the subscription with its \
            derived figures (see list_subscriptions).""")
    public SubscriptionDto createSubscription(CreateSubscriptionInput in) {
        access.requireMember(in.budgetId());
        String name = Inputs.requireName(in.name(), "name");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into subscription (id, budget_id, name, kind, cadence, group_name, note, variable,
                                                  cancelled_on)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, in.budgetId(), name, (in.kind() == null ? Kind.SUBSCRIPTION : in.kind()).name(),
                        (in.cadence() == null ? Cadence.MONTHLY : in.cadence()).name(),
                        optionalName(in.groupName(), "groupName"), note(in.note()),
                        Boolean.TRUE.equals(in.variable()), in.cancelledOn())
                .update();
        if (in.payeeIds() != null) {
            linkPayees(in.budgetId(), id, in.payeeIds());
        }
        return require(in.budgetId(), id);
    }

    public record UpdateSubscriptionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Subscription id") UUID subscriptionId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "New kind", required = false) Kind kind,
            @ActionParam(value = "New cadence", required = false) Cadence cadence,
            @ActionParam(value = "New group (\"\" clears it)", required = false) String groupName,
            @ActionParam(value = "New note (\"\" clears it)", required = false) String note,
            @ActionParam(value = "Plan plus one-off purchases", required = false) Boolean variable,
            @ActionParam(value = "Replace the linked payees", required = false) List<UUID> payeeIds,
            @ActionParam(value = "Date it was cancelled (marks it ENDED)", required = false) LocalDate cancelledOn,
            @ActionParam(value = "true = running again (clears cancelledOn)", required = false) Boolean reactivate) {
    }

    @McpAction(name = "update_subscription", description = """
            Change a subscription; omitted fields stay unchanged. Set cancelledOn when the user cancels \
            it, reactivate=true to undo that. payeeIds replaces the linked payees.""")
    public SubscriptionDto updateSubscription(UpdateSubscriptionInput in) {
        access.requireMember(in.budgetId());
        requireExists(in.budgetId(), in.subscriptionId());
        UUID id = in.subscriptionId();
        if (in.name() != null) {
            set(id, "name", Inputs.requireName(in.name(), "name"));
        }
        if (in.kind() != null) {
            set(id, "kind", in.kind().name());
        }
        if (in.cadence() != null) {
            set(id, "cadence", in.cadence().name());
        }
        if (in.groupName() != null) {
            set(id, "group_name", optionalName(in.groupName(), "groupName"));
        }
        if (in.note() != null) {
            set(id, "note", note(in.note()));
        }
        if (in.variable() != null) {
            jdbc.sql("update subscription set variable = ? where id = ?").params(in.variable(), id).update();
        }
        if (Boolean.TRUE.equals(in.reactivate())) {
            jdbc.sql("update subscription set cancelled_on = null where id = ?").param(id).update();
        } else if (in.cancelledOn() != null) {
            jdbc.sql("update subscription set cancelled_on = ? where id = ?").params(in.cancelledOn(), id).update();
        }
        if (in.payeeIds() != null) {
            linkPayees(in.budgetId(), id, in.payeeIds());
        }
        return require(in.budgetId(), id);
    }

    public record DeleteSubscriptionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Subscription id") UUID subscriptionId) {
    }

    @McpAction(name = "delete_subscription", destructive = true, description = """
            Stop tracking a subscription. Its payees and transactions are kept.""")
    public Map<String, Object> deleteSubscription(DeleteSubscriptionInput in) {
        access.requireMember(in.budgetId());
        requireExists(in.budgetId(), in.subscriptionId());
        jdbc.sql("delete from subscription where id = ?").param(in.subscriptionId()).update();
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ derived figures

    private record Row(UUID id, String name, Kind kind, Cadence cadence, String groupName, String note,
                       boolean variable, LocalDate cancelledOn) {
    }

    private SubscriptionDto require(UUID budgetId, UUID id) {
        return load(budgetId, id, LocalDate.now()).getFirst();
    }

    private void requireExists(UUID budgetId, UUID id) {
        if (id == null) {
            throw ActionException.invalid("subscriptionId is required");
        }
        if (jdbc.sql("select count(*) from subscription where budget_id = ? and id = ?")
                .params(budgetId, id)
                .query(Long.class)
                .single() == 0) {
            throw ActionException.notFound("Subscription " + id);
        }
    }

    private List<SubscriptionDto> load(UUID budgetId, UUID onlyId, LocalDate asOf) {
        List<Row> rows = jdbc.sql("""
                        select id, name, kind, cadence, group_name, note, variable, cancelled_on
                        from subscription where budget_id = ?""" + (onlyId == null ? "" : " and id = ?"))
                .params(onlyId == null ? List.of(budgetId) : List.of(budgetId, onlyId))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                        Kind.valueOf(rs.getString("kind")), Cadence.valueOf(rs.getString("cadence")),
                        rs.getString("group_name"), rs.getString("note"), rs.getBoolean("variable"),
                        rs.getObject("cancelled_on", LocalDate.class)))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PayeeRef>> payees = new HashMap<>();
        jdbc.sql("select id, name, subscription_id from payee where budget_id = ? and subscription_id is not null"
                        + " order by lower(name)")
                .param(budgetId)
                .query((rs, n) -> payees.computeIfAbsent(rs.getObject("subscription_id", UUID.class),
                        k -> new ArrayList<>()).add(new PayeeRef(rs.getObject("id", UUID.class), rs.getString("name"))))
                .list();
        // charges per subscription and date (amount positive = charged)
        Map<UUID, List<long[]>> charges = new HashMap<>();
        jdbc.sql("""
                        with line as (
                            select t.date, t.amount, t.payee_id, t.account_id, t.transfer_account_id
                            from txn t where t.budget_id = :b and not t.is_split
                            union all
                            select t.date, s.amount, coalesce(s.payee_id, t.payee_id), t.account_id, null
                            from txn_split s join txn t on t.id = s.transaction_id
                            where t.budget_id = :b and t.is_split
                        )
                        select p.subscription_id, l.date, -sum(l.amount) as amount
                        from line l
                            join account a on a.id = l.account_id and a.on_budget
                            join payee p on p.id = l.payee_id and p.subscription_id is not null
                        where l.transfer_account_id is null
                        group by p.subscription_id, l.date
                        order by p.subscription_id, l.date""")
                .param("b", budgetId)
                .query((rs, n) -> charges.computeIfAbsent(rs.getObject("subscription_id", UUID.class),
                        k -> new ArrayList<>()).add(new long[] {rs.getObject("date", LocalDate.class).toEpochDay(),
                        rs.getLong("amount")}))
                .list();
        List<SubscriptionDto> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(derive(r, payees.getOrDefault(r.id(), List.of()), charges.getOrDefault(r.id(), List.of()),
                    asOf));
        }
        return out;
    }

    private static SubscriptionDto derive(Row r, List<PayeeRef> payees, List<long[]> byDate, LocalDate asOf) {
        LocalDate thisMonth = YearMonth.from(asOf).atDay(1);
        LocalDate yearAgo = thisMonth.minusMonths(12);
        LocalDate quarterAgo = thisMonth.minusMonths(3);
        int count = 0;
        long last12 = 0, last3 = 0, lastAmount = 0;
        Long previous = null;
        LocalDate first = null, last = null;
        for (long[] c : byDate) {
            LocalDate date = LocalDate.ofEpochDay(c[0]);
            if (date.isAfter(asOf)) {
                continue;
            }
            if (!date.isBefore(yearAgo) && date.isBefore(thisMonth)) {
                last12 += c[1];
            }
            if (!date.isBefore(quarterAgo) && date.isBefore(thisMonth)) {
                last3 += c[1];
            }
            if (c[1] <= 0) {
                continue; // a refund is not a charge
            }
            count++;
            if (first == null) {
                first = date;
            }
            if (last != null) {
                previous = lastAmount;
            }
            last = date;
            lastAmount = c[1];
        }
        long monthly = r.variable() ? Math.round(last3 / 3.0) : switch (r.cadence()) {
            case WEEKLY -> Math.round(lastAmount * 52 / 12.0);
            case MONTHLY -> lastAmount;
            case QUARTERLY -> Math.round(lastAmount / 3.0);
            case YEARLY -> Math.round(lastAmount / 12.0);
        };
        Status status = Status.ACTIVE;
        LocalDate next = null;
        if (r.cancelledOn() != null && !r.cancelledOn().isAfter(asOf)) {
            status = Status.ENDED;
        } else if (last != null) {
            next = switch (r.cadence()) {
                case WEEKLY -> last.plusWeeks(1);
                case MONTHLY -> last.plusMonths(1);
                case QUARTERLY -> last.plusMonths(3);
                case YEARLY -> last.plusYears(1);
            };
            int graceDays = switch (r.cadence()) {
                case WEEKLY -> 4;
                case MONTHLY -> 10;
                case QUARTERLY -> 20;
                case YEARLY -> 31;
            };
            if (asOf.isAfter(next.plusDays(graceDays))) {
                status = Status.OVERDUE;
            }
        }
        return new SubscriptionDto(r.id(), r.name(), r.kind(), r.cadence(), r.groupName(), r.note(), r.variable(),
                r.cancelledOn(), payees, status, count, first, last, lastAmount, previous, monthly, last12, next);
    }

    // ------------------------------------------------------------------ helpers

    private void linkPayees(UUID budgetId, UUID subscriptionId, List<UUID> payeeIds) {
        Set<UUID> ids = new LinkedHashSet<>(payeeIds);
        ids.remove(null);
        if (!ids.isEmpty()) {
            long found = jdbc.sql("""
                            select count(*) from payee
                            where budget_id = :b and id in (:ids) and transfer_account_id is null""")
                    .param("b", budgetId)
                    .param("ids", ids)
                    .query(Long.class)
                    .single();
            if (found != ids.size()) {
                throw ActionException.notFound("A payee in payeeIds (transfer payees cannot be linked)");
            }
        }
        jdbc.sql("update payee set subscription_id = null where subscription_id = ?").param(subscriptionId).update();
        if (!ids.isEmpty()) {
            jdbc.sql("update payee set subscription_id = :s where budget_id = :b and id in (:ids)")
                    .param("s", subscriptionId)
                    .param("b", budgetId)
                    .param("ids", ids)
                    .update();
        }
    }

    private void set(UUID id, String column, String value) {
        jdbc.sql("update subscription set " + column + " = ? where id = ?").params(value, id).update();
    }

    private static String optionalName(String value, String field) {
        String v = Inputs.blankToNull(value);
        return v == null ? null : Inputs.requireName(v, field);
    }

    private static String note(String value) {
        String v = Inputs.blankToNull(value);
        if (v != null && v.length() > 1000) {
            throw ActionException.invalid("note is too long (max 1000 characters)");
        }
        return v;
    }
}
