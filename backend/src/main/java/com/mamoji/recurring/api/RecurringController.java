package com.mamoji.recurring.api;

import com.mamoji.recurring.domain.RecurringItem;
import com.mamoji.service.RecurringService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        @Valid @RequestBody RecurringCreateRequest request
    ) {
        return service.createRecurring(authorization, request);
    }

    @PutMapping("/{id}")
    public RecurringItem update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable String id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @Valid @RequestBody RecurringUpdateRequest request
    ) {
        return service.updateRecurring(authorization, id, companyId, request);
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
