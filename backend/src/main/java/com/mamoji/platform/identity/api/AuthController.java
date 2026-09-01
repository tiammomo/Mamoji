package com.mamoji.platform.identity.api;

import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.security.infrastructure.ClientAddressResolver;
import com.mamoji.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final ClientAddressResolver clientAddressResolver;

    public AuthController(AuthService service, ClientAddressResolver clientAddressResolver) {
        this.service = service;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return service.login(request, clientAddressResolver.resolve(httpRequest));
    }

    @PostMapping("/register")
    public Map<String, Object> register(@Valid @RequestBody RegistrationRequest request) {
        return service.register(request);
    }

    @GetMapping("/invitations")
    public List<RegistrationInviteResponse> invitations(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.listInvitations(authorization);
    }

    @PostMapping("/invitations")
    public RegistrationInviteResponse createInvitation(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @RequestBody RegistrationInviteCreateRequest request
    ) {
        return service.createInvitation(authorization, request);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.logout(authorization);
    }

    @GetMapping("/me")
    public User me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.me(authorization);
    }

    @PutMapping("/profile")
    public User updateProfile(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @RequestBody ProfileUpdateRequest request
    ) {
        return service.updateProfile(authorization, request);
    }

    @PutMapping("/password")
    public Map<String, Object> changePassword(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @RequestBody PasswordChangeRequest request
    ) {
        return service.changePassword(authorization, request);
    }

}
