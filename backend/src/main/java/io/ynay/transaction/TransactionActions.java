package io.ynay.transaction;

import io.ynay.account.AccountDto;
import io.ynay.account.AccountService;
import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.time.LocalDate;
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

/** Transaction actions: CRUD, bank import, approval, clearing and reconciliation. */
@Component
public class TransactionActions {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    static final int MAX_IMPORT_LINES = 2000;

    private static final String AMOUNTS = " Amounts are integer milliunits (1.00 = 1000, so -12340 = 12.34 spent): "
            + "negative = outflow/spending, positive = inflow/income. ";

    private final BudgetAccess access;
    private final TransactionService txns;
    private final AccountService accounts;
    private final JdbcClient jdbc;

    public TransactionActions(BudgetAccess access, TransactionService txns, AccountService accounts, JdbcClient jdbc) {
        this.access = access;
        this.txns = txns;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ read

    public record ListTransactionsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "Only this account", required = false) UUID accountId,
            @ActionParam(value = "Only this category (also matches split lines)", required = false) UUID categoryId,
            @ActionParam(value = "Only this payee (also matches split lines)", required = false) UUID payeeId,
            @ActionParam(value = "Earliest date, inclusive (YYYY-MM-DD)", required = false) LocalDate sinceDate,
            @ActionParam(value = "Latest date, inclusive (YYYY-MM-DD)", required = false) LocalDate untilDate,
            @ActionParam(value = "Case-insensitive text matched against memo, payee, category and account names",
                    required = false) String search,
            @ActionParam(value = "Only transactions still awaiting approval (e.g. fresh imports)", required = false)
            Boolean unapprovedOnly,
            @ActionParam(value = "Page size (default 200, max 1000)", required = false) Integer limit,
            @ActionParam(value = "Number of transactions to skip (default 0)", required = false) Integer offset) {}

    @McpAction(name = "list_transactions", readOnly = true, description = """
            List transactions, newest first, with optional filters (all combined with AND). Returns \
            {transactions, total} where total is the number of matches ignoring limit/offset; page with \
            limit/offset. """ + AMOUNTS + """
            Split transactions have categoryId null and their lines in splits[]. Transfers have \
            transferAccountId and transferTransactionId (the other side). Use this before importing to check \
            what is already recorded for an account and date range.""")
    public TransactionService.Page listTransactions(ListTransactionsInput in) {
        access.requireMember(in.budgetId());
        int limit = in.limit() == null ? DEFAULT_LIMIT : in.limit();
        int offset = in.offset() == null ? 0 : in.offset();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ActionException.invalid("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw ActionException.invalid("offset must not be negative");
        }
        return txns.list(new TransactionService.Filter(in.budgetId(), in.accountId(), in.categoryId(), in.payeeId(),
                in.sinceDate(), in.untilDate(), in.search(), Boolean.TRUE.equals(in.unapprovedOnly()), limit, offset));
    }

    public record GetTransactionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transaction id") UUID transactionId) {}

    @McpAction(name = "get_transaction", readOnly = true, description = "Get one transaction (with split lines)."
            + AMOUNTS)
    public TransactionDto getTransaction(GetTransactionInput in) {
        access.requireMember(in.budgetId());
        return txns.require(in.budgetId(), in.transactionId());
    }

    // ------------------------------------------------------------------ write

    public record CreateTransactionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account the transaction is in") UUID accountId,
            @ActionParam("Date, YYYY-MM-DD") LocalDate date,
            @ActionParam("Amount in milliunits; negative = outflow (-12340 = 12.34 spent), positive = inflow")
            Long amount,
            @ActionParam(value = "Existing payee id (a transfer payee makes this a transfer)", required = false)
            UUID payeeId,
            @ActionParam(value = "Payee name; matched case-insensitively, created if new. "
                    + "\"Transfer : <account>\" makes a transfer", required = false) String payeeName,
            @ActionParam(value = "Category id; omit to use the payee's last category. Use the "
                    + "\"Inflow: Ready to Assign\" category for income", required = false) UUID categoryId,
            @ActionParam(value = "Memo", required = false) String memo,
            @ActionParam(value = "Cleared state (default UNCLEARED)", required = false) Cleared cleared,
            @ActionParam(value = "Approved (default true)", required = false) Boolean approved,
            @ActionParam(value = "Flag color", required = false) FlagColor flagColor,
            @ActionParam(value = "Make this a transfer to/from this account (the other side is created "
                    + "automatically)", required = false) UUID transferAccountId,
            @ActionParam(value = "Split lines; their amounts must sum to amount", required = false)
            List<SplitInput> splits,
            @ActionParam(value = "Unique import id within the account (for dedupe)", required = false)
            String importId) {}

    @McpAction(name = "create_transaction", description = """
            Record one transaction in an account. """ + AMOUNTS + """
            Payee: give payeeId or payeeName; an unknown payeeName creates the payee. Category: when categoryId \
            is omitted and the payee was used before, the payee's most recently used category is applied \
            (memorized category); income should go to the "Inflow: Ready to Assign" category (see \
            list_categories). Categories are ignored on tracking (off-budget) accounts. \
            Transfers: set transferAccountId (or use the "Transfer : <account>" payee); the matching transaction \
            with the negated amount is created in the other account automatically, so never record both sides. \
            Transfers between two budget accounts carry no category; a transfer between a budget account and a \
            tracking account takes the category on the budget side. \
            Splits: pass splits[] (each {amount, categoryId?, payeeName?, memo?}) whose amounts sum exactly to \
            amount, and omit categoryId; transfers cannot be split. \
            importId must be unique per account (CONFLICT otherwise); prefer import_transactions for bank data.""")
    public TransactionDto createTransaction(CreateTransactionInput in) {
        access.requireMember(in.budgetId());
        UUID id = txns.create(in.budgetId(), new TransactionService.Request(in.accountId(), in.date(), in.amount(),
                in.payeeId(), in.payeeName(), in.categoryId(), false, in.memo(), in.cleared(), in.approved(),
                in.flagColor(), in.transferAccountId(), in.splits(), in.importId()), true);
        return txns.require(in.budgetId(), id);
    }

    public record UpdateTransactionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transaction id") UUID transactionId,
            @ActionParam(value = "Move to this account", required = false) UUID accountId,
            @ActionParam(value = "New date, YYYY-MM-DD", required = false) LocalDate date,
            @ActionParam(value = "New amount in milliunits (negative = outflow)", required = false) Long amount,
            @ActionParam(value = "New payee id (a transfer payee turns this into a transfer)", required = false)
            UUID payeeId,
            @ActionParam(value = "New payee name (created if new; \"\" removes the payee)", required = false)
            String payeeName,
            @ActionParam(value = "New category id", required = false) UUID categoryId,
            @ActionParam(value = "New memo (\"\" clears it)", required = false) String memo,
            @ActionParam(value = "New cleared state", required = false) Cleared cleared,
            @ActionParam(value = "Approve (true) or un-approve (false)", required = false) Boolean approved,
            @ActionParam(value = "New flag color (NONE removes the flag)", required = false) FlagColor flagColor,
            @ActionParam(value = "Turn into a transfer to/from this account", required = false) UUID transferAccountId,
            @ActionParam(value = "Replace the split lines (must sum to the amount); [] removes the split",
                    required = false) List<SplitInput> splits,
            @ActionParam(value = "New import id", required = false) String importId,
            @ActionParam(value = "true = make the transaction uncategorized", required = false) Boolean clearCategory) {}

    @McpAction(name = "update_transaction", description = """
            Change fields of a transaction; omitted (null) fields stay unchanged. """ + AMOUNTS + """
            To clear: memo "" removes the memo, payeeName "" removes the payee, flagColor NONE removes the \
            flag, clearCategory=true makes it uncategorized, splits [] un-splits. \
            On a transfer, changing amount or date updates the other side too (it always holds the negated \
            amount); changing the payee to a normal payee turns it back into a regular transaction and deletes \
            the other side; setting transferAccountId (or a transfer payee) turns a regular transaction into a \
            transfer and creates the other side. When changing the amount of a split transaction, also pass the \
            new splits. Memorized categories are not applied on update.""")
    public TransactionDto updateTransaction(UpdateTransactionInput in) {
        access.requireMember(in.budgetId());
        txns.update(in.budgetId(), in.transactionId(), new TransactionService.Request(in.accountId(), in.date(),
                in.amount(), in.payeeId(), in.payeeName(), in.categoryId(), Boolean.TRUE.equals(in.clearCategory()),
                in.memo(), in.cleared(), in.approved(), in.flagColor(), in.transferAccountId(), in.splits(),
                in.importId()));
        return txns.require(in.budgetId(), in.transactionId());
    }

    public record DeleteTransactionInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transaction id") UUID transactionId) {}

    @McpAction(name = "delete_transaction", destructive = true, description = """
            Delete a transaction (including its split lines). Deleting either side of a transfer deletes both \
            sides.""")
    public Map<String, Object> deleteTransaction(DeleteTransactionInput in) {
        access.requireMember(in.budgetId());
        txns.delete(in.budgetId(), in.transactionId());
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ import

    public record ImportTransactionsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account the statement belongs to") UUID accountId,
            @ActionParam("Statement lines") List<ImportLine> transactions,
            @ActionParam(value = "Mark the imported transactions approved (default false: the user reviews them)",
                    required = false) Boolean approved) {}

    public record ImportResult(int imported, int duplicates, List<UUID> transactionIds,
                               List<String> duplicateImportIds) {}

    @McpAction(name = "import_transactions", description = """
            Import bank-statement lines into one account. This is the preferred way to add bank data. """
            + AMOUNTS + """
            Lines are deduplicated per account on importId: a line whose importId already exists in the account \
            (or repeats within the batch) is skipped and reported in duplicateImportIds, so importing the same \
            or an overlapping statement twice is safe. Give each line the bank's own unique id as importId when \
            the statement has one; otherwise omit it and the server generates YNAY:<amount>:<date>:<occurrence> \
            (occurrence counts identical amount+date lines within this batch, starting at 1), which only dedupes \
            reliably when whole statements/periods are re-imported. \
            Payees are matched case-insensitively by payeeName and created when new; a payeeName of \
            "Transfer : <account>" records a transfer (the other side is created automatically, so don't also \
            import it on the other account). When categoryId is omitted, the payee's most recently used \
            category is applied. Imported transactions default to cleared=CLEARED and approved=false so the \
            user can review them (see list_transactions unapprovedOnly, approve_transactions).""")
    public ImportResult importTransactions(ImportTransactionsInput in) {
        access.requireMember(in.budgetId());
        AccountDto account = accounts.require(in.budgetId(), in.accountId());
        if (account.closed()) {
            throw ActionException.invalid("Account '" + account.name() + "' is closed; reopen it first");
        }
        List<ImportLine> lines = in.transactions() == null ? List.of() : in.transactions();
        if (lines.size() > MAX_IMPORT_LINES) {
            throw ActionException.invalid("At most " + MAX_IMPORT_LINES + " lines per import");
        }
        List<String> importIds = new ArrayList<>(lines.size());
        Map<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ImportLine line = lines.get(i);
            if (line == null || line.date() == null || line.amount() == null) {
                throw ActionException.invalid("transactions[" + i + "] needs date and amount");
            }
            if (line.categoryId() != null) {
                txns.requireCategory(in.budgetId(), line.categoryId());
            }
            String importId = Inputs.blankToNull(line.importId());
            if (importId == null) {
                String key = line.amount() + ":" + line.date();
                int occurrence = occurrences.merge(key, 1, Integer::sum);
                importId = "YNAY:" + key + ":" + occurrence;
            }
            importIds.add(importId);
        }
        Set<String> existing = new HashSet<>(importIds.isEmpty() ? List.of() : jdbc.sql(
                        "select import_id from txn where account_id = :account and import_id in (:ids)")
                .param("account", account.id())
                .param("ids", new LinkedHashSet<>(importIds))
                .query(String.class)
                .list());
        boolean approved = Boolean.TRUE.equals(in.approved());
        List<UUID> created = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ImportLine line = lines.get(i);
            String importId = importIds.get(i);
            if (!existing.add(importId)) {
                duplicates.add(importId);
                continue;
            }
            created.add(txns.create(in.budgetId(), new TransactionService.Request(account.id(), line.date(),
                    line.amount(), null, line.payeeName(), line.categoryId(), false, line.memo(),
                    line.cleared() == null ? Cleared.CLEARED : line.cleared(), approved, null, null, null, importId),
                    true));
        }
        return new ImportResult(created.size(), duplicates.size(), created, duplicates);
    }

    // ------------------------------------------------------------------ bulk state changes

    public record ApproveTransactionsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transactions to approve") List<UUID> transactionIds) {}

    @McpAction(name = "approve_transactions", idempotent = true, description = """
            Approve transactions (e.g. after the user reviewed an import). Returns {approved: n}, the number of \
            transactions that were not approved before. Fails with NOT_FOUND if an id isn't in this budget.""")
    public Map<String, Object> approveTransactions(ApproveTransactionsInput in) {
        access.requireMember(in.budgetId());
        Set<UUID> ids = requireTransactionIds(in.budgetId(), in.transactionIds());
        int n = ids.isEmpty() ? 0 : jdbc.sql("""
                        update txn set approved = true, updated_at = now()
                        where budget_id = :budget and id in (:ids) and not approved""")
                .param("budget", in.budgetId())
                .param("ids", ids)
                .update();
        return Map.of("approved", n);
    }

    public record SetTransactionsClearedInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Transactions to update") List<UUID> transactionIds,
            @ActionParam("New cleared state") Cleared cleared) {}

    @McpAction(name = "set_transactions_cleared", idempotent = true, description = """
            Set the cleared state (UNCLEARED, CLEARED or RECONCILED) of several transactions. Only the given \
            side of a transfer changes. Returns {updated: n}. Fails with NOT_FOUND if an id isn't in this \
            budget.""")
    public Map<String, Object> setTransactionsCleared(SetTransactionsClearedInput in) {
        access.requireMember(in.budgetId());
        if (in.cleared() == null) {
            throw ActionException.invalid("cleared is required");
        }
        Set<UUID> ids = requireTransactionIds(in.budgetId(), in.transactionIds());
        int n = ids.isEmpty() ? 0 : jdbc.sql("""
                        update txn set cleared = :cleared, updated_at = now()
                        where budget_id = :budget and id in (:ids)""")
                .param("cleared", in.cleared().name())
                .param("budget", in.budgetId())
                .param("ids", ids)
                .update();
        return Map.of("updated", n);
    }

    private Set<UUID> requireTransactionIds(UUID budgetId, List<UUID> transactionIds) {
        if (transactionIds == null) {
            throw ActionException.invalid("transactionIds is required");
        }
        Set<UUID> ids = new LinkedHashSet<>(transactionIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            return ids;
        }
        Set<UUID> found = new HashSet<>(jdbc.sql("select id from txn where budget_id = :budget and id in (:ids)")
                .param("budget", budgetId)
                .param("ids", ids)
                .query(UUID.class)
                .list());
        for (UUID id : ids) {
            if (!found.contains(id)) {
                throw ActionException.notFound("Transaction " + id);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ reconcile

    public record ReconcileAccountInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account to reconcile") UUID accountId,
            @ActionParam("Balance on the bank statement in milliunits (credit card debt is negative)")
            Long statementBalance,
            @ActionParam(value = "Statement date (default today); transactions after it are left alone",
                    required = false) LocalDate statementDate) {}

    @McpAction(name = "reconcile_account", description = """
            Reconcile an account against a bank statement: every CLEARED transaction dated on or before \
            statementDate becomes RECONCILED. If the account's cleared balance up to that date (CLEARED + \
            RECONCILED transactions) differs from statementBalance, a reconciled "Reconciliation Balance \
            Adjustment" transaction for the difference is added (categorized to Ready to Assign on budget \
            accounts). Mark the transactions that appear on the statement cleared first \
            (set_transactions_cleared). """ + AMOUNTS + """
            Returns {reconciledCount, adjustmentTransactionId (null if the balances matched)}.""")
    public TransactionService.ReconcileResult reconcileAccount(ReconcileAccountInput in) {
        access.requireMember(in.budgetId());
        AccountDto account = accounts.require(in.budgetId(), in.accountId());
        if (in.statementBalance() == null) {
            throw ActionException.invalid("statementBalance is required");
        }
        LocalDate date = in.statementDate() == null ? LocalDate.now() : in.statementDate();
        return txns.reconcile(in.budgetId(), account, in.statementBalance(), date);
    }
}
