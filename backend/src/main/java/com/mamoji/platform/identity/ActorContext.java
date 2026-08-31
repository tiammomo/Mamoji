package com.mamoji.platform.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Authenticated request actor, independent from the concrete SSO mechanism. */
public record ActorContext(User user, @JsonIgnore String legacyAuthorization) {
    public long userId() {
        return user.id;
    }
}
