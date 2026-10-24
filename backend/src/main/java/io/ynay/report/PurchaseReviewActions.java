package io.ynay.report;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The user's verdict on past purchases, shown by the purchase review report. */
@Component
public class PurchaseReviewActions {

    private final BudgetAccess access;
    private final JdbcClient jdbc;

    public PurchaseReviewActions(BudgetAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public record ReviewPurchaseInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transaction id") UUID transactionId,
            @ActionParam(value = "Split line id, to review one line of a split transaction", required = false)
            UUID splitId,
            @ActionParam(value = "1 = shouldn't have bought it .. 5 = glad I did", required = false) Integer rating,
            @ActionParam(value = "Why this was a sensible purchase that needs no rating (e.g. \"Household basics\")",
                    required = false) String sensibleReason,
            @ActionParam(value = "true = remove the review", required = false) Boolean clear) {
    }

    public record PurchaseReview(UUID transactionId, UUID splitId, Integer rating, String sensibleReason) {
    }

    @McpAction(name = "review_purchase", idempotent = true, description = """
            Rate a purchase in hindsight, or file it as sensible. Give rating (1 = shouldn't have bought it, \
            5 = glad I did) and/or sensibleReason (filed as a sensible buy: consumables, household basics, \
            replacements); this replaces any earlier review of the same transaction or split line. \
            clear=true removes the review. Reviews show up in report_payments. Returns the review, or \
            {cleared: true}.""")
    public Object reviewPurchase(ReviewPurchaseInput in) {
        access.requireMember(in.budgetId());
        if (in.transactionId() == null) {
            throw ActionException.invalid("transactionId is required");
        }
        boolean exists = jdbc.sql("select count(*) from txn where budget_id = ? and id = ?")
                .params(in.budgetId(), in.transactionId())
                .query(Long.class)
                .single() > 0;
        if (!exists) {
            throw ActionException.notFound("Transaction " + in.transactionId());
        }
        if (in.splitId() != null && jdbc.sql("select count(*) from txn_split where id = ? and transaction_id = ?")
                .params(in.splitId(), in.transactionId())
                .query(Long.class)
                .single() == 0) {
            throw ActionException.notFound("Split line " + in.splitId() + " of transaction " + in.transactionId());
        }
        jdbc.sql("delete from purchase_review where transaction_id = ? and split_id is not distinct from ?")
                .params(in.transactionId(), in.splitId())
                .update();
        if (Boolean.TRUE.equals(in.clear())) {
            return Map.of("cleared", true);
        }
        Integer rating = in.rating();
        if (rating != null && (rating < 1 || rating > 5)) {
            throw ActionException.invalid("rating must be between 1 and 5");
        }
        String reason = Inputs.blankToNull(in.sensibleReason());
        if (reason != null && reason.length() > Inputs.MAX_NAME_LENGTH) {
            throw ActionException.invalid("sensibleReason is too long (max " + Inputs.MAX_NAME_LENGTH + " characters)");
        }
        if (rating == null && reason == null) {
            throw ActionException.invalid("Give a rating or a sensibleReason (or clear=true)");
        }
        jdbc.sql("""
                        insert into purchase_review (budget_id, transaction_id, split_id, rating, sensible_reason)
                        values (?, ?, ?, ?, ?)""")
                .params(in.budgetId(), in.transactionId(), in.splitId(), rating, reason)
                .update();
        return new PurchaseReview(in.transactionId(), in.splitId(), rating, reason);
    }
}
