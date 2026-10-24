package io.ynay.transaction;

/** Cleared state of a transaction. */
public enum Cleared {
    /** Entered but not yet seen on the bank statement. */
    UNCLEARED,
    /** Confirmed by the bank (imported or manually cleared). */
    CLEARED,
    /** Locked in by a reconciliation. */
    RECONCILED
}
