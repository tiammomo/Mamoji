package com.mamoji.platform.audit.domain;

/** Validated event accepted by the append-only audit persistence boundary. */
public record AuditEvent(
    long companyId,
    String entityType,
    long entityId,
    String action,
    String summary,
    long actorUserId,
    String actorName,
    String createdAt
) {
    public AuditEvent {
        if (companyId < 0 || entityId < 0 || actorUserId < 0) {
            throw new IllegalArgumentException("Audit identifiers must not be negative");
        }
        entityType = defaultIfBlank(entityType, "unknown");
        action = defaultIfBlank(action, "update");
        summary = defaultIfBlank(summary, "记录更新");
        actorName = defaultIfBlank(actorName, "系统用户");
        if (createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("Audit creation time is required");
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
