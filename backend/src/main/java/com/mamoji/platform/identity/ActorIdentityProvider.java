package com.mamoji.platform.identity;

import com.mamoji.domain.Models.User;
import java.util.Optional;

/** Port implemented by local sessions today and an enterprise OIDC adapter later. */
public interface ActorIdentityProvider {
    Optional<User> authenticate(String authorization);
}
