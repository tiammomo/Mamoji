package com.mamoji.platform.identity.account.infrastructure;

import com.mamoji.platform.identity.account.application.UserDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserDirectory implements UserDirectory {
    private static final String COLUMNS = """
        id, email, nickname, avatar, family_id, role, permissions
        """;

    private final JdbcTemplate jdbc;

    public JdbcUserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Entry> findById(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ?",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<Entry> findBootstrapOwner() {
        return jdbc.query("""
            SELECT %s
            FROM users
            ORDER BY CASE WHEN role = 1 THEN 0 ELSE 1 END, id
            LIMIT 1
            """.formatted(COLUMNS), this::map).stream().findFirst();
    }

    @Override
    public List<Entry> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM users ORDER BY id", this::map);
    }

    private Entry map(ResultSet rs, int rowNum) throws SQLException {
        long familyId = rs.getLong("family_id");
        Long nullableFamilyId = rs.wasNull() ? null : familyId;
        return new Entry(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("nickname"),
            rs.getString("avatar"),
            nullableFamilyId,
            rs.getInt("role"),
            rs.getInt("permissions")
        );
    }
}
