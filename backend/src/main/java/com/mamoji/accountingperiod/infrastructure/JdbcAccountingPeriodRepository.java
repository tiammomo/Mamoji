package com.mamoji.accountingperiod.infrastructure;

import com.mamoji.accountingperiod.application.AccountingPeriodRepository;
import com.mamoji.accountingperiod.domain.AccountingPeriodControl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountingPeriodRepository implements AccountingPeriodRepository {
    private static final String COLUMNS = """
        company_id, version, closed_through, last_action, last_action_at,
        last_action_by, last_action_reason, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcAccountingPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountingPeriodControl> findByCompany(long companyId) {
        return find(companyId, false);
    }

    @Override
    public Optional<AccountingPeriodControl> findByCompanyForUpdate(long companyId) {
        return find(companyId, true);
    }

    @Override
    public AccountingPeriodControl update(AccountingPeriodControl control) {
        OffsetDateTime updatedAt = OffsetDateTime.now();
        int updated = jdbc.update("""
            UPDATE accounting_period_controls
            SET version = version + 1,
                closed_through = ?,
                last_action = ?,
                last_action_at = ?,
                last_action_by = ?,
                last_action_reason = ?,
                updated_at = ?
            WHERE company_id = ? AND version = ?
            """,
            control.closedThrough(),
            control.lastAction(),
            control.lastActionAt(),
            control.lastActionBy(),
            control.lastActionReason(),
            updatedAt,
            control.companyId(),
            control.version()
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                "Accounting period control was changed by another request: " + control.companyId()
            );
        }
        return new AccountingPeriodControl(
            control.companyId(),
            control.version() + 1,
            control.closedThrough(),
            control.lastAction(),
            control.lastActionAt(),
            control.lastActionBy(),
            control.lastActionReason(),
            control.createdAt(),
            updatedAt
        );
    }

    private Optional<AccountingPeriodControl> find(long companyId, boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM accounting_period_controls WHERE company_id = ?" + suffix,
            this::map,
            companyId
        ).stream().findFirst();
    }

    private AccountingPeriodControl map(ResultSet result, int rowNumber) throws SQLException {
        return new AccountingPeriodControl(
            result.getLong("company_id"),
            result.getLong("version"),
            result.getObject("closed_through", LocalDate.class),
            result.getString("last_action"),
            result.getObject("last_action_at", OffsetDateTime.class),
            nullableLong(result, "last_action_by"),
            result.getString("last_action_reason"),
            result.getObject("created_at", OffsetDateTime.class),
            result.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
