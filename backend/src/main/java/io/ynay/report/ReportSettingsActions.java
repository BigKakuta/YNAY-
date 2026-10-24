package io.ynay.report;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Report choices stored per budget, shared by its members. */
@Component
public class ReportSettingsActions {

    /** Every field may be null (not set); the Reports page then falls back to its defaults. */
    public record ReportSettings(
            @ActionParam(value = "Categories that count as \"things\" (discretionary purchases: shopping, "
                    + "electronics, furniture), tracked month by month and in the purchase review",
                    required = false) List<UUID> thingsCategoryIds,
            @ActionParam(value = "Monthly goal for things in milliunits, drawn as a line on the chart",
                    required = false) Long thingsMonthlyTarget,
            @ActionParam(value = "Categories of the spending deep dive (e.g. groceries)", required = false)
            List<UUID> focusCategoryIds,
            @ActionParam(value = "Payee of the item-by-item view (e.g. an online shop)", required = false)
            UUID focusPayeeId,
            @ActionParam(value = "Purchases of at least this much (milliunits) are up for review",
                    required = false) Long reviewMinAmount,
            @ActionParam(value = "Gross salary per year in milliunits, for the money-flow chart",
                    required = false) Long grossSalaryPerYear,
            @ActionParam(value = "Payee the net salary comes from", required = false) UUID salaryPayeeId) {

        static final ReportSettings EMPTY = new ReportSettings(null, null, null, null, null, null, null);
    }

    public record GetReportSettingsInput(@ActionParam("Budget id") UUID budgetId) {
    }

    public record UpdateReportSettingsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("The complete new settings; fields left out are cleared") ReportSettings settings) {
    }

    private final BudgetAccess access;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReportSettingsActions(BudgetAccess access, JdbcClient jdbc, JsonMapper json) {
        this.access = access;
        this.jdbc = jdbc;
        this.json = json;
    }

    @McpAction(name = "get_report_settings", readOnly = true, description = """
            The budget's report settings: which categories count as "things", the spending deep-dive \
            categories, the item-by-item payee, the purchase review threshold and the salary assumptions \
            used by the money-flow chart. Unset fields are null. Amounts in milliunits.""")
    public ReportSettings getReportSettings(GetReportSettingsInput in) {
        access.requireMember(in.budgetId());
        return load(in.budgetId());
    }

    @McpAction(name = "update_report_settings", idempotent = true, description = """
            Replace the budget's report settings (see get_report_settings). This is a full replacement: \
            read the current settings first and send them back with your changes. Category and payee ids \
            must belong to the budget. Amounts in milliunits.""")
    public ReportSettings updateReportSettings(UpdateReportSettingsInput in) {
        access.requireMember(in.budgetId());
        ReportSettings s = in.settings() == null ? ReportSettings.EMPTY : in.settings();
        s = new ReportSettings(categories(in.budgetId(), s.thingsCategoryIds(), "thingsCategoryIds"),
                nonNegative(s.thingsMonthlyTarget(), "thingsMonthlyTarget"),
                categories(in.budgetId(), s.focusCategoryIds(), "focusCategoryIds"),
                payee(in.budgetId(), s.focusPayeeId()),
                nonNegative(s.reviewMinAmount(), "reviewMinAmount"),
                nonNegative(s.grossSalaryPerYear(), "grossSalaryPerYear"),
                payee(in.budgetId(), s.salaryPayeeId()));
        jdbc.sql("""
                        insert into report_settings (budget_id, settings) values (?, cast(? as jsonb))
                        on conflict (budget_id) do update set settings = excluded.settings, updated_at = now()""")
                .params(in.budgetId(), json.writeValueAsString(s))
                .update();
        return s;
    }

    /** The stored settings, or all-null settings. */
    ReportSettings load(UUID budgetId) {
        return jdbc.sql("select settings::text from report_settings where budget_id = ?")
                .param(budgetId)
                .query(String.class)
                .optional()
                .map(text -> json.readValue(text, ReportSettings.class))
                .orElse(ReportSettings.EMPTY);
    }

    private List<UUID> categories(UUID budgetId, List<UUID> ids, String field) {
        if (ids == null) {
            return null;
        }
        Set<UUID> unique = new LinkedHashSet<>(ids);
        unique.remove(null);
        if (unique.isEmpty()) {
            return List.of();
        }
        long found = jdbc.sql("select count(*) from category where budget_id = :b and id in (:ids)")
                .param("b", budgetId)
                .param("ids", unique)
                .query(Long.class)
                .single();
        if (found != unique.size()) {
            throw ActionException.notFound("A category in " + field);
        }
        return List.copyOf(unique);
    }

    private UUID payee(UUID budgetId, UUID id) {
        if (id != null && jdbc.sql("select count(*) from payee where budget_id = ? and id = ?")
                .params(budgetId, id)
                .query(Long.class)
                .single() == 0) {
            throw ActionException.notFound("Payee " + id);
        }
        return id;
    }

    private static Long nonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw ActionException.invalid(field + " must not be negative");
        }
        return value;
    }
}
