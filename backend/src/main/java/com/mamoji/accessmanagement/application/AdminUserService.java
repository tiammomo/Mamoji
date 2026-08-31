package com.mamoji.accessmanagement.application;

import com.mamoji.accessmanagement.application.ManagedUserRepository.ManagedUserPage;
import com.mamoji.accessmanagement.application.ManagedUserRepository.UserDeletionConflictException;
import com.mamoji.accessmanagement.domain.ManagedUser;
import com.mamoji.accessmanagement.domain.ManagedUserAccessPolicy;
import com.mamoji.accessmanagement.domain.ManagedUserAccessPolicy.AccessMutationRejectedException;
import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserService {
    private final ManagedUserRepository repository;
    private final ManagedUserAccessPolicy accessPolicy;
    private final AdministratorAuthenticator authenticator;
    private final AccessManagementAuditLog auditLog;

    public AdminUserService(
        ManagedUserRepository repository,
        ManagedUserAccessPolicy accessPolicy,
        AdministratorAuthenticator authenticator,
        AccessManagementAuditLog auditLog
    ) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.authenticator = authenticator;
        this.auditLog = auditLog;
    }

    public PagedResponse<ManagedUser> listUsers(String authorization, Map<String, String> params) {
        authenticator.require(authorization);
        PageRequest pageRequest = PageRequest.from(params);
        ManagedUserPage page = repository.search(params.get("keyword"), pageRequest);
        int totalPages = (int) Math.ceil((double) page.totalElements() / pageRequest.size());
        return new PagedResponse<>(
            page.content(), page.totalElements(), totalPages, pageRequest.size(), pageRequest.page()
        );
    }

    @Transactional
    public ManagedUser updateUser(String authorization, long id, AdminUserAccessCommand command) {
        AdministratorActor operator = lockAndRequireAdministrator(authorization);
        ManagedUser current = repository.findForAccessMutation(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        final ManagedUser updated;
        try {
            updated = current.changeAccess(command.role(), command.permissions(), OffsetDateTime.now().toString());
            accessPolicy.ensureUpdateAllowed(current, updated, repository.countAdministrators());
        } catch (AccessMutationRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        repository.updateAccess(updated);
        auditLog.record(
            updated.id(), "update_permissions", "更新用户角色或权限: " + updated.email(), operator
        );
        return updated;
    }

    @Transactional
    public void deleteUser(String authorization, long id) {
        AdministratorActor operator = lockAndRequireAdministrator(authorization);
        ManagedUser user = repository.findForAccessMutation(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        try {
            accessPolicy.ensureDeletionAllowed(user, repository.countUsers(), repository.countAdministrators());
            repository.delete(id);
        } catch (AccessMutationRejectedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        } catch (UserDeletionConflictException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
        auditLog.record(id, "delete", "删除用户: " + user.email(), operator);
    }

    private AdministratorActor lockAndRequireAdministrator(String authorization) {
        authenticator.require(authorization);
        repository.lockAccessMutations();
        return authenticator.require(authorization);
    }
}
