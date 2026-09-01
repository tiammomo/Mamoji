package com.mamoji.platform.identity;

import com.mamoji.platform.identity.session.application.LocalSessionService;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LocalSessionIdentityProvider implements ActorIdentityProvider {
    private final LocalSessionService sessions;

    public LocalSessionIdentityProvider(LocalSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<User> authenticate(String authorization) {
        return sessions.authenticate(authorization);
    }
}
