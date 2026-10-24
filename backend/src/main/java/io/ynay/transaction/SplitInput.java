package io.ynay.transaction;

import io.ynay.action.ActionParam;
import java.util.UUID;

/** {@code SplitInput}: one line of a split transaction. */
public record SplitInput(
        @ActionParam("Line amount in milliunits (negative = outflow). All lines must sum to the transaction amount")
        Long amount,
        @ActionParam(value = "Category of this line (from list_categories)", required = false) UUID categoryId,
        @ActionParam(value = "Payee of this line, if different from the transaction's; created if new",
                required = false) String payeeName,
        @ActionParam(value = "Memo of this line", required = false) String memo) {
}
