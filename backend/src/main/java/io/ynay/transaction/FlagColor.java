package io.ynay.transaction;

/** Transaction flag colors. {@link #NONE} (input only) removes a flag; it is never stored. */
public enum FlagColor {
    RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, NONE;

    /** Stored value: the color name, or {@code null} for {@link #NONE}. */
    String stored() {
        return this == NONE ? null : name();
    }
}
