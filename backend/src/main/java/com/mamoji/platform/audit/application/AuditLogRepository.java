package com.mamoji.platform.audit.application;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.audit.domain.AuditLogSearchCriteria;
import java.util.List;

/** Persistence port exposing append and read operations, but no audit mutation or deletion. */
public interface AuditLogRepository {
    AuditLog append(AuditEvent event);

    PagedResponse<AuditLog> findPage(AuditLogSearchCriteria criteria, PageRequest pageRequest);

    List<AuditLog> findByEntity(long companyId, String entityType, long entityId);

    boolean existsByEntityType(String entityType);
}
