package io.ynay.budget;

import io.ynay.action.ActionException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small input-normalization helpers shared by the ledger actions. */
public final class Inputs {

    public static final int MAX_NAME_LENGTH = 200;

    private Inputs() {
    }

    /** Trims and validates a required, non-blank name. */
    public static String requireName(String value, String field) {
        String trimmed = value == null ? null : value.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw ActionException.invalid(field + " must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw ActionException.invalid(field + " is too long (max " + MAX_NAME_LENGTH + " characters)");
        }
        return trimmed;
    }

    /** Trims; blank becomes {@code null}. */
    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Formats milliunits as a plain decimal amount for messages, e.g. {@code -1234500} → {@code -1234.50}. */
    public static String formatAmount(long milliunits) {
        return BigDecimal.valueOf(milliunits, 3).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
