package com.mamoji.budget.api;

import com.mamoji.budget.application.BudgetApplicationService;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.Budget;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {
    private final BudgetApplicationService service;

    public BudgetController(BudgetApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<Budget> list(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestParam(value = "status", required = false) Integer status,
        @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return service.list(actor, companyId, status, startDate, endDate, keyword, page, size);
    }

    @GetMapping("/active")
    public List<Budget> active(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.active(actor, companyId);
    }

    @GetMapping("/{id}")
    public Budget get(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.get(actor, id, companyId);
    }

    @PostMapping
    public Budget create(@CurrentActor ActorContext actor, @Valid @RequestBody BudgetCreateRequest request) {
        return service.create(actor, request);
    }

    @PutMapping("/{id}")
    public Budget update(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @Valid @RequestBody BudgetUpdateRequest request
    ) {
        return service.update(actor, id, companyId, request);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.delete(actor, id, companyId);
    }
}
