package com.mamoji.accessmanagement.infrastructure;

import com.mamoji.accessmanagement.application.ManagedUserRepository;
import com.mamoji.accessmanagement.domain.ManagedUser;
import com.mamoji.common.PageRequest;
import com.mamoji.repository.InMemoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcManagedUserRepository implements ManagedUserRepository {
    private static final String COLUMNS = """
        id, email, nickname, avatar, family_id, role, permissions, created_at, updated_at
        """;
    private static final String SEARCH_PREDICATE = """
        POSITION(CAST(? AS TEXT) IN LOWER(email)) > 0
        OR POSITION(CAST(? AS TEXT) IN LOWER(nickname)) > 0
        """;

    private final JdbcTemplate jdbc;
    private final InMemoryStore compatibilityStore;

    public JdbcManagedUserRepository(JdbcTemplate jdbc, InMemoryStore compatibilityStore) {
        this.jdbc = jdbc;
        this.compatibilityStore = compatibilityStore;
    }

    @Override
    public ManagedUserPage search(String keyword, PageRequest pageRequest) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE " + SEARCH_PREDICATE,
            Long.class,
            normalizedKeyword,
            normalizedKeyword
        );
        long offset = (long) pageRequest.page() * pageRequest.size();
        List<ManagedUser> content = jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE " + SEARCH_PREDICATE + " ORDER BY id LIMIT ? OFFSET ?",
            this::map,
            normalizedKeyword,
            normalizedKeyword,
            pageRequest.size(),
            offset
        );
        return new ManagedUserPage(content, total == null ? 0 : total);
    }

    @Override
    public void lockAccessMutations() {
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
    }

    @Override
    public Optional<ManagedUser> findForAccessMutation(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM users WHERE id = ? FOR UPDATE",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public long countUsers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countAdministrators() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 1", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public ManagedUser updateAccess(ManagedUser user) {
        int updated = jdbc.update(
            "UPDATE users SET role = ?, permissions = ?, updated_at = ? WHERE id = ?",
            user.role(),
            user.permissions(),
            user.updatedAt(),
            user.id()
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Managed user changed during access update: " + user.id());
        }
        compatibilityStore.synchronizeUserAccessAfterCommit(
            user.id(), user.role(), user.permissions(), user.updatedAt()
        );
        return user;
    }

    @Override
    public void delete(long id) {
        final int deleted;
        try {
            deleted = jdbc.update("DELETE FROM users WHERE id = ?", id);
        } catch (DataIntegrityViolationException ex) {
            throw new UserDeletionConflictException(ex);
        }
        if (deleted != 1) {
            throw new OptimisticLockingFailureException("Managed user changed during deletion: " + id);
        }
        compatibilityStore.removeUserFromCompatibilityViewAfterCommit(id);
    }

    private ManagedUser map(ResultSet rs, int rowNum) throws SQLException {
        long familyId = rs.getLong("family_id");
        return new ManagedUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("nickname"),
            rs.getString("avatar"),
            rs.wasNull() ? null : familyId,
            rs.getInt("role"),
            rs.getInt("permissions"),
            rs.getString("created_at"),
            rs.getString("updated_at")
        );
    }
}
