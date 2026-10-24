package io.ynay.transaction;

import io.ynay.account.AccountDto;
import io.ynay.account.AccountService;
import io.ynay.action.ActionException;
import io.ynay.budget.Inputs;
import io.ynay.category.CategoryDto;
import io.ynay.category.CategoryService;
import io.ynay.payee.PayeeDto;
import io.ynay.payee.PayeeService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Transaction persistence and the ledger rules: payee resolution and auto-creation, transfers as
 * linked pairs, split validation, memorized payee categories and category placement.
 *
 * <p>Category placement (what the month math relies on):
 * <ul>
 *   <li>split parents have {@code category_id = null}; their lines carry the categories;</li>
 *   <li>transactions in tracking accounts are never categorized;</li>
 *   <li>transfers between two on-budget accounts (or two tracking accounts) carry no category on
 *       either side; a transfer between an on-budget and a tracking account carries the category
 *       on its on-budget side only.</li>
 * </ul>
 */
@Service
public class TransactionService {

    public static final String STARTING_BALANCE_PAYEE = "Starting Balance";
    public static final String RECONCILIATION_PAYEE = "Reconciliation Balance Adjustment";

    private static final String SELECT = """
            select t.id, t.budget_id, t.account_id, a.name as account_name, t.date, t.amount, t.payee_id,
                   p.name as payee_name, t.category_id, c.name as category_name, t.memo, t.cleared, t.approved,
                   t.flag_color, t.transfer_account_id, t.transfer_transaction_id, t.import_id, t.is_split
            from txn t
                join account a on a.id = t.account_id
                left join payee p on p.id = t.payee_id
                left join category c on c.id = t.category_id
            """;

    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final PayeeService payees;
    private final CategoryService categories;

