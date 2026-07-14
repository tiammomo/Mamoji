package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.service.ApprovalService;
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
    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<ApprovalService.ApprovalRequest> list(
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
    public ApprovalService.ApprovalDetail get(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.get(authorization, id);
    }

    @PostMapping
    public ApprovalService.ApprovalDetail create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.create(authorization, body);
    }

    @PostMapping("/{id}/approve")
    public ApprovalService.ApprovalDetail approve(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return service.decide(authorization, id, "approve", body == null ? Map.of() : body);
    }

    @PostMapping("/{id}/reject")
    public ApprovalService.ApprovalDetail reject(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return service.decide(authorization, id, "reject", body == null ? Map.of() : body);
    }

    @PostMapping("/{id}/withdraw")
    public ApprovalService.ApprovalDetail withdraw(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return service.withdraw(authorization, id, body == null ? Map.of() : body);
    }
}
