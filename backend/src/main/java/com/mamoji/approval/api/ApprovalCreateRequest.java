package com.mamoji.approval.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Public command contract for submitting an approval request. */
public record ApprovalCreateRequest(
    @Positive Long companyId,
    @Pattern(regexp = "reimbursement|payment|budget_adjustment|onboarding|offboarding|payroll_close|other")
    String requestType,
    @Pattern(regexp = "receipt_voucher|transaction|budget|employee|payroll_run|other")
    String entityType,
    @Positive Long entityId,
    @Size(max = 160) String title,
    @DecimalMin("0.00") @Digits(integer = 14, fraction = 2) BigDecimal amount,
    @Positive Long assigneeUserId,
    @Size(max = 1000) String description,
    @Size(max = 500) String comment,
    @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]*") String idempotencyKey
) {
    public ApprovalCreateRequest {
        requestType = nullIfBlank(requestType);
        entityType = nullIfBlank(entityType);
        title = nullIfBlank(title);
        idempotencyKey = nullIfBlank(idempotencyKey);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
