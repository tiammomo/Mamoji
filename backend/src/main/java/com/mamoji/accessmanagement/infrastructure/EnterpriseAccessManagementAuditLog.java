package com.mamoji.accessmanagement.infrastructure;

import com.mamoji.accessmanagement.application.AccessManagementAuditLog;
import com.mamoji.accessmanagement.application.AdministratorActor;
import com.mamoji.repository.EnterpriseStore;
import org.springframework.stereotype.Component;

@Component
public class EnterpriseAccessManagementAuditLog implements AccessManagementAuditLog {
    private final EnterpriseStore enterpriseStore;

    public EnterpriseAccessManagementAuditLog(EnterpriseStore enterpriseStore) {
        this.enterpriseStore = enterpriseStore;
    }

    @Override
    public void record(long targetUserId, String action, String description, AdministratorActor actor) {
        enterpriseStore.auditLog(
            0, "user", targetUserId, action, description, actor.userId(), actor.nickname()
        );
    }
}
