package com.mamoji.budget.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A budget capacity claim with explicit reserved, confirmed, and released states. */
public record BudgetReservation(
    long id,
    long version,
    long companyId,
    long budgetId,
    Long transactionId,
    String referenceKey,
    BigDecimal amount,
    Status status,
    long createdByUserId,
    OffsetDateTime confirmedAt,
    OffsetDateTime releasedAt,
    String releaseReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public BudgetReservation confirm(long confirmedTransactionId, OffsetDateTime now) {
        requireReserved("confirm");
        if (confirmedTransactionId <= 0) {
            throw new IllegalArgumentException("Confirmed transaction id must be positive");
        }
        return new BudgetReservation(
            id, version, companyId, budgetId, confirmedTransactionId, referenceKey, amount, Status.CONFIRMED,
            createdByUserId, now, null, null, createdAt, now
        );
    }

    public BudgetReservation release(String reason, OffsetDateTime now) {
        if (status == Status.RELEASED) {
            throw new IllegalStateException("Cannot release a released budget reservation");
        }
        String normalizedReason = reason == null ? null : reason.trim();
        return new BudgetReservation(
            id, version, companyId, budgetId, null, referenceKey, amount, Status.RELEASED,
            createdByUserId, null, now, normalizedReason == null || normalizedReason.isBlank() ? null : normalizedReason,
            createdAt, now
        );
    }

    private void requireReserved(String action) {
        if (status != Status.RESERVED) {
            throw new IllegalStateException("Cannot " + action + " a " + status.value + " budget reservation");
        }
    }

    public enum Status {
        RESERVED("reserved"),
        CONFIRMED("confirmed"),
        RELEASED("released");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromStored(String value) {
            for (Status status : values()) {
                if (status.value.equals(value)) return status;
            }
            throw new IllegalArgumentException("Unsupported budget reservation status: " + value);
        }
    }
}
