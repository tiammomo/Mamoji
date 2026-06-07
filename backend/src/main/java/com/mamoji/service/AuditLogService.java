package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.AuditLog;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;

    public AuditLogService(EnterpriseStore enterpriseStore, AccessControlService accessControl) {
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
    }

    public PagedResponse<AuditLog> list(String authorization, Map<String, String> params) {
        accessControl.requireAdmin(authorization);
        Long companyId = longParam(params.get("companyId"));
        Long actorUserId = longParam(params.get("actorUserId"));
        String entityType = params.getOrDefault("entityType", "").trim();
        String action = params.getOrDefault("action", "").trim();
        String keyword = params.getOrDefault("keyword", "").trim().toLowerCase();

        List<AuditLog> logs = enterpriseStore.sortedAuditLogs().stream()
            .filter(log -> companyId == null || log.companyId == companyId)
            .filter(log -> actorUserId == null || log.actorUserId == actorUserId)
            .filter(log -> entityType.isBlank() || log.entityType.equals(entityType))
            .filter(log -> action.isBlank() || log.action.equals(action))
            .filter(log -> keyword.isBlank()
                || log.summary.toLowerCase().contains(keyword)
                || log.actorName.toLowerCase().contains(keyword)
                || log.entityType.toLowerCase().contains(keyword)
                || log.action.toLowerCase().contains(keyword))
            .toList();
        return PagedResponse.of(logs, PageRequest.from(params));
    }

    private Long longParam(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
