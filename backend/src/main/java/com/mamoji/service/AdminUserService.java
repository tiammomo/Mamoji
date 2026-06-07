package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.intValue;
import static com.mamoji.service.support.DomainSupport.require;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class AdminUserService {
    private final InMemoryStore store;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;

    public AdminUserService(InMemoryStore store, EnterpriseStore enterpriseStore, AccessControlService accessControl) {
        this.store = store;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
    }

    public PagedResponse<User> listUsers(String authorization, Map<String, String> params) {
        accessControl.requireAdmin(authorization);
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        List<User> users = store.users.values().stream()
            .filter(user -> keyword.isBlank() || user.email.toLowerCase().contains(keyword) || user.nickname.toLowerCase().contains(keyword))
            .sorted(Comparator.comparing(user -> user.id))
            .toList();
        return PagedResponse.of(users, PageRequest.from(params));
    }

    public User updateUser(String authorization, long id, Map<String, Object> body) {
        User operator = accessControl.requireAdmin(authorization);
        User user = require(store.users.get(id), "User not found");
        if (body.containsKey("role")) {
            user.role = intValue(body.get("role"), user.role);
        }
        if (body.containsKey("permissions")) {
            user.permissions = intValue(body.get("permissions"), user.permissions);
        }
        touch(user);
        store.saveUser(user);
        enterpriseStore.auditLog(0, "user", user.id, "update_permissions", "更新用户角色或权限: " + user.email, operator.id, operator.nickname);
        return user;
    }

    public void deleteUser(String authorization, long id) {
        User operator = accessControl.requireAdmin(authorization);
        if (store.users.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete last user");
        }
        User user = require(store.users.get(id), "User not found");
        store.deleteUser(id);
        enterpriseStore.auditLog(0, "user", id, "delete", "删除用户: " + user.email, operator.id, operator.nickname);
    }
}
