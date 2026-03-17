package com.mamoji.controller;

import com.mamoji.common.PermissionConstants;
import com.mamoji.common.RoleConstants;
import com.mamoji.common.api.ApiResponses;
import com.mamoji.entity.User;
import com.mamoji.repository.UserRepository;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication and profile endpoints.
 *
 * <p>Handles register/login, current user profile retrieval, profile update and password change.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Registers a new user and returns token plus current-user payload.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");
        String nickname = request.get("nickname");

        log.info("Register request email={}", email);

        if (userRepository.existsByEmail(email)) {
            return ApiResponses.badRequest(2002, "Email is already registered.");
        }

        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .nickname(nickname)
            .role(RoleConstants.USER)
            .permissions(PermissionConstants.DEFAULT_USER_PERMISSIONS)
            .build();

        user = userRepository.save(user);
        return ApiResponses.ok(buildAuthPayload(user, jwtService.generateToken(user.getId())));
    }

    /**
     * Authenticates user credentials and returns token plus current-user payload.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        log.info("Login request email={}", email);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return ApiResponses.badRequest(1002, "Invalid email or password.");
        }

        return ApiResponses.ok(buildAuthPayload(user, jwtService.generateToken(user.getId())));
    }

    /**
     * Returns the current authenticated user profile.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(@AuthenticationUser User user) {
        return ApiResponses.ok(buildCurrentUserPayload(user));
    }

    /**
     * Updates mutable profile fields.
     */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
        @AuthenticationUser User user,
        @RequestBody Map<String, String> request
    ) {
        String nickname = request.get("nickname");
        String avatarUrl = request.get("avatarUrl");

        if (nickname != null && !nickname.trim().isEmpty()) {
            user.setNickname(nickname.trim());
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }

        User savedUser = userRepository.save(user);
        return ApiResponses.ok(Map.of(
            "id", savedUser.getId(),
            "email", savedUser.getEmail(),
            "nickname", savedUser.getNickname(),
            "avatarUrl", savedUser.getAvatarUrl() != null ? savedUser.getAvatarUrl() : ""
        ));
    }

    /**
     * Changes the current user password after verifying the old password.
     */
    @PutMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
        @AuthenticationUser User user,
        @RequestBody Map<String, String> request
    ) {
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");

        if (oldPassword == null || newPassword == null) {
            return ApiResponses.badRequest(1005, "Please provide old and new password.");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            return ApiResponses.badRequest(1006, "Old password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return ResponseEntity.ok(ApiResponses.body(0, "Password updated successfully.", null));
    }

    /**
     * Builds the shared auth payload used by register and login responses.
     */
    private Map<String, Object> buildAuthPayload(User user, String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", buildCurrentUserPayload(user));
        return data;
    }

    /**
     * Builds a normalized current-user payload for frontend consumption.
     */
    private Map<String, Object> buildCurrentUserPayload(User user) {
        Map<String, Object> data = new HashMap<>();
        int role = user.getRole() != null ? user.getRole() : RoleConstants.USER;
        int permissions = user.getPermissions() != null ? user.getPermissions() : PermissionConstants.DEFAULT_USER_PERMISSIONS;
        data.put("id", user.getId());
        data.put("email", user.getEmail());
        data.put("nickname", user.getNickname());
        data.put("familyId", user.getFamilyId() != null ? user.getFamilyId() : 0);
        data.put("role", role);
        data.put("roleName", RoleConstants.getRoleName(role));
        data.put("permissions", permissions);
        data.put("permissionsName", PermissionConstants.getPermissionNames(permissions));
        data.put("isAdmin", RoleConstants.isAdmin(user.getRole()));
        data.put("avatarUrl", user.getAvatarUrl() != null ? user.getAvatarUrl() : "");
        return data;
    }
}
