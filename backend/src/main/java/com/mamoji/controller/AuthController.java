package com.mamoji.controller;

import com.mamoji.domain.Models.RegistrationInvite;
import com.mamoji.domain.Models.User;
import com.mamoji.service.AuthService;
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

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return service.login(body);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return service.register(body);
    }

    @GetMapping("/invitations")
    public List<RegistrationInvite> invitations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.listInvitations(authorization);
    }

    @PostMapping("/invitations")
    public RegistrationInvite createInvitation(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createInvitation(authorization, body);
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
        @RequestBody Map<String, Object> body
    ) {
        return service.updateProfile(authorization, body);
    }

    @PutMapping("/password")
    public Map<String, Object> changePassword(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.changePassword(authorization, body);
    }
}