    public TransactionService(JdbcClient jdbc, AccountService accounts, PayeeService payees,
                              CategoryService categories) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.payees = payees;
        this.categories = categories;
    }

    // ================================================================== reads

    public Optional<TransactionDto> find(UUID budgetId, UUID transactionId) {
        if (transactionId == null) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " where t.budget_id = ? and t.id = ?")
                .params(budgetId, transactionId)
                .query(TransactionService::map)
                .optional()
                .map(t -> withSplits(List.of(t)).getFirst());
    }

    /** Loads a transaction of the budget or fails with NOT_FOUND. */
    public TransactionDto require(UUID budgetId, UUID transactionId) {
        if (transactionId == null) {
            throw ActionException.invalid("transactionId is required");
        }
        return find(budgetId, transactionId)
                .orElseThrow(() -> ActionException.notFound("Transaction " + transactionId));
    }

    public record Filter(UUID budgetId, UUID accountId, UUID categoryId, UUID payeeId, LocalDate sinceDate,
                         LocalDate untilDate, String search, boolean unapprovedOnly, int limit, int offset) {}

    public record Page(List<TransactionDto> transactions, long total) {}

    public Page list(Filter f) {
        StringBuilder where = new StringBuilder(" where t.budget_id = :budgetId");
        Map<String, Object> params = new HashMap<>();
        params.put("budgetId", f.budgetId());
        if (f.accountId() != null) {
            where.append(" and t.account_id = :accountId");
            params.put("accountId", f.accountId());
        }
        if (f.categoryId() != null) {
            where.append(" and (t.category_id = :categoryId or exists (select 1 from txn_split s"
                    + " where s.transaction_id = t.id and s.category_id = :categoryId))");
            params.put("categoryId", f.categoryId());
        }
        if (f.payeeId() != null) {
            where.append(" and (t.payee_id = :payeeId or exists (select 1 from txn_split s"
                    + " where s.transaction_id = t.id and s.payee_id = :payeeId))");
            params.put("payeeId", f.payeeId());
        }
        if (f.sinceDate() != null) {
            where.append(" and t.date >= :sinceDate");
            params.put("sinceDate", f.sinceDate());
        }
        if (f.untilDate() != null) {
            where.append(" and t.date <= :untilDate");
            params.put("untilDate", f.untilDate());
        }
        String search = Inputs.blankToNull(f.search());
        if (search != null) {
            where.append(" and (t.memo ilike :q or p.name ilike :q or c.name ilike :q or a.name ilike :q"
                    + " or exists (select 1 from txn_split s left join payee sp on sp.id = s.payee_id"
                    + " left join category sc on sc.id = s.category_id where s.transaction_id = t.id"
                    + " and (s.memo ilike :q or sp.name ilike :q or sc.name ilike :q)))");
            params.put("q", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.unapprovedOnly()) {
            where.append(" and not t.approved");
        }
        String from = " from txn t join account a on a.id = t.account_id left join payee p on p.id = t.payee_id"
                + " left join category c on c.id = t.category_id";
        long total = jdbc.sql("select count(*)" + from + where)
                .params(params)
                .query(Long.class)
                .single();
        params.put("limit", f.limit());
        params.put("offset", f.offset());
        List<TransactionDto> rows = jdbc.sql(SELECT + where
                        + " order by t.date desc, t.created_at desc, t.amount, t.id limit :limit offset :offset")
                .params(params)
                .query(TransactionService::map)
                .list();
        return new Page(withSplits(rows), total);
    }

    private List<TransactionDto> withSplits(List<TransactionDto> txns) {
        List<UUID> splitIds = txns.stream().filter(t -> t.splits() == null).map(TransactionDto::id).toList();
        Map<UUID, List<SplitDto>> byTxn = new HashMap<>();
        if (!splitIds.isEmpty()) {
            jdbc.sql("""
                            select s.transaction_id, s.id, s.amount, s.category_id, c.name as category_name,
                                   s.payee_id, p.name as payee_name, s.memo
                            from txn_split s
                                left join category c on c.id = s.category_id
                                left join payee p on p.id = s.payee_id
                            where s.transaction_id in (:ids)
                            order by s.sort_order, s.id""")
                    .param("ids", splitIds)
                    .query((rs, n) -> {
                        byTxn.computeIfAbsent(rs.getObject("transaction_id", UUID.class), k -> new ArrayList<>())
                                .add(new SplitDto(
                                        rs.getObject("id", UUID.class),
                                        rs.getLong("amount"),
                                        rs.getObject("category_id", UUID.class),
                                        rs.getString("category_name"),
                                        rs.getObject("payee_id", UUID.class),
                                        rs.getString("payee_name"),
                                        rs.getString("memo")));
                        return null;
                    })
                    .list();
        }
        return txns.stream()
                .map(t -> t.splits() == null ? t.withSplits(byTxn.getOrDefault(t.id(), List.of())) : t)
                .toList();
    }

    /** Maps a row; {@code splits} is null for split parents (filled by {@link #withSplits}). */
    static TransactionDto map(ResultSet rs, int row) throws SQLException {
        boolean isSplit = rs.getBoolean("is_split");
        return new TransactionDto(
                rs.getObject("id", UUID.class),
                rs.getObject("budget_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("account_name"),
                rs.getObject("date", LocalDate.class),
                rs.getLong("amount"),
                rs.getObject("payee_id", UUID.class),
                rs.getString("payee_name"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getString("memo"),
                Cleared.valueOf(rs.getString("cleared")),
                rs.getBoolean("approved"),
                rs.getString("flag_color"),
                rs.getObject("transfer_account_id", UUID.class),
                rs.getObject("transfer_transaction_id", UUID.class),
                rs.getString("import_id"),
                isSplit ? null : List.of());
    }

    // ================================================================== writes

    /**
     * A create/update request. {@code null} means "not specified" (on update: unchanged).
     * {@code payeeName = ""} clears the payee, {@code memo = ""} clears the memo,
     * {@code flagColor = NONE} clears the flag, {@code splits = []} un-splits.
     */
    public record Request(UUID accountId, LocalDate date, Long amount, UUID payeeId, String payeeName,
                          UUID categoryId, boolean clearCategory, String memo, Cleared cleared, Boolean approved,
                          FlagColor flagColor, UUID transferAccountId, List<SplitInput> splits, String importId) {}

    /** Payee resolution result: a transfer target, an existing payee, a payee to create, or nothing. */
    private record PayeeChoice(AccountDto transferAccount, UUID payeeId, String newPayeeName) {
        static final PayeeChoice NONE = new PayeeChoice(null, null, null);
    }

    private record SplitDraft(long amount, UUID categoryId, UUID payeeId, String newPayeeName, String memo) {}

    /** Snapshot of a stored txn row. */
    private record Row(UUID id, UUID accountId, LocalDate date, long amount, UUID payeeId, UUID categoryId,
                       String memo, Cleared cleared, boolean approved, String flagColor, UUID transferAccountId,
                       UUID transferTransactionId, String importId, boolean isSplit) {}

    /** Creates a transaction (and the other side of a transfer). Returns the new id. */
    public UUID create(UUID budgetId, Request r, boolean memorizeCategory) {
        AccountDto account = accounts.require(budgetId, r.accountId());
        requireOpen(account);
        if (r.date() == null) {
            throw ActionException.invalid("date is required");
        }
        if (r.amount() == null) {
            throw ActionException.invalid("amount is required");
        }
        long amount = r.amount();
        PayeeChoice payee = resolvePayee(budgetId, r.payeeId(), r.payeeName(), r.transferAccountId());
        AccountDto transfer = payee.transferAccount();
        validateTransfer(account, transfer, null);
        List<SplitDraft> splits = resolveSplits(budgetId, r.splits(), amount);
        if (!splits.isEmpty() && transfer != null) {
            throw ActionException.invalid("A transfer cannot be split; create the transfer and the split separately");
        }
        UUID category = null;
        if (!splits.isEmpty()) {
            if (r.categoryId() != null) {
                throw ActionException.invalid("Omit categoryId on a split transaction; categorize the split lines");
            }
        } else if (r.categoryId() != null && !r.clearCategory()) {
            category = categories.require(budgetId, r.categoryId()).id();
        } else if (memorizeCategory && !r.clearCategory() && transfer == null && account.onBudget()
                && payee.payeeId() != null) {
            category = memorizedCategory(budgetId, payee.payeeId()).orElse(null);
        }
        String importId = Inputs.blankToNull(r.importId());
        boolean approved = r.approved() == null || r.approved();
        Cleared cleared = r.cleared() == null ? Cleared.UNCLEARED : r.cleared();

        UUID payeeId = persistPayee(budgetId, payee);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            insert into txn (id, budget_id, account_id, date, amount, payee_id, category_id, memo,
                                             cleared, approved, flag_color, transfer_account_id, import_id, is_split)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, budgetId, account.id(), r.date(), amount, payeeId,
                            rowCategory(account, transfer, splits, category), Inputs.blankToNull(r.memo()),
                            cleared.name(), approved, r.flagColor() == null ? null : r.flagColor().stored(),
                            transfer == null ? null : transfer.id(), importId, !splits.isEmpty())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ActionException.conflict("A transaction with importId '" + importId + "' already exists in "
                    + account.name());
        }
        insertSplits(budgetId, id, account, splits);
        if (transfer != null) {
            createCounterpart(budgetId, id, account, transfer, r.date(), amount, Inputs.blankToNull(r.memo()),
                    approved, counterpartCategory(account, transfer, category));
        }
        return id;
    }

    /** Updates a transaction, keeping a transfer's other side consistent. */
    public void update(UUID budgetId, UUID transactionId, Request r) {
        Row ex = requireRow(budgetId, transactionId);
        AccountDto account = accounts.require(budgetId, r.accountId() != null ? r.accountId() : ex.accountId());
        if (!account.id().equals(ex.accountId())) {
            requireOpen(account);
        }
        LocalDate date = r.date() != null ? r.date() : ex.date();
        long amount = r.amount() != null ? r.amount() : ex.amount();

        PayeeChoice payee;
        if (r.payeeId() != null || r.transferAccountId() != null
                || (r.payeeName() != null && !r.payeeName().isBlank())) {
            payee = resolvePayee(budgetId, r.payeeId(), r.payeeName(), r.transferAccountId());
        } else if (r.payeeName() != null) {
            payee = PayeeChoice.NONE; // payeeName "" clears the payee (and ends a transfer)
        } else if (ex.transferAccountId() != null) {
            payee = new PayeeChoice(accounts.require(budgetId, ex.transferAccountId()), null, null);
        } else {
            payee = new PayeeChoice(null, ex.payeeId(), null);
        }
        AccountDto transfer = payee.transferAccount();
        validateTransfer(account, transfer, ex.transferAccountId());

        List<SplitDraft> splits;
        if (r.splits() != null) {
            splits = resolveSplits(budgetId, r.splits(), amount);
        } else if (ex.isSplit()) {
            splits = loadSplitDrafts(ex.id());
            long sum = splits.stream().mapToLong(SplitDraft::amount).sum();
            if (sum != amount) {
                throw ActionException.invalid("The split lines sum to " + sum + " but the new amount is " + amount
                        + "; pass the updated splits as well");
            }
        } else {
            splits = List.of();
        }
        if (!splits.isEmpty() && transfer != null) {
            throw ActionException.invalid(ex.isSplit() && r.splits() == null
                    ? "A split transaction cannot become a transfer; pass splits: [] to un-split it first"
                    : "A transfer cannot be split");
        }

        UUID category;
        if (!splits.isEmpty()) {
            if (r.categoryId() != null && !r.clearCategory()) {
                throw ActionException.invalid("Omit categoryId on a split transaction; categorize the split lines");
            }
            category = null;
        } else if (r.clearCategory()) {
            category = null;
        } else if (r.categoryId() != null) {
            category = categories.require(budgetId, r.categoryId()).id();
        } else if (ex.isSplit()) {
            category = null;
        } else if (ex.categoryId() == null && ex.transferTransactionId() != null) {
            category = jdbc.sql("select category_id from txn where id = ?")
                    .param(ex.transferTransactionId())
                    .query(UUID.class)
                    .optional()
                    .orElse(null);
        } else {
            category = ex.categoryId();
        }

        String memo = r.memo() != null ? Inputs.blankToNull(r.memo()) : ex.memo();
        Cleared cleared = r.cleared() != null ? r.cleared() : ex.cleared();
        boolean approved = r.approved() != null ? r.approved() : ex.approved();
        String flag = r.flagColor() != null ? r.flagColor().stored() : ex.flagColor();
        String importId = r.importId() != null ? Inputs.blankToNull(r.importId()) : ex.importId();

        UUID payeeId = persistPayee(budgetId, payee);
        try {
            jdbc.sql("""
                            update txn set account_id = ?, date = ?, amount = ?, payee_id = ?, category_id = ?, memo = ?,
                                           cleared = ?, approved = ?, flag_color = ?, transfer_account_id = ?,
                                           import_id = ?, is_split = ?, updated_at = now()
                            where id = ?""")
                    .params(account.id(), date, amount, payeeId, rowCategory(account, transfer, splits, category), memo,
                            cleared.name(), approved, flag, transfer == null ? null : transfer.id(), importId,
                            !splits.isEmpty(), ex.id())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ActionException.conflict("A transaction with importId '" + importId + "' already exists in "
                    + account.name());
        }
        if (r.splits() != null || !account.id().equals(ex.accountId())) {
            jdbc.sql("delete from txn_split where transaction_id = ?").param(ex.id()).update();
            insertSplits(budgetId, ex.id(), account, splits);
        }

        UUID counterpart = ex.transferTransactionId();
        UUID cpCategory = transfer == null ? null : counterpartCategory(account, transfer, category);
        if (transfer != null && counterpart != null && transfer.id().equals(ex.transferAccountId())) {
            jdbc.sql("""
                            update txn set amount = ?, date = ?, payee_id = ?, transfer_account_id = ?, category_id = ?,
                                           updated_at = now()
                            where id = ?""")
                    .params(-amount, date, payees.transferPayee(account.id()).id(), account.id(), cpCategory,
                            counterpart)
                    .update();
        } else {
            if (counterpart != null) {
                jdbc.sql("update txn set transfer_transaction_id = null where id = ?").param(ex.id()).update();
                jdbc.sql("delete from txn where id = ? and budget_id = ?").params(counterpart, budgetId).update();
            }
            if (transfer != null) {
                createCounterpart(budgetId, ex.id(), account, transfer, date, amount, memo, approved, cpCategory);
            }
        }
    }

    /** Deletes a transaction; deleting either side of a transfer deletes both. */
    public void delete(UUID budgetId, UUID transactionId) {
        Row ex = requireRow(budgetId, transactionId);
        if (ex.transferTransactionId() != null) {
            jdbc.sql("delete from txn where budget_id = ? and id in (?, ?)")
                    .params(budgetId, ex.id(), ex.transferTransactionId())
                    .update();
        } else {
            jdbc.sql("delete from txn where budget_id = ? and id = ?").params(budgetId, ex.id()).update();
        }
    }

    /** Creates the "Starting Balance" transaction of a new account. */
    public UUID createStartingBalance(UUID budgetId, AccountDto account, long amount, LocalDate date) {
        UUID category = account.onBudget() && !account.type().isCredit() ? categories.readyToAssignId(budgetId) : null;
        return insertSystemTxn(budgetId, account, date, amount, STARTING_BALANCE_PAYEE, category, Cleared.CLEARED);
    }

    public record ReconcileResult(int reconciledCount, UUID adjustmentTransactionId) {}

    /**
     * Reconciles an account: CLEARED transactions dated on/before {@code statementDate} become RECONCILED;
     * if the cleared balance up to that date differs from the statement balance, a reconciled
     * "Reconciliation Balance Adjustment" transaction for the difference is added (categorized to Ready
     * to Assign on on-budget accounts).
     */
    public ReconcileResult reconcile(UUID budgetId, AccountDto account, long statementBalance, LocalDate statementDate) {
        long cleared = jdbc.sql("""
                        select coalesce(sum(amount), 0) from txn
                        where account_id = ? and cleared <> 'UNCLEARED' and date <= ?""")
                .params(account.id(), statementDate)
                .query(Long.class)
                .single();
        int reconciled = jdbc.sql("""
                        update txn set cleared = 'RECONCILED', updated_at = now()
                        where account_id = ? and cleared = 'CLEARED' and date <= ?""")
                .params(account.id(), statementDate)
                .update();
        UUID adjustment = null;
        long difference = statementBalance - cleared;
        if (difference != 0) {
            UUID category = account.onBudget() ? categories.readyToAssignId(budgetId) : null;
            adjustment = insertSystemTxn(budgetId, account, statementDate, difference, RECONCILIATION_PAYEE, category,
                    Cleared.RECONCILED);
        }
        return new ReconcileResult(reconciled, adjustment);
    }

    /**
     * Before an account is deleted: turns the other sides of its transfers into ordinary transactions
     * (payee = the deleted account's name) so the other accounts keep their balances.
     */
    public void detachTransfersTo(UUID budgetId, AccountDto account) {
        long count = jdbc.sql("select count(*) from txn where budget_id = ? and transfer_account_id = ? and account_id <> ?")
                .params(budgetId, account.id(), account.id())
                .query(Long.class)
                .single();
        if (count == 0) {
            return;
        }
        UUID payeeId = payees.findOrCreate(budgetId, account.name()).id();
        jdbc.sql("""
                        update txn set transfer_account_id = null, transfer_transaction_id = null, payee_id = ?,
                                       updated_at = now()
                        where budget_id = ? and transfer_account_id = ? and account_id <> ?""")
                .params(payeeId, budgetId, account.id(), account.id())
                .update();
    }

    /** Most recent category used with a payee (YNAB-style memorized category). */
    public Optional<UUID> memorizedCategory(UUID budgetId, UUID payeeId) {
        return jdbc.sql("""
                        select t.category_id from txn t join account a on a.id = t.account_id
                        where t.budget_id = ? and t.payee_id = ? and t.category_id is not null
                          and t.transfer_account_id is null and a.on_budget
                        order by t.date desc, t.created_at desc limit 1""")
                .params(budgetId, payeeId)
                .query(UUID.class)
                .optional();
    }

    // ================================================================== helpers

    private UUID insertSystemTxn(UUID budgetId, AccountDto account, LocalDate date, long amount, String payeeName,
                                 UUID category, Cleared cleared) {
        UUID payeeId = payees.findOrCreate(budgetId, payeeName).id();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into txn (id, budget_id, account_id, date, amount, payee_id, category_id, cleared, approved)
                        values (?, ?, ?, ?, ?, ?, ?, ?, true)""")
                .params(id, budgetId, account.id(), date, amount, payeeId, category, cleared.name())
                .update();
        return id;
    }

    private Row requireRow(UUID budgetId, UUID transactionId) {
        if (transactionId == null) {
            throw ActionException.invalid("transactionId is required");
        }
        return jdbc.sql("select * from txn where budget_id = ? and id = ?")
                .params(budgetId, transactionId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("date", LocalDate.class),
                        rs.getLong("amount"),
                        rs.getObject("payee_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("memo"),
                        Cleared.valueOf(rs.getString("cleared")),
                        rs.getBoolean("approved"),
                        rs.getString("flag_color"),
                        rs.getObject("transfer_account_id", UUID.class),
                        rs.getObject("transfer_transaction_id", UUID.class),
                        rs.getString("import_id"),
                        rs.getBoolean("is_split")))
                .optional()
                .orElseThrow(() -> ActionException.notFound("Transaction " + transactionId));
    }

    private static void requireOpen(AccountDto account) {
        if (account.closed()) {
            throw ActionException.invalid("Account '" + account.name() + "' is closed; reopen it first");
        }
    }

    private static void validateTransfer(AccountDto account, AccountDto transfer, UUID existingTransferAccountId) {
        if (transfer == null) {
            return;
        }
        if (transfer.id().equals(account.id())) {
            throw ActionException.invalid("Cannot transfer from an account to itself");
        }
        if (transfer.closed() && !transfer.id().equals(existingTransferAccountId)) {
            throw ActionException.invalid("Cannot transfer to closed account '" + transfer.name() + "'");
        }
    }

    private PayeeChoice resolvePayee(UUID budgetId, UUID payeeId, String payeeName, UUID transferAccountId) {
        AccountDto transfer = null;
        UUID existing = null;
        String newName = null;
        if (payeeId != null) {
            PayeeDto p = payees.require(budgetId, payeeId);
            if (p.isTransfer()) {
                transfer = accounts.require(budgetId, p.transferAccountId());
            } else {
                existing = p.id();
            }
        } else if (payeeName != null && !payeeName.isBlank()) {
            String name = Inputs.requireName(payeeName, "payeeName");
            Optional<PayeeDto> p = payees.findByName(budgetId, name);
            if (p.isPresent() && p.get().isTransfer()) {
                transfer = accounts.require(budgetId, p.get().transferAccountId());
            } else if (p.isPresent()) {
                existing = p.get().id();
            } else if (name.regionMatches(true, 0, PayeeService.TRANSFER_PREFIX, 0,
                    PayeeService.TRANSFER_PREFIX.length())) {
                throw ActionException.invalid("'" + name + "' looks like a transfer payee but no account named '"
                        + name.substring(PayeeService.TRANSFER_PREFIX.length()).strip()
                        + "' exists. Use transferAccountId for transfers.");
            } else {
                newName = name;
            }
        }
        if (transferAccountId != null) {
            AccountDto target = accounts.require(budgetId, transferAccountId);
            if (transfer != null && !transfer.id().equals(target.id())) {
                throw ActionException.invalid("The transfer payee and transferAccountId refer to different accounts");
            }
            return new PayeeChoice(target, null, null);
        }
        return new PayeeChoice(transfer, existing, newName);
    }

    /** Creates a new payee if needed; returns the payee id to store on the row. */
    private UUID persistPayee(UUID budgetId, PayeeChoice choice) {
        if (choice.transferAccount() != null) {
            return payees.transferPayee(choice.transferAccount().id()).id();
        }
        if (choice.newPayeeName() != null) {
            return payees.findOrCreate(budgetId, choice.newPayeeName()).id();
        }
        return choice.payeeId();
    }

    private List<SplitDraft> resolveSplits(UUID budgetId, List<SplitInput> input, long amount) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<SplitDraft> drafts = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < input.size(); i++) {
            SplitInput s = input.get(i);
            if (s == null || s.amount() == null) {
                throw ActionException.invalid("splits[" + i + "].amount is required");
            }
            sum += s.amount();
            UUID category = s.categoryId() == null ? null : categories.require(budgetId, s.categoryId()).id();
            UUID payeeId = null;
            String newName = null;
            if (s.payeeName() != null && !s.payeeName().isBlank()) {
                String name = Inputs.requireName(s.payeeName(), "splits[" + i + "].payeeName");
                Optional<PayeeDto> p = payees.findByName(budgetId, name);
                if (p.isPresent() && p.get().isTransfer()
                        || p.isEmpty() && name.regionMatches(true, 0, PayeeService.TRANSFER_PREFIX, 0,
                        PayeeService.TRANSFER_PREFIX.length())) {
                    throw ActionException.invalid("Split lines cannot be transfers (splits[" + i + "])");
                }
                if (p.isPresent()) {
                    payeeId = p.get().id();
                } else {
                    newName = name;
                }
            }
            drafts.add(new SplitDraft(s.amount(), category, payeeId, newName, Inputs.blankToNull(s.memo())));
        }
        if (sum != amount) {
            throw ActionException.invalid("Split amounts sum to " + sum + " but the transaction amount is " + amount
                    + " (milliunits); they must be equal");
        }
        return drafts;
    }

    private List<SplitDraft> loadSplitDrafts(UUID transactionId) {
        return jdbc.sql("select amount, category_id, payee_id, memo from txn_split where transaction_id = ?"
                        + " order by sort_order, id")
                .param(transactionId)
                .query((rs, n) -> new SplitDraft(rs.getLong("amount"), rs.getObject("category_id", UUID.class),
                        rs.getObject("payee_id", UUID.class), null, rs.getString("memo")))
                .list();
    }

    private void insertSplits(UUID budgetId, UUID transactionId, AccountDto account, List<SplitDraft> splits) {
        int order = 0;
        for (SplitDraft s : splits) {
            UUID payeeId = s.newPayeeName() != null ? payees.findOrCreate(budgetId, s.newPayeeName()).id() : s.payeeId();
            jdbc.sql("""
                            insert into txn_split (id, transaction_id, amount, category_id, payee_id, memo, sort_order)
                            values (?, ?, ?, ?, ?, ?, ?)""")
                    .params(UUID.randomUUID(), transactionId, s.amount(), account.onBudget() ? s.categoryId() : null,
                            payeeId, s.memo(), order++)
                    .update();
        }
    }

    private void createCounterpart(UUID budgetId, UUID txnId, AccountDto account, AccountDto transfer, LocalDate date,
                                   long amount, String memo, boolean approved, UUID category) {
        UUID cpId = UUID.randomUUID();
        jdbc.sql("""
                        insert into txn (id, budget_id, account_id, date, amount, payee_id, category_id, memo, cleared,
                                         approved, transfer_account_id, transfer_transaction_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 'UNCLEARED', ?, ?, ?)""")
                .params(cpId, budgetId, transfer.id(), date, -amount, payees.transferPayee(account.id()).id(), category,
                        memo, approved, account.id(), txnId)
                .update();
        jdbc.sql("update txn set transfer_transaction_id = ? where id = ?").params(cpId, txnId).update();
    }

    /** Category stored on the row itself, per the placement rules in the class comment. */
    private static UUID rowCategory(AccountDto account, AccountDto transfer, List<SplitDraft> splits, UUID category) {
        if (!splits.isEmpty() || !account.onBudget()) {
            return null;
        }
        if (transfer != null) {
            return transfer.onBudget() ? null : category;
        }
        return category;
    }

    /** Category stored on the other side of a transfer. */
    private static UUID counterpartCategory(AccountDto account, AccountDto transfer, UUID category) {
        return transfer.onBudget() && !account.onBudget() ? category : null;
    }

    /** For callers that only have a category id (e.g. imports): validates it belongs to the budget. */
    CategoryDto requireCategory(UUID budgetId, UUID categoryId) {
        return categories.require(budgetId, categoryId);
    }
}
