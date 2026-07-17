package com.mamoji.platform.identity;

import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class LocalSessionIdentityProvider implements ActorIdentityProvider {
    private final InMemoryStore sessions;

    public LocalSessionIdentityProvider(InMemoryStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<User> authenticate(String authorization) {
        return sessions.currentUser(authorization);
    }
}
