package com.mamoji.platform.identity.account.infrastructure;

import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.account.application.LocalUserAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLocalUserAccountRepository implements LocalUserAccountRepository {
    private static final String COLUMNS = """
        id, email, nickname, avatar, family_id, role, permissions,
        password_hash, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcLocalUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public User insert(User user) {
        Long id = jdbc.queryForObject("""
            INSERT INTO users (
                email, nickname, avatar, family_id, role, permissions,
                password_hash, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            user.email,
            user.nickname,
            user.avatar,
            user.familyId,
            user.role,
            user.permissions,
            user.passwordHash,
            timestamp(user.createdAt),
            timestamp(user.updatedAt)
        );
        if (id == null) {
            throw new IllegalStateException("User account insert returned no identity");
        }
        user.id = id;
        return user;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE email = ?",
            this::map,
            email
        ).stream().findFirst();
    }

    @Override
    public Optional<User> findById(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ?",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<User> findByIdForUpdate(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ? FOR UPDATE",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public void update(User user) {
        int updated = jdbc.update("""
            UPDATE users
            SET email = ?, nickname = ?, avatar = ?, family_id = ?, role = ?, permissions = ?,
                password_hash = ?, created_at = ?, updated_at = ?
            WHERE id = ?
            """,
            user.email,
            user.nickname,
            user.avatar,
            user.familyId,
            user.role,
            user.permissions,
            user.passwordHash,
            timestamp(user.createdAt),
            timestamp(user.updatedAt),
            user.id
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                "User account changed during update: " + user.id
            );
        }
    }

    @Override
    public boolean updatePasswordHashIfCurrent(
        long userId,
        String currentPasswordHash,
        String nextPasswordHash,
        String updatedAt
    ) {
        return jdbc.update("""
            UPDATE users SET password_hash = ?, updated_at = ?
            WHERE id = ? AND password_hash = ?
            """,
            nextPasswordHash,
            timestamp(updatedAt),
            userId,
            currentPasswordHash
        ) == 1;
    }

    private User map(ResultSet rs, int rowNum) throws SQLException {
        User user = new User();
        user.id = rs.getLong("id");
        user.email = rs.getString("email");
        user.nickname = rs.getString("nickname");
        user.avatar = rs.getString("avatar");
        long familyId = rs.getLong("family_id");
        user.familyId = rs.wasNull() ? null : familyId;
        user.role = rs.getInt("role");
        user.permissions = rs.getInt("permissions");
        user.passwordHash = rs.getString("password_hash");
        user.createdAt = rs.getObject("created_at", OffsetDateTime.class).toString();
        user.updatedAt = rs.getObject("updated_at", OffsetDateTime.class).toString();
        return user;
    }

    private OffsetDateTime timestamp(String value) {
        return OffsetDateTime.parse(value);
    }
}
