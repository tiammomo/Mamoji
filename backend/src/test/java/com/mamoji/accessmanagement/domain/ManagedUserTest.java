package com.mamoji.accessmanagement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ManagedUserTest {
    @Test
    void changesOnlyTheAccessFieldsAndTimestamp() {
        ManagedUser current = user(2, 1);

        ManagedUser updated = current.changeAccess(1, 15, "2026-08-31T16:00:00+08:00");

        assertEquals(current.id(), updated.id());
        assertEquals(current.email(), updated.email());
        assertEquals(1, updated.role());
        assertEquals(15, updated.permissions());
        assertEquals("2026-08-31T16:00:00+08:00", updated.updatedAt());
    }

    @Test
    void rejectsUnknownRolesAndPermissionBits() {
        ManagedUser current = user(2, 1);

        assertThrows(IllegalArgumentException.class, () -> current.changeAccess(3, null, "now"));
        assertThrows(IllegalArgumentException.class, () -> current.changeAccess(null, 16, "now"));
    }

    private ManagedUser user(int role, int permissions) {
        return new ManagedUser(
            7, "member@example.invalid", "Member", "M|#123456", null, role, permissions, "created", "updated"
        );
    }
}
