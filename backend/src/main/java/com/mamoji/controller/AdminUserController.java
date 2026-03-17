package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.common.RoleConstants;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.entity.User;
import com.mamoji.repository.UserRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin-only user management endpoints.
 *
 * <p>Supports listing, creating, updating and deleting user accounts from the admin console.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private static final int FORBIDDEN_CODE = 1003;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Lists all users for the admin console.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getUsers(@AuthenticationUser User currentUser) {
        if (!RoleConstants.isAdmin(currentUser.getRole())) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "Access denied.");
        }

        List<Map<String, Object>> userList = userRepository.findAll()
            .stream()
            .map(this::toUserMap)
            .toList();
        return ApiResponses.ok(userList);
    }

    /**
     * Creates one user account with role and permission assignment.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createUser(
        @AuthenticationUser User currentUser,
        @RequestBody Map<String, String> request
    ) {
        if (!RoleConstants.isAdmin(currentUser.getRole())) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "Access denied.");
        }

        String email = request.get("email");
        if (userRepository.existsByEmail(email)) {
            return ApiResponses.badRequest(2002, "Email is already registered.");
        }

        Integer role = request.get("role") != null ? Integer.parseInt(request.get("role")) : RoleConstants.USER;
        Integer permissions = request.get("permissions") != null
            ? Integer.parseInt(request.get("permissions"))
            : PermissionConstants.DEFAULT_USER_PERMISSIONS;

        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(request.get("password")))
            .nickname(request.get("nickname"))
            .role(role)
            .permissions(permissions)
            .familyId(currentUser.getFamilyId())
            .build();

        return ApiResponses.ok(toUserMap(userRepository.save(user)));
    }

    /**
     * Updates user profile, role, permissions or password.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(
        @AuthenticationUser User currentUser,
        @PathVariable Long id,
        @RequestBody Map<String, Object> request
    ) {
        if (!RoleConstants.isAdmin(currentUser.getRole())) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "Access denied.");
        }

        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (request.get("nickname") != null) {
            user.setNickname(request.get("nickname").toString());
        }
        if (request.get("role") != null) {
            user.setRole(Integer.parseInt(request.get("role").toString()));
        }
        if (request.get("permissions") != null) {
            user.setPermissions(Integer.parseInt(request.get("permissions").toString()));
        }
        if (request.get("password") != null && !request.get("password").toString().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(request.get("password").toString()));
        }

        return ApiResponses.ok(toUserMap(userRepository.save(user)));
    }

    /**
     * Deletes one user account, except self-delete.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@AuthenticationUser User currentUser, @PathVariable Long id) {
        if (!RoleConstants.isAdmin(currentUser.getRole())) {
            return ApiResponses.forbidden(FORBIDDEN_CODE, "Access denied.");
        }
        if (currentUser.getId().equals(id)) {
            return ApiResponses.badRequest(1004, "You cannot delete your own account.");
        }

        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        userRepository.delete(user);
        return ApiResponses.ok(null);
    }

    /**
     * Maps user entity to the admin response payload.
     */
    private Map<String, Object> toUserMap(User user) {
        int role = user.getRole() != null ? user.getRole() : RoleConstants.USER;
        int permissions = user.getPermissions() != null ? user.getPermissions() : PermissionConstants.DEFAULT_USER_PERMISSIONS;
        return Map.of(
            "id", user.getId(),
            "email", user.getEmail(),
            "nickname", user.getNickname(),
            "role", role,
            "roleName", RoleConstants.getRoleName(role),
            "permissions", permissions,
            "permissionsName", PermissionConstants.getPermissionNames(permissions),
            "familyId", user.getFamilyId() != null ? user.getFamilyId() : 0
        );
    }
}
