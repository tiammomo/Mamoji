package com.mamoji.controller;

import com.mamoji.domain.Models.RecurringItem;
import com.mamoji.service.MamojiService;
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

@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {
    private final MamojiService service;

    public RecurringController(MamojiService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecurringItem> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.listRecurring(authorization);
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
        @RequestBody Map<String, Object> body
    ) {
        return service.updateRecurring(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        service.deleteRecurring(authorization, id);
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return service.toggleRecurring(authorization, id);
    }

    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return service.executeRecurring(authorization, id);
    }
}
