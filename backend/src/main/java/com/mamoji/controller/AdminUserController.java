package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.User;
import com.mamoji.service.AdminUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<User> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listUsers(authorization, params);
    }

    @PutMapping("/{id}")
    public User update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateUser(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        service.deleteUser(authorization, id);
    }
}
