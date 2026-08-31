package com.mamoji.platform.audit.domain;

import java.util.Locale;

/** Normalized filters used by the audit persistence query. */
public record AuditLogSearchCriteria(
    Long companyId,
    String entityType,
    Long entityId,
    String action,
    Long actorUserId,
    String keyword
) {
    public AuditLogSearchCriteria {
        entityType = normalize(entityType);
        action = normalize(action);
        keyword = normalize(keyword).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
