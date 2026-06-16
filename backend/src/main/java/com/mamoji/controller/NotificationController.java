package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.service.NotificationService;
import com.mamoji.service.NotificationService.NotificationPreference;
import com.mamoji.service.NotificationService.NotificationView;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<NotificationView> notifications(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.list(authorization, params);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.summary(authorization);
    }

    @GetMapping("/preferences")
    public NotificationPreference preference(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.preference(authorization);
    }

    @PutMapping("/preferences")
    public NotificationPreference updatePreference(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.updatePreference(authorization, body);
    }

    @PostMapping("/preferences/test-webhook")
    public Map<String, Object> testWebhook(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.testWebhook(authorization);
    }

    @PutMapping("/{id}/read")
    public NotificationView markRead(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.markRead(authorization, id);
    }

    @PutMapping("/read-all")
    public Map<String, Object> markAllRead(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.markAllRead(authorization);
    }
}
