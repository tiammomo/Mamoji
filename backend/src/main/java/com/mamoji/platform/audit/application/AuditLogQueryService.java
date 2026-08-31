package com.mamoji.platform.audit.application;

import com.mamoji.common.PagedResponse;
import com.mamoji.platform.audit.api.AuditLogQueryRequest;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.service.support.AccessControlService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Administrator-only application boundary for consistent, database-paged audit reads. */
@Service
public class AuditLogQueryService {
    private final AuditLogRepository repository;
    private final AccessControlService accessControl;

    public AuditLogQueryService(AuditLogRepository repository, AccessControlService accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<AuditLog> list(ActorContext actor, AuditLogQueryRequest request) {
        accessControl.requireAdmin(actor.legacyAuthorization());
        return repository.findPage(request.criteria(), request.pageRequest());
    }
}
