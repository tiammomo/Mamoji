package com.mamoji.operations.infrastructure;

import com.mamoji.operations.application.CategoryRepository;
import com.mamoji.operations.domain.Category;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for transaction category persistence and default initialization. */
@Repository
public class JdbcCategoryRepository implements CategoryRepository {
    private final JdbcTemplate jdbc;

    public JdbcCategoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Category> findAll(long userId, long companyId, String type) {
        if (type == null || type.isBlank()) {
            return jdbc.query(
                "SELECT * FROM categories WHERE user_id = ? AND company_id = ? ORDER BY id",
                this::map,
                userId,
                companyId
            );
        }
        return jdbc.query(
            "SELECT * FROM categories WHERE user_id = ? AND company_id = ? AND type = ? ORDER BY id",
            this::map,
            userId,
            companyId,
            type
        );
    }

    @Override
    public Optional<Category> findById(long id) {
        return jdbc.query("SELECT * FROM categories WHERE id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<Category> findForUpdate(long id) {
        return jdbc.query(
            "SELECT * FROM categories WHERE id = ? FOR UPDATE",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public Category insert(Category category) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO categories (
                    name, icon, color, type, user_id, status, created_at, updated_at, company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            statement.setString(1, category.name);
            statement.setString(2, category.icon);
            statement.setString(3, category.color);
            statement.setString(4, category.type);
            statement.setLong(5, category.userId);
            statement.setInt(6, category.status);
            statement.setObject(7, timestamp(category.createdAt));
            statement.setObject(8, timestamp(category.updatedAt));
            statement.setLong(9, category.companyId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated category id");
        category.id = key.longValue();
        return category;
    }

    @Override
    public void update(Category category) {
        int updated = jdbc.update("""
            UPDATE categories
            SET name = ?, icon = ?, color = ?, type = ?, user_id = ?, status = ?,
                created_at = ?, updated_at = ?, company_id = ?
            WHERE id = ?
            """,
            category.name,
            category.icon,
            category.color,
            category.type,
            category.userId,
            category.status,
            timestamp(category.createdAt),
            timestamp(category.updatedAt),
            category.companyId,
            category.id
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Category was changed by another request: " + category.id);
        }
    }

    @Override
    public boolean hasAccountingReferences(long categoryId) {
        Integer count = jdbc.queryForObject("""
            SELECT (
                (SELECT COUNT(*) FROM transactions WHERE category_id = ?)
                + (SELECT COUNT(*) FROM budgets WHERE category_id = ?)
            )
            """, Integer.class, categoryId, categoryId);
        return count != null && count > 0;
    }

    @Override
    public void delete(Category category) {
        int deleted = jdbc.update("DELETE FROM categories WHERE id = ?", category.id);
        if (deleted != 1) {
            throw new OptimisticLockingFailureException("Category was changed by another request: " + category.id);
        }
    }

    @Override
    @Transactional
    public void ensureCompanyDefaults(long ownerId, long companyId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> { },
            "category-defaults:" + companyId
        );
        if (findAll(ownerId, companyId, "income").isEmpty()) {
            insert(defaultCategory(ownerId, companyId, "经营收入", "💼", "#22c55e", "income"));
        }
        if (findAll(ownerId, companyId, "expense").isEmpty()) {
            insert(defaultCategory(ownerId, companyId, "经营支出", "🧾", "#ef4444", "expense"));
        }
    }

    private Category defaultCategory(
        long ownerId,
        long companyId,
        String name,
        String icon,
        String color,
        String type
    ) {
        Category category = new Category();
        category.userId = ownerId;
        category.companyId = companyId;
        category.name = name;
        category.icon = icon;
        category.color = color;
        category.type = type;
        category.status = 1;
        category.createdAt = java.time.OffsetDateTime.now().toString();
        category.updatedAt = category.createdAt;
        return category;
    }

    private Category map(ResultSet rs, int rowNum) throws SQLException {
        Category category = new Category();
        category.id = rs.getLong("id");
        category.companyId = rs.getLong("company_id");
        category.name = rs.getString("name");
        category.icon = rs.getString("icon");
        category.color = rs.getString("color");
        category.type = rs.getString("type");
        category.userId = rs.getLong("user_id");
        category.status = rs.getInt("status");
        category.createdAt = rs.getObject("created_at", OffsetDateTime.class).toString();
        category.updatedAt = rs.getObject("updated_at", OffsetDateTime.class).toString();
        return category;
    }

    private OffsetDateTime timestamp(String value) {
        return value == null ? null : OffsetDateTime.parse(value);
    }
}
