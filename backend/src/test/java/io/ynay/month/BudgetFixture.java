package io.ynay.month;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds budget data with plain SQL inserts, mirroring what the ledger actions create
 * (budget + member, INTERNAL group with Ready to Assign, Credit Card Payments group,
 * payment categories for on-budget credit accounts, transfer payees, transfers as linked pairs).
 */
public final class BudgetFixture {

    public record Line(long amount, UUID categoryId) {
    }

    private final JdbcClient jdbc;
    public final UUID budgetId = UUID.randomUUID();
    public final UUID internalGroupId = UUID.randomUUID();
    public final UUID rtaId = UUID.randomUUID();
    public final UUID ccGroupId = UUID.randomUUID();
    private int sort = 10;

    private BudgetFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static BudgetFixture create(JdbcClient jdbc, UUID ownerId) {
        BudgetFixture f = new BudgetFixture(jdbc);
        jdbc.sql("insert into budget (id, name, currency_code) values (?, 'Test budget', 'EUR')")
                .param(f.budgetId).update();
        jdbc.sql("insert into budget_member (budget_id, user_id, role) values (?, ?, 'OWNER')")
                .params(f.budgetId, ownerId).update();
        jdbc.sql("""
                insert into category_group (id, budget_id, name, hidden, sort_order, system_kind)
                values (?, ?, 'Internal', true, 0, 'INTERNAL')""")
                .params(f.internalGroupId, f.budgetId).update();
        jdbc.sql("""
                insert into category (id, budget_id, group_id, name, sort_order, system_kind)
                values (?, ?, ?, 'Inflow: Ready to Assign', 0, 'READY_TO_ASSIGN')""")
                .params(f.rtaId, f.budgetId, f.internalGroupId).update();
        jdbc.sql("""
                insert into category_group (id, budget_id, name, sort_order, system_kind)
                values (?, ?, 'Credit Card Payments', 1, 'CREDIT_CARD_PAYMENTS')""")
                .params(f.ccGroupId, f.budgetId).update();
        return f;
    }

    public UUID group(String name) {
        return group(name, false);
    }

