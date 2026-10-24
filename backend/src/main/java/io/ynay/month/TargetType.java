package io.ynay.month;

/** Kinds of category targets (goals). See "Targets" in docs/ARCHITECTURE.md. */
public enum TargetType {
    /** Assign X every month. */
    MONTHLY_SAVINGS,
    /** Have X available for spending each month. */
    MONTHLY_SPENDING,
    /** Save up to a balance of X (no deadline). */
    TARGET_BALANCE,
    /** Have a balance of X by the month of the target date. */
    TARGET_BALANCE_BY_DATE
}
