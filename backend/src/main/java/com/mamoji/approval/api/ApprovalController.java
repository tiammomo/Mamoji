package com.mamoji.approval.api;

import com.mamoji.approval.application.ApprovalApplicationService;
import com.mamoji.approval.domain.ApprovalRequest;
import com.mamoji.common.PagedResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalApplicationService service;

    public ApprovalController(ApprovalApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<ApprovalRequest> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.list(authorization, params);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.summary(authorization, companyId);
    }

    @GetMapping("/{id}")
    public ApprovalDetail get(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.get(authorization, id);
    }

    @PostMapping
    public ApprovalDetail create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody ApprovalCreateRequest request
    ) {
        return service.create(authorization, request, idempotencyKey);
    }

    @PostMapping("/{id}/approve")
    public ApprovalDetail approve(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @Valid @RequestBody(required = false) ApprovalActionRequest request
    ) {
        return service.decide(authorization, id, "approve", request == null ? ApprovalActionRequest.empty() : request);
    }

    @PostMapping("/{id}/reject")
    public ApprovalDetail reject(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @Valid @RequestBody(required = false) ApprovalActionRequest request
    ) {
        return service.decide(authorization, id, "reject", request == null ? ApprovalActionRequest.empty() : request);
    }

    @PostMapping("/{id}/withdraw")
    public ApprovalDetail withdraw(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @Valid @RequestBody(required = false) ApprovalActionRequest request
    ) {
        return service.withdraw(authorization, id, request == null ? ApprovalActionRequest.empty() : request);
    }
}
