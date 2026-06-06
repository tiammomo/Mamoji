package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.Budget;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {
    private final MamojiService service;

    public BudgetController(MamojiService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<Budget> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listBudgets(authorization, params);
    }

    @GetMapping("/active")
    public List<Budget> active(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.activeBudgets(authorization);
    }

    @GetMapping("/{id}")
    public Budget get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        return service.getBudget(authorization, id);
    }

    @PostMapping
    public Budget create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createBudget(authorization, body);
    }

    @PutMapping("/{id}")
    public Budget update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateBudget(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        service.deleteBudget(authorization, id);
    }
}
