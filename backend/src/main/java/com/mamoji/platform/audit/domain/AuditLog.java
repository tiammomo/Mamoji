package com.mamoji.platform.audit.domain;

/** Immutable audit record returned by the audit trail. */
public record AuditLog(
    long id,
    long companyId,
    String entityType,
    long entityId,
    String action,
    String summary,
    long actorUserId,
    String actorName,
    String createdAt
) {
}