    public UUID group(String name, boolean hidden) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into category_group (id, budget_id, name, hidden, sort_order) values (?, ?, ?, ?, ?)")
                .params(id, budgetId, name, hidden, sort++).update();
        return id;
    }

    public UUID category(UUID groupId, String name) {
        return category(groupId, name, false);
    }

    public UUID category(UUID groupId, String name, boolean hidden) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into category (id, budget_id, group_id, name, hidden, sort_order)
                values (?, ?, ?, ?, ?, ?)""")
                .params(id, budgetId, groupId, name, hidden, sort++).update();
        return id;
    }

    public void target(UUID categoryId, TargetType type, long amount, LocalDate date) {
        jdbc.sql("update category set target_type = ?, target_amount = ?, target_date = ? where id = ?")
                .params(type.name(), amount, date, categoryId).update();
    }

    /** Creates an account (+ transfer payee, + payment category for on-budget credit accounts). */
    public UUID account(String name, String type, boolean onBudget) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into account (id, budget_id, name, type, on_budget, sort_order) values (?, ?, ?, ?, ?, ?)")
                .params(id, budgetId, name, type, onBudget, sort++).update();
        jdbc.sql("insert into payee (id, budget_id, name, transfer_account_id) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), budgetId, "Transfer : " + name, id).update();
        if (onBudget && (type.equals("CREDIT_CARD") || type.equals("LINE_OF_CREDIT"))) {
            jdbc.sql("""
                    insert into category (id, budget_id, group_id, name, sort_order, system_kind, linked_account_id)
                    values (?, ?, ?, ?, ?, 'CREDIT_CARD_PAYMENT', ?)""")
                    .params(UUID.randomUUID(), budgetId, ccGroupId, name, sort++, id).update();
        }
        return id;
    }

    public UUID paymentCategory(UUID accountId) {
        return jdbc.sql("select id from category where linked_account_id = ?")
                .param(accountId).query(UUID.class).single();
    }

    public UUID payee(String name) {
        return jdbc.sql("""
                insert into payee (id, budget_id, name) values (?, ?, ?)
                on conflict (budget_id, name) do update set name = excluded.name
                returning id""")
                .params(UUID.randomUUID(), budgetId, name).query(UUID.class).single();
    }

    public UUID txn(UUID accountId, String date, long amount, UUID categoryId) {
        return txn(accountId, date, amount, categoryId, null);
    }

    public UUID txn(UUID accountId, String date, long amount, UUID categoryId, String payeeName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into txn (id, budget_id, account_id, date, amount, payee_id, category_id)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, budgetId, accountId, LocalDate.parse(date), amount,
                        payeeName == null ? null : payee(payeeName), categoryId)
                .update();
        return id;
    }

    /** Opening balance as the ledger creates it: RTA on non-credit on-budget accounts, else uncategorized. */
    public UUID startingBalance(UUID accountId, String date, long amount) {
        var row = jdbc.sql("select type, on_budget from account where id = ?").param(accountId)
                .query((rs, n) -> new Object[] {rs.getString("type"), rs.getBoolean("on_budget")}).single();
        String type = (String) row[0];
        boolean onBudget = (Boolean) row[1];
        boolean credit = type.equals("CREDIT_CARD") || type.equals("LINE_OF_CREDIT");
        return txn(accountId, date, amount, onBudget && !credit ? rtaId : null, "Starting Balance");
    }

    public UUID split(UUID accountId, String date, Line... lines) {
        UUID id = UUID.randomUUID();
        long total = 0;
        for (Line l : lines) {
            total += l.amount();
        }
        jdbc.sql("""
                insert into txn (id, budget_id, account_id, date, amount, is_split)
                values (?, ?, ?, ?, ?, true)""")
                .params(id, budgetId, accountId, LocalDate.parse(date), total).update();
        int i = 0;
        for (Line l : lines) {
            jdbc.sql("""
                    insert into txn_split (id, transaction_id, amount, category_id, sort_order)
                    values (?, ?, ?, ?, ?)""")
                    .params(UUID.randomUUID(), id, l.amount(), l.categoryId(), i++).update();
        }
        return id;
    }

    /**
     * Transfer of {@code amount} (> 0) from {@code fromAccount} to {@code toAccount}: two linked rows,
     * -amount on the source side (with {@code fromCategory}, if any) and +amount on the destination.
     */
    public void transfer(UUID fromAccount, UUID toAccount, String date, long amount, UUID fromCategory) {
        UUID out = UUID.randomUUID();
        UUID in = UUID.randomUUID();
        UUID payeeOfOut = transferPayee(toAccount);
        UUID payeeOfIn = transferPayee(fromAccount);
        String sql = """
                insert into txn (id, budget_id, account_id, date, amount, payee_id, category_id,
                                 transfer_account_id, transfer_transaction_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        // insert unlinked first, then link (each statement commits on its own in tests)
        jdbc.sql(sql).params(out, budgetId, fromAccount, LocalDate.parse(date), -amount, payeeOfOut, fromCategory,
                toAccount, null).update();
        jdbc.sql(sql).params(in, budgetId, toAccount, LocalDate.parse(date), amount, payeeOfIn, null,
                fromAccount, out).update();
        jdbc.sql("update txn set transfer_transaction_id = ? where id = ?").params(in, out).update();
    }

    private UUID transferPayee(UUID accountId) {
        return jdbc.sql("select id from payee where transfer_account_id = ?").param(accountId)
                .query(UUID.class).single();
    }

    public void assign(UUID categoryId, String month, long amount) {
        jdbc.sql("""
                insert into month_assignment (budget_id, category_id, month, assigned) values (?, ?, ?, ?)
                on conflict (category_id, month) do update set assigned = excluded.assigned""")
                .params(budgetId, categoryId, YearMonth.parse(month).atDay(1), amount).update();
    }
}
