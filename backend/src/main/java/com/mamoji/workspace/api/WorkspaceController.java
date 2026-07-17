package com.mamoji.workspace.api;

import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import com.mamoji.workspace.application.WorkspaceApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {
    private final WorkspaceApplicationService service;

    public WorkspaceController(WorkspaceApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public WorkspaceView view(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.view(actor, companyId);
    }
}
