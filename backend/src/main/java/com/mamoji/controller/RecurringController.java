package com.mamoji.controller;

import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.service.RecurringService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {
    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecurringItem> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listRecurring(authorization, companyId);
    }

    @PostMapping
    public RecurringItem create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createRecurring(authorization, body);
    }

    @PutMapping("/{id}")
    public RecurringItem update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        Map<String, Object> scopedBody = new java.util.LinkedHashMap<>(body);
        if (companyId != null) scopedBody.put("companyId", companyId);
        return service.updateRecurring(authorization, id, scopedBody);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.deleteRecurring(authorization, id, companyId);
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.toggleRecurring(authorization, id, companyId);
    }

    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.executeRecurring(authorization, id, companyId);
    }
}
