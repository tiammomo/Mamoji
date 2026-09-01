package com.mamoji.budget.infrastructure;

import com.mamoji.budget.application.BudgetRepository;
import com.mamoji.budget.application.BudgetRepository.CategoryRef;
import com.mamoji.budget.domain.Budget;
import com.mamoji.budget.domain.BudgetPolicy;
import com.mamoji.operations.domain.TransactionRecord;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBudgetRepository implements BudgetRepository {
    private static final String PROJECTED_SELECT = """
        SELECT b.*, c.name AS category_name, c.icon AS category_icon,
               COALESCE(SUM(
                   CASE
                       WHEN t.type = 2 THEN t.amount
                       WHEN t.type = 3 THEN -t.amount
                       ELSE 0
                   END
               ), 0) AS computed_spent,
               COALESCE((
                   SELECT SUM(reservation.amount)
                   FROM budget_reservations reservation
                   WHERE reservation.budget_id = b.id AND reservation.status = 'reserved'
               ), 0) AS computed_reserved
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

    public JdbcBudgetRepository(JdbcTemplate jdbc, BudgetPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    @Override
    public List<Budget> findByCompany(long companyId) {
        return jdbc.query(
            PROJECTED_SELECT + " WHERE b.company_id = ? GROUP BY b.id, c.name, c.icon ORDER BY b.id",
            this::mapProjected,
            companyId
        );
    }

    @Override
    public Optional<Budget> findById(long companyId, long id) {
        return jdbc.query(
            PROJECTED_SELECT + " WHERE b.company_id = ? AND b.id = ? GROUP BY b.id, c.name, c.icon",
            this::mapProjected,
            companyId,
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<Budget> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM budgets WHERE id = ? FOR UPDATE", this::mapBase, id).stream().findFirst();
    }

    @Override
    public Budget insert(Budget budget) {
        budget.version = 0;
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

    @Override
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

    @Override
    public void delete(long id) {
        int deleted = jdbc.update("DELETE FROM budgets WHERE id = ?", id);
        if (deleted != 1) {
            throw new OptimisticLockingFailureException("Budget was changed by another request: " + id);
        }
    }

    @Override
    public boolean hasTransactions(long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE budget_id = ?", Long.class, id);
        return count != null && count > 0;
    }

    @Override
    public Optional<CategoryRef> category(long id) {
        return jdbc.query(
            "SELECT id, company_id, type FROM categories WHERE id = ?",
            (rs, rowNum) -> new CategoryRef(rs.getLong("id"), nullableLong(rs, "company_id"), rs.getString("type")),
            id
        ).stream().findFirst();
    }

    @Override
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
            transaction.categoryId, LocalDate.parse(transaction.date), transaction.categoryId).stream().findFirst();
    }

    @Override
    public void persistProjection(Budget budget) {
        int updated = jdbc.update("""
            UPDATE budgets
            SET spent = ?, remaining_amount = ?, usage_rate = ?, warning_reached = ?,
                risk_level = ?, risk_message = ?, status = ?, updated_at = ?, version = version + 1
            WHERE id = ? AND version = ?
              AND (
                  spent IS DISTINCT FROM ?
                  OR remaining_amount IS DISTINCT FROM ?
                  OR usage_rate IS DISTINCT FROM ?
                  OR warning_reached IS DISTINCT FROM ?
                  OR risk_level IS DISTINCT FROM ?
                  OR risk_message IS DISTINCT FROM ?
                  OR status IS DISTINCT FROM ?
              )
            """, money(budget.spent), money(budget.remainingAmount), budget.usageRate,
            budget.warningReached, budget.riskLevel, budget.riskMessage,
            budget.status, timestamp(budget.updatedAt), budget.id, budget.version,
            money(budget.spent), money(budget.remainingAmount), budget.usageRate,
            budget.warningReached, budget.riskLevel, budget.riskMessage, budget.status);
        if (updated == 1) {
            budget.version++;
            return;
        }
        Long storedVersion = jdbc.queryForObject(
            "SELECT version FROM budgets WHERE id = ?",
            Long.class,
            budget.id
        );
        if (storedVersion == null || storedVersion != budget.version) {
            throw new OptimisticLockingFailureException("Budget was changed by another request: " + budget.id);
        }
    }

    private Budget mapProjected(ResultSet rs, int rowNum) throws SQLException {
        Budget budget = mapBase(rs, rowNum);
        budget.spent = rs.getBigDecimal("computed_spent");
        budget.reservedAmount = rs.getBigDecimal("computed_reserved");
        budget.categoryName = rs.getString("category_name");
        budget.categoryIcon = rs.getString("category_icon");
        return policy.apply(budget);
    }

    private Budget mapBase(ResultSet rs, int rowNum) throws SQLException {
        Budget budget = new Budget();
        budget.id = rs.getLong("id");
        budget.version = rs.getLong("version");
        budget.companyId = rs.getLong("company_id");
        budget.name = rs.getString("name");
        budget.amount = rs.getBigDecimal("amount");
        budget.startDate = dateText(rs, "start_date");
        budget.endDate = dateText(rs, "end_date");
        budget.warningThreshold = rs.getInt("warning_threshold");
        budget.status = rs.getInt("status");
        budget.spent = rs.getBigDecimal("spent");
        budget.remainingAmount = rs.getBigDecimal("remaining_amount");
        budget.usageRate = rs.getDouble("usage_rate");
        budget.warningReached = rs.getBoolean("warning_reached");
        budget.riskLevel = rs.getString("risk_level");
        budget.riskMessage = rs.getString("risk_message");
        budget.userId = rs.getLong("user_id");
        budget.ledgerId = nullableLong(rs, "ledger_id");
        budget.categoryId = nullableLong(rs, "category_id");
        budget.createdAt = timestampText(rs, "created_at");
        budget.updatedAt = timestampText(rs, "updated_at");
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
        values[index++] = date(budget.startDate);
        values[index++] = date(budget.endDate);
        values[index++] = budget.warningThreshold;
        values[index++] = budget.status;
        values[index++] = money(budget.spent);
        values[index++] = money(budget.remainingAmount);
        values[index++] = budget.usageRate;
        values[index++] = budget.warningReached;
        values[index++] = budget.riskLevel;
        values[index++] = budget.riskMessage;
        values[index++] = budget.userId;
        values[index++] = budget.ledgerId;
        values[index++] = budget.categoryId;
        values[index++] = timestamp(budget.createdAt);
        values[index++] = timestamp(budget.updatedAt);
        values[index++] = budget.companyId;
        if (includeId) values[index] = budget.id;
        return values;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    private OffsetDateTime timestamp(String value) {
        return OffsetDateTime.parse(value);
    }

    private String dateText(ResultSet result, String column) throws SQLException {
        return result.getObject(column, LocalDate.class).toString();
    }

    private String timestampText(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toString();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

}
