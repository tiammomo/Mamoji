package com.mamoji.accessmanagement.infrastructure;

import com.mamoji.accessmanagement.application.AccessManagementAuditLog;
import com.mamoji.accessmanagement.application.AdministratorActor;
import com.mamoji.platform.audit.application.AuditTrailService;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseAccessManagementAuditLog implements AccessManagementAuditLog {
    private final AuditTrailService auditTrail;

    public EnterpriseAccessManagementAuditLog(AuditTrailService auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    public void record(long targetUserId, String action, String description, AdministratorActor actor) {
        auditTrail.record(
            0, "user", targetUserId, action, description, actor.userId(), actor.nickname()
        );
    }
}
