package io.ynay.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** {@code Account} with balances (milliunits) computed from its transactions. */
public record AccountDto(
        UUID id,
        UUID budgetId,
        String name,
        AccountType type,
        boolean onBudget,
        boolean closed,
        String note,
        int sortOrder,
        long balance,
        long clearedBalance,
        long unclearedBalance) {

    /** On-budget credit card / line of credit: has a credit card payment category. */
    @JsonIgnore
    public boolean isOnBudgetCredit() {
        return onBudget && type.isCredit();
    }
}
