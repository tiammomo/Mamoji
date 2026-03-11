package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.common.RoleConstants;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.entity.Category;
import com.mamoji.entity.User;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Category management endpoints.
 *
 * <p>Returns categories grouped by income/expense and supports user-defined
 * category CRUD with permission checks.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private static final int FORBIDDEN_CODE = 1003;

    private final CategoryRepository categoryRepository;

    /**
     * Returns all categories grouped by transaction type.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getCategories(@AuthenticationUser User user) {
        List<Category> allCategories = categoryRepository.findAll();
        Map<String, Object> data = new HashMap<>();
        data.put("income", allCategories.stream().filter(category -> category.getType() == 1).map(this::toMap).toList());
        data.put("expense", allCategories.stream().filter(category -> category.getType() == 2).map(this::toMap).toList());
        return ApiResponses.ok(data);
    }

    /**
     * Creates a custom category.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createCategory(
        @AuthenticationUser User user,
        @RequestBody Map<String, Object> request
    ) {
        if (!hasCategoryPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "无分类管理权限。");
        }

        Category category = Category.builder()
            .name(request.get("name").toString())
            .type(Integer.parseInt(request.get("type").toString()))
            .icon(request.get("icon") != null ? request.get("icon").toString() : null)
            .color(request.get("color") != null ? request.get("color").toString() : null)
            .isSystem(0)
            .build();

        return ApiResponses.ok(toMap(categoryRepository.save(category)));
    }

    /**
     * Updates mutable fields of one category.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateCategory(
        @AuthenticationUser User user,
        @PathVariable Long id,
        @RequestBody Map<String, Object> request
    ) {
        if (!hasCategoryPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "无分类管理权限。");
        }

        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("分类不存在。"));
        if (request.get("name") != null) {
            category.setName(request.get("name").toString());
        }
        if (request.get("icon") != null) {
            category.setIcon(request.get("icon").toString());
        }
        if (request.get("color") != null) {
            category.setColor(request.get("color").toString());
        }
        return ApiResponses.ok(toMap(categoryRepository.save(category)));
    }

    /**
     * Deletes a non-system category.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteCategory(@AuthenticationUser User user, @PathVariable Long id) {
        if (!hasCategoryPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "无分类管理权限。");
        }

        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("分类不存在。"));
        if (category.getIsSystem() != null && category.getIsSystem() == 1) {
            return ApiResponses.badRequest(1005, "系统分类不能删除。");
        }

        categoryRepository.delete(category);
        return ApiResponses.ok(null);
    }

    /**
     * Checks whether caller can manage categories.
     */
    private boolean hasCategoryPermission(User user) {
        return RoleConstants.isAdmin(user.getRole())
            || PermissionConstants.hasPermission(user.getPermissions(), PermissionConstants.PERM_MANAGE_CATEGORIES);
    }

    /**
     * Converts category entity to API payload map.
     */
    private Map<String, Object> toMap(Category category) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", category.getId());
        map.put("name", category.getName());
        map.put("icon", category.getIcon());
        map.put("color", category.getColor());
        map.put("type", category.getType());
        map.put("isSystem", category.getIsSystem());
        return map;
    }
}
