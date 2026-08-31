package com.mamoji.budget.domain;

import java.math.BigDecimal;

/** Immutable capacity snapshot evaluated while the owning budget row is locked. */
public record BudgetCapacity(BigDecimal limit, BigDecimal committed, BigDecimal reserved) {
    public BudgetCapacity {
        limit = money(limit);
        committed = money(committed);
        reserved = money(reserved);
        if (limit.signum() < 0 || committed.signum() < 0 || reserved.signum() < 0) {
            throw new IllegalArgumentException("Budget capacity values must not be negative");
        }
    }

    public BigDecimal available() {
        return limit.subtract(committed).subtract(reserved).max(BigDecimal.ZERO);
    }

    public BudgetCapacity reserve(BigDecimal amount) {
        BigDecimal requested = money(amount);
        if (requested.signum() <= 0) {
            throw new IllegalArgumentException("Reservation amount must be positive");
        }
        if (requested.compareTo(available()) > 0) {
            throw new BudgetCapacityExceededException(requested, available());
        }
        return new BudgetCapacity(limit, committed, reserved.add(requested));
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static final class BudgetCapacityExceededException extends IllegalStateException {
        private final BigDecimal requested;
        private final BigDecimal available;

        public BudgetCapacityExceededException(BigDecimal requested, BigDecimal available) {
            super("Budget capacity exceeded: requested " + requested + ", available " + available);
            this.requested = requested;
            this.available = available;
        }

        public BigDecimal requested() {
            return requested;
        }

        public BigDecimal available() {
            return available;
        }
    }
}
