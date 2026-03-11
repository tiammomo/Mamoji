package com.mamoji.common.status;

/**
 * Integer constants for budget lifecycle states.
 */
public final class BudgetStatus {

    public static final int INACTIVE = 0;
    public static final int ACTIVE = 1;
    public static final int COMPLETED = 2;
    public static final int OVERRUN = 3;

    /**
     * Utility constants class; do not instantiate.
     */
    private BudgetStatus() {
    }
}
