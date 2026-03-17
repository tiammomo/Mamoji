package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.common.RoleConstants;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.dto.AccountDTO;
import com.mamoji.entity.User;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.service.AccountService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Account management endpoints.
 *
 * <p>Provides account CRUD operations and aggregated balance summary for the current user.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private static final int FORBIDDEN_CODE = 1003;
    private static final String ACCOUNT_PERMISSION_MESSAGE = "No permission to manage accounts.";

    private final AccountService accountService;

    /**
     * Lists all active accounts owned by the current user.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAccounts(@AuthenticationUser User user) {
        return ApiResponses.ok(accountService.getAccounts(user.getId()));
    }

    /**
     * Returns one account detail by id.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAccount(@PathVariable Long id, @AuthenticationUser User user) {
        return ApiResponses.ok(accountService.getAccount(id, user.getId()));
    }

    /**
     * Creates a new account after permission validation.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody AccountDTO dto, @AuthenticationUser User user) {
        if (!hasAccountPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, ACCOUNT_PERMISSION_MESSAGE);
        }
        return ApiResponses.ok(accountService.createAccount(dto, user.getId()));
    }

    /**
     * Updates account fields after permission validation.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateAccount(
        @PathVariable Long id,
        @RequestBody AccountDTO dto,
        @AuthenticationUser User user
    ) {
        if (!hasAccountPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, ACCOUNT_PERMISSION_MESSAGE);
        }
        return ApiResponses.ok(accountService.updateAccount(id, dto, user.getId()));
    }

    /**
     * Soft deletes one account after permission validation.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteAccount(@PathVariable Long id, @AuthenticationUser User user) {
        if (!hasAccountPermission(user)) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, ACCOUNT_PERMISSION_MESSAGE);
        }
        accountService.deleteAccount(id, user.getId());
        return ApiResponses.ok(null);
    }

    /**
     * Returns account summary either for full history or a custom date range.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary(
        @AuthenticationUser User user,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate
    ) {
        Map<String, BigDecimal> summary = hasDateRange(startDate, endDate)
            ? accountService.getAccountSummaryByDateRange(
                user.getId(),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate)
            )
            : accountService.getAccountSummary(user.getId());
        return ApiResponses.ok(summary);
    }

    /**
     * Checks whether the caller can manage account resources.
     */
    private boolean hasAccountPermission(User user) {
        return RoleConstants.isAdmin(user.getRole())
            || PermissionConstants.hasPermission(user.getPermissions(), PermissionConstants.PERM_MANAGE_ACCOUNTS);
    }

    /**
     * Returns true when both date parameters are present.
     */
    private boolean hasDateRange(String startDate, String endDate) {
        return startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty();
    }
}
