package com.mamoji.service;

import com.mamoji.domain.Models.Account;
import com.mamoji.domain.Models.User;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.textOr;

@Service
public class AccountReconciliationService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    private final AccountingService accountingService;

    public AccountReconciliationService(
        JdbcTemplate jdbc,
        AccessControlService accessControl,
        AccountingService accountingService
    ) {
        this.jdbc = jdbc;
        this.accessControl = accessControl;
        this.accountingService = accountingService;
    }

    public List<ReconciliationRecord> list(String authorization, long accountId, Long companyId) {
        Account account = accountingService.getAccount(authorization, accountId, companyId);
        return jdbc.query("""
            SELECT * FROM account_reconciliations
            WHERE account_id = ? AND company_id = ? AND user_id = ?
            ORDER BY statement_date DESC, id DESC
            LIMIT 50
            """, this::mapRecord, account.id, account.companyId, account.userId);
    }

    @Transactional
    public ReconciliationRecord create(
        String authorization,
        long accountId,
        Long companyId,
        Map<String, Object> body
    ) {
        User operator = accessControl.requireUser(authorization);
        Account account = accountingService.getAccount(authorization, accountId, companyId);
        String statementDate = validDate(textOr(body.get("statementDate"), LocalDate.now().toString()));
        BigDecimal statementBalance = number(body.get("statementBalance"), account.balance).setScale(2, RoundingMode.HALF_UP);
        BigDecimal systemBalance = account.balance.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = statementBalance.subtract(systemBalance).setScale(2, RoundingMode.HALF_UP);
        String status = difference.abs().compareTo(TOLERANCE) <= 0 ? "reconciled" : "exception";
        String now = com.mamoji.repository.InMemoryStore.now();
        ReconciliationRecord record = jdbc.queryForObject("""
            INSERT INTO account_reconciliations (
                company_id, user_id, account_id, statement_date, statement_balance, system_balance,
                difference, status, note, created_by, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *
            """, this::mapRecord, account.companyId, account.userId, account.id, statementDate,
            statementBalance.toPlainString(), systemBalance.toPlainString(), difference.toPlainString(), status,
            nullableText(body.get("note")), operator.id, now);

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("reconciliationStatus", status);
        if ("reconciled".equals(status)) update.put("lastReconciledAt", statementDate);
        accountingService.updateAccount(authorization, account.id, account.companyId, update);
        return record;
    }

    private String validDate(String value) {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.isAfter(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate must not be in the future");
            }
            return date.toString();
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "statementDate must use yyyy-MM-dd format");
        }
    }

    private ReconciliationRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new ReconciliationRecord(
            rs.getLong("id"),
            rs.getLong("company_id"),
            rs.getLong("account_id"),
            rs.getString("statement_date"),
            new BigDecimal(rs.getString("statement_balance")),
            new BigDecimal(rs.getString("system_balance")),
            new BigDecimal(rs.getString("difference")),
            rs.getString("status"),
            rs.getString("note"),
            rs.getLong("created_by"),
            rs.getString("created_at")
        );
    }

    public record ReconciliationRecord(
        long id,
        long companyId,
        long accountId,
        String statementDate,
        BigDecimal statementBalance,
        BigDecimal systemBalance,
        BigDecimal difference,
        String status,
        String note,
        long createdBy,
        String createdAt
    ) {}
}
