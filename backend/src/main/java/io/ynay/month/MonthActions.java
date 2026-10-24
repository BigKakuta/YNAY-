package io.ynay.month;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.month.MonthCalculator.CategoryInfo;
import io.ynay.month.MonthCalculator.Computation;
import io.ynay.month.MonthCalculator.MonthState;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Budgeting actions: the month budget screen, assigning money and category targets. */
@Component
public class MonthActions {

    static final int MAX_MONTH_RANGE = 1200;

    public record GetMonthBudgetInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Month, YYYY-MM") YearMonth month) {
    }

    public record ListMonthSummariesInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("First month, YYYY-MM (inclusive)") YearMonth fromMonth,
            @ActionParam("Last month, YYYY-MM (inclusive)") YearMonth toMonth) {
    }

    public record AssignToCategoryInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Month, YYYY-MM") YearMonth month,
            @ActionParam("Category id (not Ready to Assign)") UUID categoryId,
            @ActionParam("New assigned amount for the month in milliunits (absolute, replaces the current value)")
            Long assigned) {
    }

    public record MoveMoneyInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Month, YYYY-MM") YearMonth month,
            @ActionParam("Amount to move in milliunits, > 0") Long amount,
            @ActionParam(value = "Source category id; omit (or pass the Ready to Assign category) to take from Ready to Assign",
                    required = false) UUID fromCategoryId,
            @ActionParam(value = "Destination category id; omit (or pass the Ready to Assign category) to return money to Ready to Assign",
                    required = false) UUID toCategoryId) {
    }

    public record AssignUnderfundedInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Month, YYYY-MM") YearMonth month) {
    }

    public record SetCategoryTargetInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category id") UUID categoryId,
            @ActionParam("Target type: MONTHLY_SAVINGS (assign X every month), MONTHLY_SPENDING (have X available "
                    + "each month), TARGET_BALANCE (save up to X), TARGET_BALANCE_BY_DATE (have X by targetDate)")
            TargetType type,
            @ActionParam("Target amount X in milliunits, > 0") Long amount,
            @ActionParam(value = "Target date YYYY-MM-DD; required for TARGET_BALANCE_BY_DATE, ignored otherwise",
                    required = false) LocalDate targetDate) {
    }

    public record ClearCategoryTargetInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Category id") UUID categoryId) {
    }

    private final BudgetAccess access;
    private final MonthCalculator calculator;
    private final JdbcClient jdbc;

    public MonthActions(BudgetAccess access, MonthCalculator calculator, JdbcClient jdbc) {
        this.access = access;
        this.calculator = calculator;
        this.jdbc = jdbc;
    }

    @McpAction(name = "get_month_budget", readOnly = true, description = """
            Get the budget for one month: Ready to Assign, income, totals, overspending from last month, \
            uncategorized activity, and every category group (except the internal one) with each \
            category's carryover, assigned, activity, available, target and targetNeeded. \
            All amounts are integer milliunits (1000 = 1.00); negative activity = spending.""")
    public MonthBudget getMonthBudget(GetMonthBudgetInput in) {
        access.requireMember(in.budgetId());
        return calculator.monthBudget(in.budgetId(), requireMonth(in.month(), "month"));
    }

    @McpAction(name = "list_month_summaries", readOnly = true, description = """
            List per-month totals (income, assigned, activity, readyToAssign; milliunits) for every \
            month from fromMonth to toMonth inclusive.""")
    public List<MonthSummary> listMonthSummaries(ListMonthSummariesInput in) {
        access.requireMember(in.budgetId());
        YearMonth from = requireMonth(in.fromMonth(), "fromMonth");
        YearMonth to = requireMonth(in.toMonth(), "toMonth");
        requireRange(from, to);
        Computation comp = calculator.compute(in.budgetId(), from, to);
        List<MonthSummary> out = new ArrayList<>();
        for (MonthState s : comp.months().values()) {
            out.add(new MonthSummary(s.month(), s.income(), s.assigned(), s.activity(), s.readyToAssign()));
        }
        return out;
    }

    @McpAction(name = "assign_to_category", idempotent = true, description = """
            Set the amount assigned (budgeted) to a category in a month. `assigned` is the new absolute \
            value in milliunits, not a delta; the difference comes from / goes back to Ready to Assign. \
            Cannot assign to the Ready to Assign category. Returns the category's numbers for that month.""")
    public CategoryMonth assignToCategory(AssignToCategoryInput in) {
        access.requireMember(in.budgetId());
        YearMonth month = requireMonth(in.month(), "month");
        if (in.assigned() == null) {
            throw ActionException.invalid("assigned is required");
        }
        CategoryInfo category = calculator.requireCategory(in.budgetId(), in.categoryId());
        if (category.isReadyToAssign()) {
            throw ActionException.invalid("Cannot assign money to Ready to Assign");
        }
        setAssigned(in.budgetId(), category.id(), month, in.assigned());
        Computation comp = calculator.compute(in.budgetId(), month, month);
        return calculator.categoryMonth(comp.category(category.id()), comp.month(month));
    }

    @McpAction(name = "move_money", description = """
            Move `amount` milliunits of assigned money between categories in a month by adjusting their \
            assigned amounts: the source's assigned decreases and the destination's increases. Omit \
            fromCategoryId to assign from Ready to Assign; omit toCategoryId to return money to Ready to \
            Assign. Categories may go negative. Returns the updated month budget.""")
    public MonthBudget moveMoney(MoveMoneyInput in) {
        access.requireMember(in.budgetId());
        YearMonth month = requireMonth(in.month(), "month");
        if (in.amount() == null || in.amount() <= 0) {
            throw ActionException.invalid("amount must be greater than 0");
        }
        UUID from = budgetCategoryOrNull(in.budgetId(), in.fromCategoryId());
        UUID to = budgetCategoryOrNull(in.budgetId(), in.toCategoryId());
        if (from == null && to == null) {
            throw ActionException.invalid("Give fromCategoryId and/or toCategoryId");
        }
        if (from != null && from.equals(to)) {
            throw ActionException.invalid("fromCategoryId and toCategoryId must differ");
        }
        if (from != null) {
            addAssigned(in.budgetId(), from, month, -in.amount());
        }
        if (to != null) {
            addAssigned(in.budgetId(), to, month, in.amount());
        }
        return calculator.monthBudget(in.budgetId(), month);
    }

    @McpAction(name = "assign_underfunded", description = """
            Fund category targets for a month: walks the visible categories in budget order and adds \
            each one's targetNeeded to its assigned amount, as long as Ready to Assign is positive. When \
            Ready to Assign runs short, the next category gets only what is left (partially funded) and \
            the rest are skipped. Hidden categories and categories in hidden groups are skipped. \
            Returns the updated month budget.""")
    public MonthBudget assignUnderfunded(AssignUnderfundedInput in) {
        access.requireMember(in.budgetId());
        YearMonth month = requireMonth(in.month(), "month");
        Computation comp = calculator.compute(in.budgetId(), month, month);
        MonthState state = comp.month(month);
        Map<UUID, Boolean> groupHidden = new HashMap<>();
        comp.groups().forEach(g -> groupHidden.put(g.id(), g.hidden()));
        long rta = state.readyToAssign();
        for (CategoryInfo c : MonthCalculator.displayOrder(comp)) {
            if (rta <= 0) {
                break;
            }
            if (c.isReadyToAssign() || c.target() == null || c.hidden()
                    || groupHidden.getOrDefault(c.groupId(), false)) {
                continue;
            }
            CategoryMonth cm = calculator.categoryMonth(c, state);
            long amount = Math.min(cm.targetNeeded(), rta);
            if (amount <= 0) {
                continue;
            }
            addAssigned(in.budgetId(), c.id(), month, amount);
            rta -= amount;
        }
        return calculator.monthBudget(in.budgetId(), month);
    }

    @McpAction(name = "set_category_target", idempotent = true, description = """
            Set (or replace) a category's target. Types: MONTHLY_SAVINGS = assign `amount` every month; \
            MONTHLY_SPENDING = have `amount` available each month; TARGET_BALANCE = save up to a balance \
            of `amount`; TARGET_BALANCE_BY_DATE = reach `amount` by the month of `targetDate` (spread \
            evenly over the remaining months). Amount in milliunits. Returns the category.""")
    public TargetCategoryDto setCategoryTarget(SetCategoryTargetInput in) {
        access.requireMember(in.budgetId());
        CategoryInfo category = calculator.requireCategory(in.budgetId(), in.categoryId());
        if (category.isReadyToAssign()) {
            throw ActionException.invalid("Ready to Assign cannot have a target");
        }
        if (in.type() == null) {
            throw ActionException.invalid("type is required");
        }
        if (in.amount() == null || in.amount() <= 0) {
            throw ActionException.invalid("amount must be greater than 0");
        }
        LocalDate date = null;
        if (in.type() == TargetType.TARGET_BALANCE_BY_DATE) {
            if (in.targetDate() == null) {
                throw ActionException.invalid("targetDate is required for TARGET_BALANCE_BY_DATE");
            }
            date = in.targetDate();
        }
        jdbc.sql("""
                        update category set target_type = :type, target_amount = :amount, target_date = :date
                        where id = :id and budget_id = :b""")
                .param("type", in.type().name())
                .param("amount", in.amount())
                .param("date", date)
                .param("id", category.id())
                .param("b", in.budgetId())
                .update();
        return calculator.requireCategory(in.budgetId(), category.id()).toDto();
    }

    @McpAction(name = "clear_category_target", idempotent = true, description = """
            Remove a category's target. Returns the category.""")
    public TargetCategoryDto clearCategoryTarget(ClearCategoryTargetInput in) {
        access.requireMember(in.budgetId());
        CategoryInfo category = calculator.requireCategory(in.budgetId(), in.categoryId());
        jdbc.sql("""
                        update category set target_type = null, target_amount = null, target_date = null
                        where id = :id and budget_id = :b""")
                .param("id", category.id())
                .param("b", in.budgetId())
                .update();
        return calculator.requireCategory(in.budgetId(), category.id()).toDto();
    }

    // ---------------------------------------------------------------- helpers

    static YearMonth requireMonth(YearMonth month, String name) {
        if (month == null) {
            throw ActionException.invalid(name + " is required");
        }
        if (month.getYear() < 1900 || month.getYear() > 9998) {
            throw ActionException.invalid(name + " is out of range");
        }
        return month;
    }

    static void requireRange(YearMonth from, YearMonth to) {
        if (from.isAfter(to)) {
            throw ActionException.invalid("fromMonth must not be after toMonth");
        }
        if (from.until(to, ChronoUnit.MONTHS) >= MAX_MONTH_RANGE) {
            throw ActionException.invalid("At most " + MAX_MONTH_RANGE + " months per request");
        }
    }

    /** Resolves a category of the budget; null (and the Ready to Assign category) mean Ready to Assign. */
    private UUID budgetCategoryOrNull(UUID budgetId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        CategoryInfo c = calculator.requireCategory(budgetId, categoryId);
        return c.isReadyToAssign() ? null : c.id();
    }

    private void setAssigned(UUID budgetId, UUID categoryId, YearMonth month, long assigned) {
        jdbc.sql("""
                        insert into month_assignment (budget_id, category_id, month, assigned)
                        values (:b, :c, :m, :a)
                        on conflict (category_id, month) do update set assigned = excluded.assigned""")
                .param("b", budgetId)
                .param("c", categoryId)
                .param("m", month.atDay(1))
                .param("a", assigned)
                .update();
    }

    private void addAssigned(UUID budgetId, UUID categoryId, YearMonth month, long delta) {
        jdbc.sql("""
                        insert into month_assignment (budget_id, category_id, month, assigned)
                        values (:b, :c, :m, :a)
                        on conflict (category_id, month) do update
                            set assigned = month_assignment.assigned + excluded.assigned""")
                .param("b", budgetId)
                .param("c", categoryId)
                .param("m", month.atDay(1))
                .param("a", delta)
                .update();
    }
}
