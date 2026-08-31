package com.mamoji.accessmanagement.domain;

import com.mamoji.common.Permissions;
import com.mamoji.common.Roles;

/** Password-free user view owned by the access-management boundary. */
public record ManagedUser(
    long id,
    String email,
    String nickname,
    String avatar,
    Long familyId,
    int role,
    int permissions,
    String createdAt,
    String updatedAt
) {
    public ManagedUser changeAccess(Integer requestedRole, Integer requestedPermissions, String changedAt) {
        int nextRole = requestedRole == null ? role : requestedRole;
        int nextPermissions = requestedPermissions == null ? permissions : requestedPermissions;
        if (nextRole != Roles.ADMIN && nextRole != Roles.USER) {
            throw new IllegalArgumentException("Role must be admin(1) or user(2)");
        }
        if (nextPermissions < 0 || (nextPermissions & ~Permissions.ALL) != 0) {
            throw new IllegalArgumentException("Permissions contain unsupported bits");
        }
        if (changedAt == null || changedAt.isBlank()) {
            throw new IllegalArgumentException("Access change timestamp is required");
        }
        return new ManagedUser(
            id, email, nickname, avatar, familyId, nextRole, nextPermissions, createdAt, changedAt
        );
    }

    public boolean administrator() {
        return role == Roles.ADMIN;
    }
}
