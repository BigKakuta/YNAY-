package io.ynay.payee;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Payee actions. Payees are created implicitly by transactions (payeeName). */
@Component
public class PayeeActions {

    private final BudgetAccess access;
    private final PayeeService payees;
    private final JdbcClient jdbc;

    public PayeeActions(BudgetAccess access, PayeeService payees, JdbcClient jdbc) {
        this.access = access;
        this.payees = payees;
        this.jdbc = jdbc;
    }

    public record ListPayeesInput(@ActionParam("Budget id") UUID budgetId) {}

    @McpAction(name = "list_payees", readOnly = true, description = """
            List all payees of the budget, sorted by name. Payees with a transferAccountId are transfer payees \
            ("Transfer : <account name>"): using one on a transaction makes it a transfer to that account. \
            Payees don't need to be created explicitly: create_transaction/import_transactions create them from \
            payeeName on the fly.""")
    public List<PayeeDto> listPayees(ListPayeesInput in) {
        access.requireMember(in.budgetId());
        return payees.list(in.budgetId());
    }

    public record UpdatePayeeInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Payee id") UUID payeeId,
            @ActionParam("New name") String name) {}

    @McpAction(name = "update_payee", description = """
            Rename a payee. Fails with CONFLICT if another payee already has that name (use merge_payees to \
            combine them). Transfer payees are named after their account and cannot be renamed.""")
    public PayeeDto updatePayee(UpdatePayeeInput in) {
        access.requireMember(in.budgetId());
        PayeeDto payee = payees.require(in.budgetId(), in.payeeId());
        if (payee.isTransfer()) {
            throw ActionException.invalid("Transfer payees are renamed by renaming their account");
        }
        String name = Inputs.requireName(in.name(), "name");
        if (name.regionMatches(true, 0, PayeeService.TRANSFER_PREFIX, 0, PayeeService.TRANSFER_PREFIX.length())) {
            throw ActionException.invalid("Payee names starting with '" + PayeeService.TRANSFER_PREFIX
                    + "' are reserved for transfers");
        }
        Optional<PayeeDto> clash = payees.findByName(in.budgetId(), name);
        if (clash.isPresent() && !clash.get().id().equals(payee.id())) {
            throw ActionException.conflict("A payee named '" + clash.get().name()
                    + "' already exists; use merge_payees to combine them");
        }
        jdbc.sql("update payee set name = ? where id = ?").params(name, payee.id()).update();
        return payees.require(in.budgetId(), payee.id());
    }

    public record MergePayeesInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Payees to merge away (they are deleted)") List<UUID> sourcePayeeIds,
            @ActionParam("Payee that receives all transactions of the source payees") UUID targetPayeeId) {}

    @McpAction(name = "merge_payees", destructive = true, description = """
            Merge payees: every transaction and split line of the source payees is moved to the target payee, \
            then the source payees are deleted. Useful to clean up bank-statement variants such as \
            "AMAZON MKTPLACE 123" into "Amazon". Transfer payees can be neither source nor target.""")
    public PayeeDto mergePayees(MergePayeesInput in) {
        access.requireMember(in.budgetId());
        PayeeDto target = payees.require(in.budgetId(), in.targetPayeeId());
        if (target.isTransfer()) {
            throw ActionException.invalid("Transfer payees cannot be merged");
        }
        if (in.sourcePayeeIds() == null || in.sourcePayeeIds().isEmpty()) {
            throw ActionException.invalid("sourcePayeeIds must not be empty");
        }
        Set<UUID> sources = new LinkedHashSet<>(in.sourcePayeeIds());
        sources.remove(target.id());
        for (UUID id : sources) {
            PayeeDto source = payees.require(in.budgetId(), id);
            if (source.isTransfer()) {
                throw ActionException.invalid("Transfer payee '" + source.name() + "' cannot be merged");
            }
        }
        for (UUID id : sources) {
            jdbc.sql("update txn set payee_id = ?, updated_at = now() where budget_id = ? and payee_id = ?")
                    .params(target.id(), in.budgetId(), id)
                    .update();
            jdbc.sql("""
                            update txn_split s set payee_id = ? from txn t
                            where s.transaction_id = t.id and t.budget_id = ? and s.payee_id = ?""")
                    .params(target.id(), in.budgetId(), id)
                    .update();
            jdbc.sql("delete from payee where budget_id = ? and id = ?").params(in.budgetId(), id).update();
        }
        return target;
    }
}
