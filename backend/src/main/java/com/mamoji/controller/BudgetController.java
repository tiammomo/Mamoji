package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.common.RoleConstants;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.dto.BudgetDTO;
import com.mamoji.entity.User;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.service.BudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Budget management endpoints.
 *
 * <p>Supports budget CRUD plus active/range queries for the current user.
 */
@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private static final int FORBIDDEN_CODE = 1003;
    private static final String BUDGET_PERMISSION_MESSAGE = "No permission to manage budgets.";

    private final BudgetService budgetService;

    /**
     * Lists budgets, optionally filtered by date range.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getBudgets(
        @AuthenticationUser User user,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate
    ) {
        return ApiResponses.ok(
            hasDateRange(startDate, endDate)
                ? budgetService.getBudgetsByDateRange(user.getId(), LocalDate.parse(startDate), LocalDate.parse(endDate))
                : budgetService.getBudgets(user.getId())
        );
    }

    /**
     * Lists budgets active on the current date.
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveBudgets(@AuthenticationUser User user) {
        return ApiResponses.ok(budgetService.getActiveBudgets(user.getId()));
    }

    /**
     * Returns one budget by id.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getBudget(@PathVariable Long id, @AuthenticationUser User user) {
        return ApiResponses.ok(budgetService.getBudget(id, user.getId()));
    }

    /**
     * Creates a budget after permission validation.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createBudget(@RequestBody BudgetDTO dto, @AuthenticationUser User user) {
        if (!hasBudgetPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, BUDGET_PERMISSION_MESSAGE);
        }
        return ApiResponses.ok(budgetService.createBudget(dto, user.getId()));
    }

    /**
     * Updates a budget after permission validation.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateBudget(
        @PathVariable Long id,
        @RequestBody BudgetDTO dto,
        @AuthenticationUser User user
    ) {
        if (!hasBudgetPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, BUDGET_PERMISSION_MESSAGE);
        }
        return ApiResponses.ok(budgetService.updateBudget(id, dto, user.getId()));
    }

    /**
     * Deletes a budget after permission validation.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteBudget(@PathVariable Long id, @AuthenticationUser User user) {
        if (!hasBudgetPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, BUDGET_PERMISSION_MESSAGE);
        }
        budgetService.deleteBudget(id, user.getId());
        return ApiResponses.ok(null);
    }

    /**
     * Checks whether the caller can manage budget resources.
     */
    private boolean hasBudgetPermission(User user) {
        return RoleConstants.isAdmin(user.getRole())
            || PermissionConstants.hasPermission(user.getPermissions(), PermissionConstants.PERM_MANAGE_BUDGETS);
    }

    /**
     * Returns true when both date parameters are present.
     */
    private boolean hasDateRange(String startDate, String endDate) {
        return startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty();
    }
}
