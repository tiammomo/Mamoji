package com.mamoji.repository;

import com.mamoji.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/**
 * Repository for user account persistence.
 */
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * Finds user by unique email.
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks whether email has been registered.
     */
    boolean existsByEmail(String email);
}
