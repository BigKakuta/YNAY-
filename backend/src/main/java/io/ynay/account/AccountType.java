package io.ynay.account;

/** Account types. The first five are budget (on-budget) accounts by default, the rest tracking accounts. */
public enum AccountType {
    CHECKING(true, false),
    SAVINGS(true, false),
    CASH(true, false),
    CREDIT_CARD(true, true),
    LINE_OF_CREDIT(true, true),
    ASSET(false, false),
    LIABILITY(false, false),
    MORTGAGE(false, false),
    LOAN(false, false),
    OTHER(false, false);

    private final boolean onBudgetByDefault;
    private final boolean credit;

    AccountType(boolean onBudgetByDefault, boolean credit) {
        this.onBudgetByDefault = onBudgetByDefault;
        this.credit = credit;
    }

    public boolean onBudgetByDefault() {
        return onBudgetByDefault;
    }

    /** Credit accounts (credit card, line of credit) get a payment category when on-budget. */
    public boolean isCredit() {
        return credit;
    }
}
