package com.mamoji.controller;

import com.mamoji.domain.Models.Category;
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
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final MamojiService service;

    public CategoryController(MamojiService service) {
        this.service = service;
    }

    @GetMapping
    public List<Category> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "type", required = false) String type
    ) {
        return service.listCategories(authorization, type);
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
        @RequestBody Map<String, Object> body
    ) {
        return service.updateCategory(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        service.deleteCategory(authorization, id);
    }
}
