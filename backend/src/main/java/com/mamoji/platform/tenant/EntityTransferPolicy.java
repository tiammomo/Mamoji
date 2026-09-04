package com.mamoji.platform.tenant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** Stable command policy for append-only transfers between tenant subjects. */
public final class EntityTransferPolicy {
    private static final BigDecimal MAX_AMOUNT_EXCLUSIVE = new BigDecimal("10000000000000000");
    private static final Set<String> TYPES = Set.of(
        "inter_entity_transfer",
        "shareholder_advance",
        "advance_repayment",
        "expense_reimbursement",
        "reimbursement_payment"
    );

    private EntityTransferPolicy() {
    }

    public static void normalizeAndValidate(EntityTransfer transfer) {
        if (transfer.fromEntityId <= 0 || transfer.toEntityId <= 0) {
            throw new IllegalArgumentException("transfer subjects must be positive");
        }
        if (transfer.fromEntityId == transfer.toEntityId) {
            throw new IllegalArgumentException("cannot transfer within the same subject");
        }
        if (transfer.operatorUserId <= 0) {
            throw new IllegalArgumentException("operatorUserId must be positive");
        }
        transfer.transferType = required(transfer.transferType, "transferType", 64).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(transfer.transferType)) {
            throw new IllegalArgumentException("transferType is unsupported");
        }
        if (transfer.amount == null || transfer.amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal normalizedAmount = transfer.amount.stripTrailingZeros();
        if (normalizedAmount.scale() > 4 || normalizedAmount.compareTo(MAX_AMOUNT_EXCLUSIVE) >= 0) {
            throw new IllegalArgumentException("amount exceeds the supported precision");
        }
        transfer.amount = new BigDecimal(normalizedAmount.toPlainString());
        transfer.currency = required(transfer.currency, "currency", 3).toUpperCase(Locale.ROOT);
        if (!transfer.currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        transfer.transferDate = validDate(transfer.transferDate);
        transfer.note = optional(transfer.note, 1000);
        transfer.status = required(transfer.status, "status", 32).toLowerCase(Locale.ROOT);
        if (!"recorded".equals(transfer.status)) {
            throw new IllegalArgumentException("status must be recorded");
        }
    }

    private static String validDate(String value) {
        try {
            return LocalDate.parse(required(value, "transferDate", 10)).toString();
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("transferDate must be an ISO date", error);
        }
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("note is too long");
        return normalized;
    }
}
