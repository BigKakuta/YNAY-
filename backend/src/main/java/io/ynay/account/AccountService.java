package io.ynay.account;

import io.ynay.action.ActionException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Account lookups with computed balances. */
@Service
public class AccountService {

    private static final String SELECT = """
            select a.id, a.budget_id, a.name, a.type, a.on_budget, a.closed, a.note, a.sort_order,
                   coalesce(sum(t.amount), 0) as balance,
                   coalesce(sum(t.amount) filter (where t.cleared <> 'UNCLEARED'), 0) as cleared_balance,
                   coalesce(sum(t.amount) filter (where t.cleared = 'UNCLEARED'), 0) as uncleared_balance
            from account a left join txn t on t.account_id = a.id
            """;

    private final JdbcClient jdbc;

    public AccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AccountDto> list(UUID budgetId, boolean includeClosed) {
        return jdbc.sql(SELECT + " where a.budget_id = ? and (? or not a.closed)"
                        + " group by a.id order by a.closed, a.sort_order, a.name")
                .params(budgetId, includeClosed)
                .query(AccountService::map)
                .list();
    }

    public Optional<AccountDto> find(UUID budgetId, UUID accountId) {
        if (accountId == null) {
            return Optional.empty();
        }
        return jdbc.sql(SELECT + " where a.budget_id = ? and a.id = ? group by a.id")
                .params(budgetId, accountId)
                .query(AccountService::map)
                .optional();
    }

    /** Loads an account of the budget or fails with NOT_FOUND (use for caller-supplied ids). */
    public AccountDto require(UUID budgetId, UUID accountId) {
        if (accountId == null) {
            throw ActionException.invalid("accountId is required");
        }
        return find(budgetId, accountId).orElseThrow(() -> ActionException.notFound("Account " + accountId));
    }

    boolean nameTaken(UUID budgetId, String name, UUID except) {
        return jdbc.sql("select count(*) from account where budget_id = ? and lower(name) = lower(?) and id <> ?")
                .params(budgetId, name, except == null ? new UUID(0, 0) : except)
                .query(Long.class)
                .single() > 0;
    }

    int nextSortOrder(UUID budgetId) {
        return jdbc.sql("select coalesce(max(sort_order) + 1, 0) from account where budget_id = ?")
                .param(budgetId)
                .query(Integer.class)
                .single();
    }

    static AccountDto map(ResultSet rs, int row) throws SQLException {
        return new AccountDto(
                rs.getObject("id", UUID.class),
                rs.getObject("budget_id", UUID.class),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                rs.getBoolean("on_budget"),
                rs.getBoolean("closed"),
                rs.getString("note"),
                rs.getInt("sort_order"),
                rs.getLong("balance"),
                rs.getLong("cleared_balance"),
                rs.getLong("uncleared_balance"));
    }
}
