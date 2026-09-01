package com.mamoji.platform.identity.session.infrastructure;

import com.mamoji.platform.identity.User;
import com.mamoji.platform.identity.session.application.LocalSessionRepository;
import com.mamoji.platform.identity.session.domain.LocalSession;
import com.mamoji.platform.identity.session.domain.SessionTokenDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLocalSessionRepository implements LocalSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcLocalSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(LocalSession session) {
        int inserted = jdbc.update("""
            INSERT INTO auth_tokens (token, user_id, created_at, expires_at)
            VALUES (?, ?, ?, ?)
            """,
            session.tokenDigest().value(),
            session.userId(),
            session.createdAt(),
            session.expiresAt()
        );
        if (inserted != 1) {
            throw new IllegalStateException("Session insert did not write exactly one row");
        }
    }

    @Override
    public Optional<User> findActiveUser(SessionTokenDigest tokenDigest, OffsetDateTime now) {
        return jdbc.query("""
            SELECT users.*
            FROM auth_tokens session
            JOIN users ON users.id = session.user_id
            WHERE session.token = ? AND session.expires_at > ?
            """, this::mapUser, tokenDigest.value(), now).stream().findFirst();
    }

    @Override
    public void delete(SessionTokenDigest tokenDigest) {
        jdbc.update("DELETE FROM auth_tokens WHERE token = ?", tokenDigest.value());
    }

    @Override
    public int deleteExpired(OffsetDateTime now) {
        return jdbc.update("DELETE FROM auth_tokens WHERE expires_at <= ?", now);
    }

    private User mapUser(ResultSet rs, int rowNum) throws SQLException {
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
        user.createdAt = rs.getString("created_at");
        user.updatedAt = rs.getString("updated_at");
        return user;
    }
}
