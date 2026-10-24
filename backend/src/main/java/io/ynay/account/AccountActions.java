package io.ynay.account;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import io.ynay.category.CategoryService;
import io.ynay.payee.PayeeService;
import io.ynay.transaction.TransactionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Account actions. */
@Component
public class AccountActions {

    private final BudgetAccess access;
    private final AccountService accounts;
    private final CategoryService categories;
    private final PayeeService payees;
    private final TransactionService txns;
    private final JdbcClient jdbc;

    public AccountActions(BudgetAccess access, AccountService accounts, CategoryService categories,
                          PayeeService payees, TransactionService txns, JdbcClient jdbc) {
        this.access = access;
        this.accounts = accounts;
        this.categories = categories;
        this.payees = payees;
        this.txns = txns;
        this.jdbc = jdbc;
    }

    public record ListAccountsInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam(value = "Also return closed accounts (default false)", required = false)
            Boolean includeClosed) {}

    @McpAction(name = "list_accounts", readOnly = true, description = """
            List the budget's accounts with their balances. Balances are integer milliunits (1.00 = 1000): \
            balance = all transactions, clearedBalance = CLEARED + RECONCILED, unclearedBalance = UNCLEARED. \
            Credit card and loan balances are negative when money is owed. onBudget=false marks tracking \
            accounts, which don't affect the budget.""")
    public List<AccountDto> listAccounts(ListAccountsInput in) {
        access.requireMember(in.budgetId());
        return accounts.list(in.budgetId(), Boolean.TRUE.equals(in.includeClosed()));
    }

    public record CreateAccountInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account name, unique within the budget, e.g. \"Checking\"") String name,
            @ActionParam("Account type") AccountType type,
            @ActionParam(value = "Budget account (true) or tracking account (false). Defaults to true for "
                    + "CHECKING, SAVINGS, CASH, CREDIT_CARD, LINE_OF_CREDIT and false otherwise", required = false)
            Boolean onBudget,
            @ActionParam(value = "Current balance in milliunits (default 0); negative for credit card debt",
                    required = false) Long startingBalance,
            @ActionParam(value = "Date of the starting balance (default today)", required = false)
            LocalDate startingDate,
            @ActionParam(value = "Free-text note", required = false) String note) {}

    @McpAction(name = "create_account", description = """
            Create an account. A non-zero startingBalance (milliunits, negative = owed, e.g. -150000 for a \
            150.00 credit card balance) is recorded as a cleared "Starting Balance" transaction on startingDate; \
            on a non-credit budget account it counts as income (Ready to Assign), on credit cards and tracking \
            accounts it has no budget effect. Every account gets a transfer payee "Transfer : <name>". A budget \
            CREDIT_CARD or LINE_OF_CREDIT account also gets its payment category in the Credit Card Payments \
            group.""")
    public AccountDto createAccount(CreateAccountInput in) {
        access.requireMember(in.budgetId());
        String name = Inputs.requireName(in.name(), "name");
        if (in.type() == null) {
            throw ActionException.invalid("type is required");
        }
        if (accounts.nameTaken(in.budgetId(), name, null)) {
            throw ActionException.conflict("An account named '" + name + "' already exists");
        }
        if (payees.findByName(in.budgetId(), PayeeService.transferPayeeName(name)).isPresent()) {
            throw ActionException.conflict("A payee named '" + PayeeService.transferPayeeName(name) + "' already exists");
        }
        boolean onBudget = in.onBudget() != null ? in.onBudget() : in.type().onBudgetByDefault();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into account (id, budget_id, name, type, on_budget, note, sort_order)
                        values (?, ?, ?, ?, ?, ?, ?)""")
                .params(id, in.budgetId(), name, in.type().name(), onBudget, Inputs.blankToNull(in.note()),
                        accounts.nextSortOrder(in.budgetId()))
                .update();
        payees.createTransferPayee(in.budgetId(), id, name);
        AccountDto account = accounts.require(in.budgetId(), id);
        if (account.isOnBudgetCredit()) {
            categories.ensureCreditCardPaymentCategory(in.budgetId(), id, name);
        }
        long startingBalance = in.startingBalance() == null ? 0 : in.startingBalance();
        if (startingBalance != 0) {
            txns.createStartingBalance(in.budgetId(), account, startingBalance,
                    in.startingDate() == null ? LocalDate.now() : in.startingDate());
        }
        return accounts.require(in.budgetId(), id);
    }

    public record UpdateAccountInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account id") UUID accountId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "New note (\"\" clears it)", required = false) String note,
            @ActionParam(value = "Close (true) or reopen (false) the account", required = false) Boolean closed) {}

    @McpAction(name = "update_account", description = """
            Rename, annotate, close or reopen an account. Renaming also renames its transfer payee and credit \
            card payment category. An account can only be closed when its balance is 0; closed accounts accept \
            no new transactions and their credit card payment category is hidden.""")
    public AccountDto updateAccount(UpdateAccountInput in) {
        access.requireMember(in.budgetId());
        AccountDto account = accounts.require(in.budgetId(), in.accountId());
        if (in.name() != null) {
            String name = Inputs.requireName(in.name(), "name");
            if (!name.equals(account.name())) {
                if (accounts.nameTaken(in.budgetId(), name, account.id())) {
                    throw ActionException.conflict("An account named '" + name + "' already exists");
                }
                payees.renameTransferPayee(in.budgetId(), account.id(), name);
                jdbc.sql("update account set name = ? where id = ?").params(name, account.id()).update();
                categories.renameLinkedCategory(account.id(), name);
            }
        }
        if (in.note() != null) {
            jdbc.sql("update account set note = ? where id = ?")
                    .params(Inputs.blankToNull(in.note()), account.id())
                    .update();
        }
        if (in.closed() != null && in.closed() != account.closed()) {
            if (in.closed() && account.balance() != 0) {
                throw ActionException.invalid("Account '" + account.name() + "' has a balance of "
                        + Inputs.formatAmount(account.balance()) + " (" + account.balance()
                        + " milliunits); bring it to 0 (e.g. with a transfer) before closing");
            }
            jdbc.sql("update account set closed = ? where id = ?").params(in.closed(), account.id()).update();
            categories.setLinkedCategoryHidden(account.id(), in.closed());
        }
        return accounts.require(in.budgetId(), account.id());
    }

    public record DeleteAccountInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Account id") UUID accountId) {}

    @McpAction(name = "delete_account", destructive = true, description = """
            Permanently delete an account with all of its transactions, its transfer payee and its credit card \
            payment category. Transfers with other accounts are kept on the other side as ordinary transactions \
            (payee = the deleted account's name). Prefer closing (update_account closed=true) to keep history.""")
    public Map<String, Object> deleteAccount(DeleteAccountInput in) {
        access.requireMember(in.budgetId());
        AccountDto account = accounts.require(in.budgetId(), in.accountId());
        txns.detachTransfersTo(in.budgetId(), account);
        jdbc.sql("delete from txn where account_id = ?").param(account.id()).update();
        jdbc.sql("delete from account where budget_id = ? and id = ?").params(in.budgetId(), account.id()).update();
        return Map.of("deleted", true);
    }
}
