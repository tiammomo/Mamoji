package com.mamoji.platform.identity.invitation.infrastructure;

import com.mamoji.platform.identity.invitation.application.RegistrationInvitationRepository;
import com.mamoji.platform.identity.invitation.domain.InvitationTokenDigest;
import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegistrationInvitationRepository implements RegistrationInvitationRepository {
    private static final String COLUMNS = """
        id, token, email, role, permissions, expires_at, accepted_at,
        accepted_user_id, invited_by_user_id, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcRegistrationInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RegistrationInvitation insert(RegistrationInvitation invitation) {
        Long id = jdbc.queryForObject("""
            INSERT INTO registration_invites (
                token, email, role, permissions, expires_at, accepted_at,
                accepted_user_id, invited_by_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            invitation.tokenDigest().value(),
            invitation.email(),
            invitation.role(),
            invitation.permissions(),
            invitation.expiresAt(),
            invitation.invitedByUserId(),
            invitation.createdAt(),
            invitation.updatedAt()
        );
        if (id == null) {
            throw new IllegalStateException("Invitation insert returned no identity");
        }
        return invitation.withId(id);
    }

    @Override
    public List<RegistrationInvitation> findAll() {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM registration_invites ORDER BY created_at DESC, id DESC",
            this::map
        );
    }

    @Override
    public Optional<RegistrationInvitation> findByTokenForUpdate(InvitationTokenDigest tokenDigest) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM registration_invites WHERE token = ? FOR UPDATE",
            this::map,
            tokenDigest.value()
        ).stream().findFirst();
    }

    @Override
    public void updateAcceptance(RegistrationInvitation acceptedInvitation) {
        int updated = jdbc.update("""
            UPDATE registration_invites
            SET accepted_at = ?, accepted_user_id = ?, updated_at = ?
            WHERE id = ? AND accepted_at IS NULL
            """,
            acceptedInvitation.acceptedAt(),
            acceptedInvitation.acceptedUserId(),
            acceptedInvitation.updatedAt(),
            acceptedInvitation.id()
        );
        if (updated != 1) {
            throw new IllegalStateException("Invitation acceptance did not update exactly one pending row");
        }
    }

    private RegistrationInvitation map(ResultSet rs, int rowNum) throws SQLException {
        return new RegistrationInvitation(
            rs.getLong("id"),
            new InvitationTokenDigest(rs.getString("token")),
            rs.getString("email"),
            rs.getInt("role"),
            rs.getInt("permissions"),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("accepted_at", OffsetDateTime.class),
            nullableLong(rs, "accepted_user_id"),
            nullableLong(rs, "invited_by_user_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
