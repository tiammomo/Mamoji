package com.mamoji.platform.access;

import org.springframework.web.bind.annotation.GetMapping;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class AccessContextController {
    private final AccessContextService service;

    public AccessContextController(AccessContextService service) {
        this.service = service;
    }

    @GetMapping("/access-context")
    public AccessContextView accessContext(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.resolve(actor, companyId);
    }
}
