package io.ynay.transaction;

import io.ynay.action.ActionParam;
import java.time.LocalDate;
import java.util.UUID;

/** {@code ImportLine}: one bank-statement line for {@code import_transactions}. */
public record ImportLine(
        @ActionParam("Booking date, YYYY-MM-DD") LocalDate date,
        @ActionParam(value = "Local time of day of the payment, HH:MM, when the statement has it", required = false)
        String time,
        @ActionParam("Amount in milliunits: -12340 = 12.34 spent (outflow), 250000 = 250.00 received (inflow)")
        Long amount,
        @ActionParam(value = "Payee as it should appear (cleaned up merchant name). Created if it doesn't exist; "
                + "an existing payee is matched case-insensitively", required = false) String payeeName,
        @ActionParam(value = "Memo / original statement text", required = false) String memo,
        @ActionParam(value = "Category id. When omitted, the payee's most recently used category is applied",
                required = false) UUID categoryId,
        @ActionParam(value = "Stable unique id of this line from the bank (e.g. FITID). Omit to let the server "
                + "generate YNAY:<amount>:<date>:<occurrence>", required = false) String importId,
        @ActionParam(value = "Cleared state (default CLEARED, since the bank has seen it)", required = false)
        Cleared cleared) {
}
