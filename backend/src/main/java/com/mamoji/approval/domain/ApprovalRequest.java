package com.mamoji.approval.domain;

import java.math.BigDecimal;

/** Stored approval request exposed by the approval API. */
public record ApprovalRequest(
    long id,
    long version,
    String idempotencyKey,
    long companyId,
    String requestType,
    String entityType,
    Long entityId,
    String title,
    BigDecimal amount,
    long applicantUserId,
    Long assigneeUserId,
    String status,
    String currentStep,
    String description,
    String decidedAt,
    String createdAt,
    String updatedAt
) {
}
