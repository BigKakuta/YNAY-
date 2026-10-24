package io.ynay.payee;

import io.ynay.action.ActionException;
import io.ynay.budget.Inputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Payee persistence, name resolution and transfer payees. */
@Service
public class PayeeService {

    public static final String TRANSFER_PREFIX = "Transfer : ";

    private final JdbcClient jdbc;

    public PayeeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String transferPayeeName(String accountName) {
        return TRANSFER_PREFIX + accountName;
    }

    public List<PayeeDto> list(UUID budgetId) {
        return jdbc.sql("select id, name, transfer_account_id from payee where budget_id = ? order by lower(name), name")
                .param(budgetId)
                .query(PayeeService::map)
                .list();
    }

    public Optional<PayeeDto> find(UUID budgetId, UUID payeeId) {
        if (payeeId == null) {
            return Optional.empty();
        }
        return jdbc.sql("select id, name, transfer_account_id from payee where budget_id = ? and id = ?")
                .params(budgetId, payeeId)
                .query(PayeeService::map)
                .optional();
    }

    /** Loads a payee of the budget or fails with NOT_FOUND (use for caller-supplied ids). */
    public PayeeDto require(UUID budgetId, UUID payeeId) {
        if (payeeId == null) {
            throw ActionException.invalid("payeeId is required");
        }
        return find(budgetId, payeeId).orElseThrow(() -> ActionException.notFound("Payee " + payeeId));
    }

    /** Case-insensitive lookup by name, preferring an exact-case match. */
    public Optional<PayeeDto> findByName(UUID budgetId, String name) {
        return jdbc.sql("""
                        select id, name, transfer_account_id from payee
                        where budget_id = ? and lower(name) = lower(?)
                        order by (name = ?) desc, name limit 1""")
                .params(budgetId, name, name)
                .query(PayeeService::map)
                .optional();
    }

    /**
     * Resolves a payee by name, creating it when it doesn't exist. May return a transfer payee when
     * the name matches one ("Transfer : Savings").
     */
    public PayeeDto findOrCreate(UUID budgetId, String rawName) {
        String name = Inputs.requireName(rawName, "payeeName");
        Optional<PayeeDto> existing = findByName(budgetId, name);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (name.regionMatches(true, 0, TRANSFER_PREFIX, 0, TRANSFER_PREFIX.length())) {
            throw ActionException.invalid("'" + name + "' looks like a transfer payee but no account named '"
                    + name.substring(TRANSFER_PREFIX.length()) + "' exists. Use transferAccountId for transfers.");
        }
        return insert(budgetId, name, null);
    }

    public PayeeDto insert(UUID budgetId, String name, UUID transferAccountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into payee (id, budget_id, name, transfer_account_id) values (?, ?, ?, ?)")
                .params(id, budgetId, name, transferAccountId)
                .update();
        return new PayeeDto(id, name, transferAccountId);
    }

    /** Creates the "Transfer : <account>" payee of a new account. */
    public PayeeDto createTransferPayee(UUID budgetId, UUID accountId, String accountName) {
        String name = transferPayeeName(accountName);
        if (findByName(budgetId, name).isPresent()) {
            throw ActionException.conflict("A payee named '" + name + "' already exists");
        }
        return insert(budgetId, name, accountId);
    }

    public void renameTransferPayee(UUID budgetId, UUID accountId, String accountName) {
        String name = transferPayeeName(accountName);
        Optional<PayeeDto> clash = findByName(budgetId, name);
        if (clash.isPresent() && !accountId.equals(clash.get().transferAccountId())) {
            throw ActionException.conflict("A payee named '" + name + "' already exists");
        }
        jdbc.sql("update payee set name = ? where transfer_account_id = ?").params(name, accountId).update();
    }

    /** The transfer payee of an account. */
    public PayeeDto transferPayee(UUID accountId) {
        return jdbc.sql("select id, name, transfer_account_id from payee where transfer_account_id = ?")
                .param(accountId)
                .query(PayeeService::map)
                .single();
    }

    static PayeeDto map(ResultSet rs, int row) throws SQLException {
        return new PayeeDto(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("transfer_account_id", UUID.class));
    }
}
