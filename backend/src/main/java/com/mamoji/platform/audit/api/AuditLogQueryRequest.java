package com.mamoji.platform.audit.api;

import com.mamoji.common.PageRequest;
import com.mamoji.platform.audit.domain.AuditLogSearchCriteria;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Typed query-string contract for administrator audit searches. */
public record AuditLogQueryRequest(
    @PositiveOrZero Long companyId,
    @Size(max = 100) String entityType,
    @PositiveOrZero Long entityId,
    @Size(max = 100) String action,
    @PositiveOrZero Long actorUserId,
    @Size(max = 200) String keyword,
    @Min(0) Integer page,
    @Min(1) @Max(PageRequest.MAX_SIZE) Integer size
) {
    public AuditLogSearchCriteria criteria() {
        return new AuditLogSearchCriteria(companyId, entityType, entityId, action, actorUserId, keyword);
    }

    public PageRequest pageRequest() {
        return new PageRequest(
            page == null ? PageRequest.DEFAULT_PAGE : page,
            size == null ? PageRequest.DEFAULT_SIZE : size
        );
    }
}
