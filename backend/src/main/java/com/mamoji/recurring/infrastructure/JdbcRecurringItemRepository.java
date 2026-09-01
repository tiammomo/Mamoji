package com.mamoji.recurring.infrastructure;

import com.mamoji.recurring.application.RecurringItemRepository;
import com.mamoji.recurring.domain.RecurringItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for recurring rule persistence and execution locking. */
@Repository
public class JdbcRecurringItemRepository implements RecurringItemRepository {
    private final JdbcTemplate jdbc;

    public JdbcRecurringItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RecurringItem> findByOwnerAndCompany(long userId, long companyId) {
        return jdbc.query(
            "SELECT * FROM recurring_items WHERE user_id = ? AND company_id = ? ORDER BY next_execution, id",
            this::map,
            userId,
            companyId
        );
    }

    @Override
    public Optional<RecurringItem> findByIdForUpdate(String id) {
        return jdbc.query(
            "SELECT * FROM recurring_items WHERE id = ? FOR UPDATE",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public RecurringItem insert(RecurringItem item) {
        int inserted = jdbc.update("""
            INSERT INTO recurring_items (
                id, user_id, company_id, name, type, amount, frequency, interval_value,
                day_of_week, day_of_month, month_of_year, start_date, end_date,
                last_executed, next_execution, status, execution_count, note
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            item.id,
            item.userId,
            item.companyId,
            item.name,
            item.type,
            item.amount,
            item.frequency,
            item.interval,
            item.dayOfWeek,
            item.dayOfMonth,
            item.monthOfYear,
            date(item.startDate),
            date(item.endDate),
            date(item.lastExecuted),
            date(item.nextExecution),
            item.status,
            item.executionCount,
            item.note
        );
        if (inserted != 1) {
            throw new OptimisticLockingFailureException("Recurring item was not inserted: " + item.id);
        }
        return item;
    }

    @Override
    public void update(RecurringItem item) {
        int updated = jdbc.update("""
            UPDATE recurring_items
            SET user_id = ?, company_id = ?, name = ?, type = ?, amount = ?, frequency = ?,
                interval_value = ?, day_of_week = ?, day_of_month = ?, month_of_year = ?,
                start_date = ?, end_date = ?, last_executed = ?, next_execution = ?,
                status = ?, execution_count = ?, note = ?
            WHERE id = ?
            """,
            item.userId,
            item.companyId,
            item.name,
            item.type,
            item.amount,
            item.frequency,
            item.interval,
            item.dayOfWeek,
            item.dayOfMonth,
            item.monthOfYear,
            date(item.startDate),
            date(item.endDate),
            date(item.lastExecuted),
            date(item.nextExecution),
            item.status,
            item.executionCount,
            item.note,
            item.id
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Recurring item was changed by another request: " + item.id);
        }
    }

    @Override
    public void delete(String id) {
        int deleted = jdbc.update("DELETE FROM recurring_items WHERE id = ?", id);
        if (deleted != 1) {
            throw new OptimisticLockingFailureException("Recurring item was changed by another request: " + id);
        }
    }

    private RecurringItem map(ResultSet result, int rowNumber) throws SQLException {
        RecurringItem item = new RecurringItem();
        item.id = result.getString("id");
        item.userId = result.getLong("user_id");
        item.companyId = result.getLong("company_id");
        item.name = result.getString("name");
        item.type = result.getInt("type");
        item.amount = result.getBigDecimal("amount");
        item.frequency = result.getString("frequency");
        item.interval = result.getInt("interval_value");
        item.dayOfWeek = nullableInteger(result, "day_of_week");
        item.dayOfMonth = nullableInteger(result, "day_of_month");
        item.monthOfYear = nullableInteger(result, "month_of_year");
        item.startDate = dateText(result, "start_date");
        item.endDate = dateText(result, "end_date");
        item.lastExecuted = dateText(result, "last_executed");
        item.nextExecution = dateText(result, "next_execution");
        item.status = result.getInt("status");
        item.executionCount = result.getInt("execution_count");
        item.note = result.getString("note");
        return item;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static String dateText(ResultSet result, String column) throws SQLException {
        LocalDate value = result.getObject(column, LocalDate.class);
        return value == null ? null : value.toString();
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
