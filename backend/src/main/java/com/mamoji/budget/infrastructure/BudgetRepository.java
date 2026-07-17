package com.mamoji.budget.infrastructure;

import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.domain.Models.Budget;
import com.mamoji.domain.Models.TransactionRecord;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BudgetRepository {
    private static final String PROJECTED_SELECT = """
        SELECT b.*, c.name AS category_name, c.icon AS category_icon,
               COALESCE(SUM(
                   CASE
                       WHEN t.type = 2 THEN CAST(t.amount AS NUMERIC)
                       WHEN t.type = 3 THEN -CAST(t.amount AS NUMERIC)
                       ELSE 0
                   END
               ), 0) AS computed_spent
        FROM budgets b
        LEFT JOIN categories c ON c.id = b.category_id
        LEFT JOIN transactions t
          ON t.company_id = b.company_id
         AND t.type IN (2, 3)
         AND (b.ledger_id IS NULL OR t.family_id = b.ledger_id)
         AND (b.category_id IS NULL OR t.category_id = b.category_id)
         AND ((t.type = 3 AND t.budget_id = b.id) OR t.date BETWEEN b.start_date AND b.end_date)
        """;

    private final JdbcTemplate jdbc;
    private final BudgetPolicy policy;

    public BudgetRepository(JdbcTemplate jdbc, BudgetPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    public List<Budget> findByCompany(long companyId) {
        return jdbc.query(
            PROJECTED_SELECT + " WHERE b.company_id = ? GROUP BY b.id, c.name, c.icon ORDER BY b.id",
            this::mapProjected,
            companyId
        );
    }

    public Optional<Budget> findById(long companyId, long id) {
        return jdbc.query(
            PROJECTED_SELECT + " WHERE b.company_id = ? AND b.id = ? GROUP BY b.id, c.name, c.icon",
            this::mapProjected,
            companyId,
            id
        ).stream().findFirst();
    }

    public Optional<Budget> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM budgets WHERE id = ? FOR UPDATE", this::mapBase, id).stream().findFirst();
    }

    public Budget insert(Budget budget) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO budgets (
                    name, amount, start_date, end_date, warning_threshold, status, spent,
                    remaining_amount, usage_rate, warning_reached, risk_level, risk_message,
                    user_id, ledger_id, category_id, created_at, updated_at, company_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] {"id"});
            bind(statement, budget, false);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Budget insert did not return an id");
        budget.id = key.longValue();
        return budget;
    }

    public void update(Budget budget) {
        Object[] arguments = Arrays.copyOf(statementArguments(budget, false), 20);
        arguments[18] = budget.id;
        arguments[19] = budget.version;
        int updated = jdbc.update("""
            UPDATE budgets SET
                name = ?, amount = ?, start_date = ?, end_date = ?, warning_threshold = ?, status = ?,
                spent = ?, remaining_amount = ?, usage_rate = ?, warning_reached = ?, risk_level = ?,
                risk_message = ?, user_id = ?, ledger_id = ?, category_id = ?, created_at = ?, updated_at = ?,
                company_id = ?, version = version + 1
            WHERE id = ? AND version = ?
            """, arguments);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Budget was changed by another request: " + budget.id);
        }
        budget.version++;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM budgets WHERE id = ?", id);
    }

    public boolean hasTransactions(long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE budget_id = ?", Long.class, id);
        return count != null && count > 0;
    }

    public Optional<CategoryRef> category(long id) {
        return jdbc.query(
            "SELECT id, company_id, type FROM categories WHERE id = ?",
            (rs, rowNum) -> new CategoryRef(rs.getLong("id"), nullableLong(rs, "company_id"), rs.getString("type")),
            id
        ).stream().findFirst();
    }

    public Optional<Long> matchingBudgetId(TransactionRecord transaction) {
        return jdbc.query("""
            SELECT id
            FROM budgets
            WHERE company_id = ?
              AND status <> 0
              AND (ledger_id IS NULL OR ledger_id = ?)
              AND (category_id IS NULL OR category_id = ?)
              AND ? BETWEEN start_date AND end_date
            ORDER BY CASE WHEN category_id = ? THEN 0 ELSE 1 END, id
            LIMIT 1
            """, (rs, rowNum) -> rs.getLong("id"), transaction.companyId, transaction.familyId,
            transaction.categoryId, transaction.date, transaction.categoryId).stream().findFirst();
    }

    public void persistProjection(Budget budget) {
        jdbc.update("""
            UPDATE budgets
            SET spent = ?, remaining_amount = ?, usage_rate = ?, warning_reached = ?,
                risk_level = ?, risk_message = ?, status = ?, updated_at = ?
            WHERE id = ?
            """, money(budget.spent), money(budget.remainingAmount), budget.usageRate,
            budget.warningReached ? 1 : 0, budget.riskLevel, budget.riskMessage,
            budget.status, budget.updatedAt, budget.id);
    }

    private Budget mapProjected(ResultSet rs, int rowNum) throws SQLException {
        Budget budget = mapBase(rs, rowNum);
        budget.spent = rs.getBigDecimal("computed_spent");
        budget.categoryName = rs.getString("category_name");
        budget.categoryIcon = rs.getString("category_icon");
        return policy.apply(budget);
    }

    private Budget mapBase(ResultSet rs, int rowNum) throws SQLException {
        Budget budget = new Budget();
        budget.id = rs.getLong("id");
        budget.version = rs.getLong("version");
        budget.companyId = nullableLong(rs, "company_id");
        budget.name = rs.getString("name");
        budget.amount = moneyValue(rs.getString("amount"));
        budget.startDate = rs.getString("start_date");
        budget.endDate = rs.getString("end_date");
        budget.warningThreshold = rs.getInt("warning_threshold");
        budget.status = rs.getInt("status");
        budget.spent = moneyValue(rs.getString("spent"));
        budget.remainingAmount = moneyValue(rs.getString("remaining_amount"));
        budget.usageRate = rs.getDouble("usage_rate");
        budget.warningReached = rs.getInt("warning_reached") == 1;
        budget.riskLevel = rs.getString("risk_level");
        budget.riskMessage = rs.getString("risk_message");
        budget.userId = rs.getLong("user_id");
        budget.ledgerId = nullableLong(rs, "ledger_id");
        budget.categoryId = nullableLong(rs, "category_id");
        budget.createdAt = rs.getString("created_at");
        budget.updatedAt = rs.getString("updated_at");
        return budget;
    }

    private void bind(PreparedStatement statement, Budget budget, boolean includeId) throws SQLException {
        Object[] values = statementArguments(budget, includeId);
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private Object[] statementArguments(Budget budget, boolean includeId) {
        Object[] values = new Object[includeId ? 19 : 18];
        int index = 0;
        values[index++] = budget.name;
        values[index++] = money(budget.amount);
        values[index++] = budget.startDate;
        values[index++] = budget.endDate;
        values[index++] = budget.warningThreshold;
        values[index++] = budget.status;
        values[index++] = money(budget.spent);
        values[index++] = money(budget.remainingAmount);
        values[index++] = budget.usageRate;
        values[index++] = budget.warningReached ? 1 : 0;
        values[index++] = budget.riskLevel;
        values[index++] = budget.riskMessage;
        values[index++] = budget.userId;
        values[index++] = budget.ledgerId;
        values[index++] = budget.categoryId;
        values[index++] = budget.createdAt;
        values[index++] = budget.updatedAt;
        values[index++] = budget.companyId;
        if (includeId) values[index] = budget.id;
        return values;
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private BigDecimal moneyValue(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record CategoryRef(long id, Long companyId, String type) {
    }
}
