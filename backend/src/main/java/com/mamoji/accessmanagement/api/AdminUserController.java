package com.mamoji.accessmanagement.api;

import com.mamoji.accessmanagement.application.AdminUserAccessCommand;
import com.mamoji.accessmanagement.application.AdminUserService;
import com.mamoji.accessmanagement.domain.ManagedUser;
import com.mamoji.common.PagedResponse;
import com.mamoji.platform.product.RequiresProductModule;
import jakarta.validation.Valid;
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
@RequiresProductModule("access-management")
public class AdminUserController {
    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<ManagedUser> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listUsers(authorization, params);
    }

    @PutMapping("/{id}")
    public ManagedUser update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @Valid @RequestBody AdminUserAccessUpdateRequest request
    ) {
        return service.updateUser(
            authorization,
            id,
            new AdminUserAccessCommand(request.role(), request.permissions())
        );
    }

    @DeleteMapping("/{id}")
    public void delete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        service.deleteUser(authorization, id);
    }
}
