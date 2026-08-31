package com.mamoji.accessmanagement.domain;

import org.springframework.stereotype.Component;

/** Cross-user invariants evaluated while the users table is locked for an access mutation. */
@Component
public class ManagedUserAccessPolicy {
    public void ensureUpdateAllowed(ManagedUser current, ManagedUser updated, long administratorCount) {
        if (current.administrator() && !updated.administrator() && administratorCount <= 1) {
            throw new AccessMutationRejectedException("Cannot demote the last administrator");
        }
    }

    public void ensureDeletionAllowed(ManagedUser current, long userCount, long administratorCount) {
        if (userCount <= 1) {
            throw new AccessMutationRejectedException("Cannot delete the last user");
        }
        if (current.administrator() && administratorCount <= 1) {
            throw new AccessMutationRejectedException("Cannot delete the last administrator");
        }
    }

    public static final class AccessMutationRejectedException extends IllegalStateException {
        public AccessMutationRejectedException(String message) {
            super(message);
        }
    }
}
