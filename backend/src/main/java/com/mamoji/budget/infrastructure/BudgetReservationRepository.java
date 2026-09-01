package com.mamoji.budget.infrastructure;

import com.mamoji.budget.domain.BudgetCapacity;
import com.mamoji.budget.domain.BudgetReservation;
import com.mamoji.budget.domain.BudgetReservationCommand;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BudgetReservationRepository {
    private final JdbcTemplate jdbc;

    public BudgetReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BudgetReservation> reserveMatching(BudgetReservationCommand command) {
        validate(command);
        Optional<BudgetCandidate> candidate = jdbc.query("""
            SELECT id, CAST(amount AS NUMERIC) AS amount, ledger_id, category_id, start_date, end_date
            FROM budgets
            WHERE company_id = ?
              AND status IN (1, 3)
              AND (ledger_id IS NULL OR ledger_id = ?)
              AND (category_id IS NULL OR category_id = ?)
              AND ? BETWEEN start_date AND end_date
            ORDER BY CASE WHEN category_id = ? THEN 0 ELSE 1 END,
                     CASE WHEN ledger_id = ? THEN 0 ELSE 1 END,
                     id
            LIMIT 1
            FOR UPDATE
            """, (rs, rowNum) -> new BudgetCandidate(
                rs.getLong("id"),
                rs.getBigDecimal("amount"),
                nullableLong(rs, "ledger_id"),
                nullableLong(rs, "category_id"),
                rs.getString("start_date"),
                rs.getString("end_date")
            ),
            command.companyId(), command.ledgerId(), command.categoryId(), command.transactionDate(),
            command.categoryId(), command.ledgerId()).stream().findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        BudgetCandidate budget = candidate.get();
        BigDecimal committed = jdbc.queryForObject("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN type = 2 THEN CAST(amount AS NUMERIC)
                    WHEN type = 3 THEN -CAST(amount AS NUMERIC)
                    ELSE 0
                END
            ), 0)
            FROM transactions
            WHERE company_id = ?
              AND type IN (2, 3)
              AND (CAST(? AS BIGINT) IS NULL OR family_id = ?)
              AND (CAST(? AS BIGINT) IS NULL OR category_id = ?)
              AND (CAST(? AS BIGINT) IS NULL OR id <> ?)
              AND ((type = 3 AND budget_id = ?) OR (type = 2 AND date BETWEEN ? AND ?))
            """, BigDecimal.class, command.companyId(), budget.ledgerId(), budget.ledgerId(),
            budget.categoryId(), budget.categoryId(), command.replacedTransactionId(), command.replacedTransactionId(),
            budget.id(), budget.startDate(), budget.endDate());
        BigDecimal reserved = jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount), 0)
            FROM budget_reservations
            WHERE budget_id = ? AND status = 'reserved'
            """, BigDecimal.class, budget.id());
        new BudgetCapacity(budget.amount(), nonNegative(committed), nonNegative(reserved)).reserve(command.amount());

        OffsetDateTime now = OffsetDateTime.now();
        return Optional.ofNullable(jdbc.queryForObject("""
            INSERT INTO budget_reservations (
                company_id, budget_id, reference_key, amount, status, created_by_user_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'reserved', ?, ?, ?)
            RETURNING *
            """, this::map, command.companyId(), budget.id(), command.referenceKey(), command.amount(),
            command.userId(), now, now));
    }

    public BudgetReservation confirm(long reservationId, long transactionId) {
        BudgetReservation current = requireForUpdate(reservationId);
        validateConfirmationTarget(current, transactionId);
        return update(current.confirm(transactionId, OffsetDateTime.now()));
    }

    public BudgetReservation release(long reservationId, String reason) {
        BudgetReservation current = requireForUpdate(reservationId);
        return update(current.release(reason, OffsetDateTime.now()));
    }

    public Optional<BudgetReservation> releaseByTransaction(long transactionId, String reason) {
        Optional<BudgetReservation> current = jdbc.query("""
            SELECT * FROM budget_reservations
            WHERE transaction_id = ? AND status = 'confirmed'
            FOR UPDATE
            """, this::map, transactionId).stream().findFirst();
        return current.map(reservation -> update(reservation.release(reason, OffsetDateTime.now())));
    }

    public Optional<BudgetReservation> findById(long id) {
        return jdbc.query("SELECT * FROM budget_reservations WHERE id = ?", this::map, id).stream().findFirst();
    }

    private BudgetReservation requireForUpdate(long id) {
        return jdbc.query("SELECT * FROM budget_reservations WHERE id = ? FOR UPDATE", this::map, id).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Budget reservation not found: " + id));
    }

    private void validateConfirmationTarget(BudgetReservation reservation, long transactionId) {
        Optional<ConfirmationTarget> target = jdbc.query("""
            SELECT company_id, budget_id, type, CAST(amount AS NUMERIC) AS amount
            FROM transactions
            WHERE id = ?
            """, (rs, rowNum) -> new ConfirmationTarget(
                rs.getLong("company_id"),
                nullableLong(rs, "budget_id"),
                rs.getInt("type"),
                rs.getBigDecimal("amount")
            ), transactionId).stream().findFirst();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("Transaction not found for budget confirmation: " + transactionId);
        }
        ConfirmationTarget value = target.get();
        if (value.companyId() != reservation.companyId()
            || !Objects.equals(value.budgetId(), reservation.budgetId())
            || value.type() != 2
            || value.amount().compareTo(reservation.amount()) != 0) {
            throw new IllegalStateException("Transaction does not match budget reservation: " + reservation.id());
        }
    }

    private BudgetReservation update(BudgetReservation reservation) {
        int updated = jdbc.update("""
            UPDATE budget_reservations
            SET transaction_id = ?, source_transaction_id = COALESCE(source_transaction_id, ?),
                status = ?, confirmed_at = ?, released_at = ?, release_reason = ?,
                updated_at = ?, version = version + 1
            WHERE id = ? AND version = ?
            """, reservation.transactionId(), reservation.transactionId(), reservation.status().value(),
            reservation.confirmedAt(), reservation.releasedAt(), reservation.releaseReason(), reservation.updatedAt(),
            reservation.id(), reservation.version());
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                "Budget reservation was changed by another request: " + reservation.id()
            );
        }
        return new BudgetReservation(
            reservation.id(), reservation.version() + 1, reservation.companyId(), reservation.budgetId(),
            reservation.transactionId(), reservation.referenceKey(), reservation.amount(), reservation.status(),
            reservation.createdByUserId(), reservation.confirmedAt(), reservation.releasedAt(),
            reservation.releaseReason(), reservation.createdAt(), reservation.updatedAt()
        );
    }

    private BudgetReservation map(ResultSet rs, int rowNum) throws SQLException {
        long transactionId = rs.getLong("transaction_id");
        return new BudgetReservation(
            rs.getLong("id"),
            rs.getLong("version"),
            rs.getLong("company_id"),
            rs.getLong("budget_id"),
            rs.wasNull() ? null : transactionId,
            rs.getString("reference_key"),
            rs.getBigDecimal("amount"),
            BudgetReservation.Status.fromStored(rs.getString("status")),
            rs.getLong("created_by_user_id"),
            offsetDateTime(rs, "confirmed_at"),
            offsetDateTime(rs, "released_at"),
            rs.getString("release_reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private void validate(BudgetReservationCommand command) {
        if (command.companyId() <= 0 || command.userId() <= 0 || command.categoryId() <= 0) {
            throw new IllegalArgumentException("Company, user, and category are required for a budget reservation");
        }
        if (command.transactionDate() == null) {
            throw new IllegalArgumentException("Transaction date is required for a budget reservation");
        }
        if (command.amount() == null || command.amount().signum() <= 0) {
            throw new IllegalArgumentException("Budget reservation amount must be positive");
        }
        if (command.referenceKey() == null || command.referenceKey().isBlank() || command.referenceKey().length() > 160) {
            throw new IllegalArgumentException("Budget reservation reference key is invalid");
        }
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private record BudgetCandidate(
        long id,
        BigDecimal amount,
        Long ledgerId,
        Long categoryId,
        String startDate,
        String endDate
    ) {
    }

    private record ConfirmationTarget(long companyId, Long budgetId, int type, BigDecimal amount) {
    }
}
