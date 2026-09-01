package com.mamoji.platform.identity.security.infrastructure;

import com.mamoji.platform.identity.security.application.LoginFailureRepository;
import com.mamoji.platform.identity.security.domain.LoginFailureState;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject;
import com.mamoji.platform.identity.security.domain.LoginThrottleSubject.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLoginFailureRepository implements LoginFailureRepository {
    private final JdbcTemplate jdbc;

    public JdbcLoginFailureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LoginFailureState lockOrCreate(LoginThrottleSubject subject, OffsetDateTime now) {
        jdbc.update("""
            INSERT INTO login_failure_states (
                subject_key, subject_type, failed_attempts, window_started_at, locked_until, updated_at
            ) VALUES (?, ?, 0, ?, NULL, ?)
            ON CONFLICT (subject_key) DO NOTHING
            """, subject.keyHash(), subject.type().databaseValue(), now, now);
        return jdbc.query("""
            SELECT subject_key, subject_type, failed_attempts, window_started_at, locked_until, updated_at
            FROM login_failure_states
            WHERE subject_key = ?
            FOR UPDATE
            """, this::map, subject.keyHash()).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Login failure state disappeared while locked"));
    }

    @Override
    public void update(LoginFailureState state) {
        int updated = jdbc.update("""
            UPDATE login_failure_states
            SET failed_attempts = ?, window_started_at = ?, locked_until = ?, updated_at = ?
            WHERE subject_key = ? AND subject_type = ?
            """,
            state.failedAttempts(),
            state.windowStartedAt(),
            state.lockedUntil(),
            state.updatedAt(),
            state.subject().keyHash(),
            state.subject().type().databaseValue()
        );
        if (updated != 1) {
            throw new IllegalStateException("Login failure state changed while locked");
        }
    }

    @Override
    public Optional<OffsetDateTime> findLatestActiveLock(
        Collection<LoginThrottleSubject> subjects,
        OffsetDateTime now
    ) {
        List<String> keys = subjects.stream().map(LoginThrottleSubject::keyHash).distinct().toList();
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = String.join(", ", keys.stream().map(ignored -> "?").toList());
        List<Object> arguments = new ArrayList<>(keys);
        arguments.add(now);
        return jdbc.query(
            "SELECT MAX(locked_until) AS locked_until FROM login_failure_states"
                + " WHERE subject_key IN (" + placeholders + ") AND locked_until > ?",
            (rs, rowNum) -> rs.getObject("locked_until", OffsetDateTime.class),
            arguments.toArray()
        ).stream().filter(Objects::nonNull).findFirst();
    }

    @Override
    public void delete(LoginThrottleSubject subject) {
        jdbc.update(
            "DELETE FROM login_failure_states WHERE subject_key = ? AND subject_type = ?",
            subject.keyHash(),
            subject.type().databaseValue()
        );
    }

    @Override
    public int deleteInactiveBefore(OffsetDateTime updatedBefore, OffsetDateTime now) {
        return jdbc.update("""
            DELETE FROM login_failure_states
            WHERE updated_at < ? AND (locked_until IS NULL OR locked_until <= ?)
            """, updatedBefore, now);
    }

    private LoginFailureState map(ResultSet rs, int rowNum) throws SQLException {
        LoginThrottleSubject subject = new LoginThrottleSubject(
            Type.fromDatabase(rs.getString("subject_type")),
            rs.getString("subject_key")
        );
        return new LoginFailureState(
            subject,
            rs.getInt("failed_attempts"),
            rs.getObject("window_started_at", OffsetDateTime.class),
            rs.getObject("locked_until", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
