package com.mamoji.people.api;

import com.mamoji.people.application.DepartmentApplicationService;
import com.mamoji.people.domain.Department;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import com.mamoji.platform.product.RequiresProductModule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiresProductModule("people-core")
@RequestMapping("/api/v1/enterprise/departments")
public class DepartmentController {
    private final DepartmentApplicationService service;

    public DepartmentController(DepartmentApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Department> list(
        @CurrentActor ActorContext actor,
        @Positive @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.list(actor, companyId);
    }

    @PostMapping
    public Department create(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody DepartmentCreateRequest request
    ) {
        return service.create(actor, request);
    }

    @PutMapping("/{id}")
    public Department update(
        @CurrentActor ActorContext actor,
        @Positive @PathVariable long id,
        @Valid @RequestBody DepartmentUpdateRequest request
    ) {
        return service.update(actor, id, request);
    }
}
