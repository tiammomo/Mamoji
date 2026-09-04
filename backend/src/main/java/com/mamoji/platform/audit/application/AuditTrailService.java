package com.mamoji.platform.audit.application;

import com.mamoji.platform.audit.domain.AuditEvent;
import com.mamoji.platform.audit.domain.AuditLog;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** Application boundary for append-only audit recording and entity history lookup. */
@Service
public class AuditTrailService {
    private final AuditLogRepository repository;

    public AuditTrailService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public AuditLog record(
        long companyId,
        String entityType,
        long entityId,
        String action,
        String summary,
        long actorUserId,
        String actorName
    ) {
        return repository.append(new AuditEvent(
            companyId,
            entityType,
            entityId,
            action,
            summary,
            actorUserId,
            actorName,
            OffsetDateTime.now().toString()
        ));
    }

    public List<AuditLog> findByEntity(long companyId, String entityType, long entityId) {
        return repository.findByEntity(companyId, entityType, entityId);
    }

    public boolean existsByEntityType(String entityType) {
        return repository.existsByEntityType(entityType);
    }
}
