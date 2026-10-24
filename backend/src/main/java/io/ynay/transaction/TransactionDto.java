package io.ynay.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code Transaction}. Amount in milliunits, negative = outflow. */
public record TransactionDto(
        UUID id,
        UUID budgetId,
        UUID accountId,
        String accountName,
        LocalDate date,
        long amount,
        UUID payeeId,
        String payeeName,
        UUID categoryId,
        String categoryName,
        String memo,
        Cleared cleared,
        boolean approved,
        String flagColor,
        UUID transferAccountId,
        UUID transferTransactionId,
        String importId,
        List<SplitDto> splits) {

    TransactionDto withSplits(List<SplitDto> splits) {
        return new TransactionDto(id, budgetId, accountId, accountName, date, amount, payeeId, payeeName, categoryId,
                categoryName, memo, cleared, approved, flagColor, transferAccountId, transferTransactionId, importId,
                splits);
    }
}
