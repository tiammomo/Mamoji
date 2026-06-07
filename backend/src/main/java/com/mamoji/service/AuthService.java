package com.mamoji.service;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.PasswordHasher;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class AuthService {
    private final InMemoryStore store;
    private final AccessControlService accessControl;
    private final PasswordHasher passwordHasher;

    public AuthService(InMemoryStore store, AccessControlService accessControl, PasswordHasher passwordHasher) {
        this.store = store;
        this.accessControl = accessControl;
        this.passwordHasher = passwordHasher;
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String email = text(body.get("email"));
        String password = text(body.get("password"));
        User user = store.findUserByEmail(email)
            .filter(candidate -> passwordHasher.matches(password, candidate.passwordHash))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        if (passwordHasher.needsUpgrade(user.passwordHash)) {
            user.passwordHash = passwordHasher.hash(password);
            touch(user);
            store.saveUser(user);
        }
        return authenticated(user);
    }

    public Map<String, Object> register(Map<String, Object> body) {
        String email = text(body.get("email"));
        if (email.isBlank() || text(body.get("password")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (store.findUserByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        User user = store.user(
            email,
            textOr(body.get("nickname"), email.substring(0, email.indexOf("@") > 0 ? email.indexOf("@") : email.length())),
            textOr(body.get("avatar"), "😊|#3370ff"),
            passwordHasher.hash(text(body.get("password"))),
            Roles.USER,
            Permissions.ALL
        );
        Ledger ledger = store.ledger(user.id, "公司经营账本", "初创公司经营收入、成本、税费与预算", "CNY", true);
        store.member(ledger.id, user.id, "owner");
        return authenticated(user);
    }

    public User me(String authorization) {
        return accessControl.requireUser(authorization);
    }

    public User updateProfile(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        if (body.containsKey("nickname")) {
            user.nickname = text(body.get("nickname"));
        }
        if (body.containsKey("avatar")) {
            user.avatar = text(body.get("avatar"));
        }
        touch(user);
        store.saveUser(user);
        return user;
    }

    public Map<String, Object> changePassword(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        if (!passwordHasher.matches(text(body.get("oldPassword")), user.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is incorrect");
        }
        String newPassword = text(body.get("newPassword"));
        if (newPassword.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 6 characters");
        }
        user.passwordHash = passwordHasher.hash(newPassword);
        touch(user);
        store.saveUser(user);
        return Map.of("success", true);
    }

    private Map<String, Object> authenticated(User user) {
        String token = UUID.randomUUID().toString();
        store.rememberToken(token, user.id);
        return Map.of("token", token, "user", user);
    }
}
