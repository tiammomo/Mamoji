package com.mamoji.controller;

import com.mamoji.domain.Models.Category;
import com.mamoji.service.AccountingService;
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
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final AccountingService service;

    public CategoryController(AccountingService service) {
        this.service = service;
    }

    @GetMapping
    public List<Category> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "type", required = false) String type,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listCategories(authorization, type, companyId);
    }

    @PostMapping
    public Category create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createCategory(authorization, body);
    }

    @PutMapping("/{id}")
    public Category update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateCategory(authorization, id, companyId, body);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.deleteCategory(authorization, id, companyId);
    }
}
