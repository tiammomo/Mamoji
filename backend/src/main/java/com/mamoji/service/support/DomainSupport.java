package com.mamoji.service.support;

import com.mamoji.repository.InMemoryStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class DomainSupport {
    private DomainSupport() {
    }

    public static <T> T require(T value, String message) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return value;
    }

    public static void assertOwner(long ownerId, long currentUserId) {
        if (ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    public static void touch(Object model) {
        try {
            model.getClass().getField("updatedAt").set(model, InMemoryStore.now());
        } catch (ReflectiveOperationException ignored) {
            // Models without updatedAt do not need mutation timestamps.
        }
    }
}
