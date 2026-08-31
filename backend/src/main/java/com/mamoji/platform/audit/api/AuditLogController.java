package com.mamoji.platform.audit.api;

import com.mamoji.common.PagedResponse;
import com.mamoji.platform.audit.application.AuditLogQueryService;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {
    private final AuditLogQueryService service;

    public AuditLogController(AuditLogQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<AuditLog> list(
        @CurrentActor ActorContext actor,
        @Valid @ModelAttribute AuditLogQueryRequest request
    ) {
        return service.list(actor, request);
    }
}
