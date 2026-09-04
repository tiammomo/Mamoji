package com.mamoji.operations.api;

import com.mamoji.operations.application.CategoryApplicationService;
import com.mamoji.operations.domain.Category;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryApplicationService service;

    public CategoryController(CategoryApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Category> list(
        @CurrentActor ActorContext actor,
        @Pattern(regexp = "(?i)\\s*(income|expense)\\s*")
        @RequestParam(value = "type", required = false) String type,
        @Positive @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listCategories(actor, type, companyId);
    }

    @PostMapping
    public Category create(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody CategoryCreateRequest request
    ) {
        return service.createCategory(actor, request);
    }

    @PutMapping("/{id}")
    public Category update(
        @CurrentActor ActorContext actor,
        @Positive @PathVariable long id,
        @Positive @RequestParam(value = "companyId", required = false) Long companyId,
        @Valid @RequestBody CategoryUpdateRequest request
    ) {
        return service.updateCategory(actor, id, companyId, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @CurrentActor ActorContext actor,
        @Positive @PathVariable long id,
        @Positive @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.deleteCategory(actor, id, companyId);
    }
}
