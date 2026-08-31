package com.mamoji.accessmanagement.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mamoji.accessmanagement.domain.ManagedUserAccessPolicy.AccessMutationRejectedException;
import org.junit.jupiter.api.Test;

class ManagedUserAccessPolicyTest {
    private final ManagedUserAccessPolicy policy = new ManagedUserAccessPolicy();

    @Test
    void preventsDemotingTheLastAdministrator() {
        ManagedUser administrator = user(1);
        ManagedUser member = administrator.changeAccess(2, null, "changed");

        assertThrows(
            AccessMutationRejectedException.class,
            () -> policy.ensureUpdateAllowed(administrator, member, 1)
        );
        assertDoesNotThrow(() -> policy.ensureUpdateAllowed(administrator, member, 2));
    }

    @Test
    void preventsDeletingTheLastUserOrAdministrator() {
        ManagedUser administrator = user(1);
        ManagedUser member = user(2);

        assertThrows(
            AccessMutationRejectedException.class,
            () -> policy.ensureDeletionAllowed(member, 1, 0)
        );
        assertThrows(
            AccessMutationRejectedException.class,
            () -> policy.ensureDeletionAllowed(administrator, 2, 1)
        );
        assertDoesNotThrow(() -> policy.ensureDeletionAllowed(administrator, 3, 2));
    }

    private ManagedUser user(int role) {
        return new ManagedUser(
            7, "member@example.invalid", "Member", "M|#123456", null, role, 1, "created", "updated"
        );
    }
}
