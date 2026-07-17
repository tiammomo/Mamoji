package com.mamoji.workspace.infrastructure;

import com.mamoji.workspace.api.WorkspaceView.RecentTransaction;
import com.mamoji.workspace.api.WorkspaceView.UpcomingItem;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceReadRepository {
    private final JdbcTemplate jdbc;

    public WorkspaceReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OperatingMetrics operatingMetrics(long companyId, LocalDate start, LocalDate end, DataScope scope) {
        return jdbc.queryForObject("""
            SELECT
                COALESCE(SUM(CASE WHEN t.type = 1 THEN CAST(t.amount AS NUMERIC) ELSE 0 END), 0) AS income,
                GREATEST(COALESCE(SUM(CASE
                    WHEN t.type = 2 THEN CAST(t.amount AS NUMERIC)
                    WHEN t.type = 3 THEN -CAST(t.amount AS NUMERIC)
                    ELSE 0 END), 0), 0) AS expense,
                COUNT(*) FILTER (
                    WHERE t.type = 2 AND (CAST(t.amount AS NUMERIC) >= 10000 OR BTRIM(COALESCE(t.note, '')) = '')
                ) AS review_count
            FROM transactions t
            WHERE t.company_id = ? AND t.date BETWEEN ? AND ?
              AND (
                  ? OR t.user_id = ? OR (
                      CAST(? AS BIGINT) IS NOT NULL AND EXISTS (
                          SELECT 1 FROM employees scoped_employee
                          WHERE scoped_employee.company_id = t.company_id
                            AND scoped_employee.user_id = t.user_id
                            AND scoped_employee.department_id = ?
                            AND scoped_employee.status <> 'departed'
                      )
                  )
              )
            """, (rs, rowNum) -> new OperatingMetrics(
                rs.getBigDecimal("income"),
                rs.getBigDecimal("expense"),
                rs.getInt("review_count")
            ), companyId, start.toString(), end.toString(), scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public FinanceMetrics financeMetrics(long companyId) {
        return jdbc.queryForObject("""
            SELECT
                COALESCE(SUM(CASE
                    WHEN status = 1 AND type NOT IN ('debt', 'credit') THEN CAST(available_balance AS NUMERIC)
                    ELSE 0 END), 0) AS available_cash,
                COUNT(*) FILTER (WHERE status = 1 AND reconciliation_status <> 'reconciled') AS reconciliation_issues,
                COUNT(*) FILTER (WHERE status = 1 AND risk_level IN ('high', 'critical')) AS risk_issues
            FROM accounts
            WHERE company_id = ?
            """, (rs, rowNum) -> new FinanceMetrics(
                rs.getBigDecimal("available_cash"),
                rs.getInt("reconciliation_issues") + rs.getInt("risk_issues")
            ), companyId);
    }

    public int evidenceIssueCount(long companyId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM receipt_vouchers
            WHERE company_id = ?
              AND status <> 'archived'
              AND (
                  approval_status IN ('pending', 'rejected')
                  OR accounting_status NOT IN ('completed', 'posted')
                  OR file_name IS NULL
                  OR file_name = ''
              )
            """, Integer.class, companyId);
        return count == null ? 0 : count;
    }

    public int pendingApprovalCount(long companyId, long userId, boolean companyWide) {
        Integer count = companyWide
            ? jdbc.queryForObject("SELECT COUNT(*) FROM approval_requests WHERE company_id = ? AND status = 'pending'", Integer.class, companyId)
            : jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_requests
                WHERE company_id = ? AND status = 'pending'
                  AND (applicant_user_id = ? OR assignee_user_id = ?)
                """, Integer.class, companyId, userId, userId);
        return count == null ? 0 : count;
    }

    public RecurringMetrics recurringMetrics(long companyId, LocalDate today, DataScope scope) {
        LocalDate weekEnd = today.plusDays(7);
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) FILTER (WHERE next_execution < ?) AS overdue_count,
                COUNT(*) FILTER (WHERE next_execution BETWEEN ? AND ?) AS upcoming_count
            FROM recurring_items item
            WHERE item.company_id = ? AND item.status = 1
              AND (
                  ? OR item.user_id = ? OR (
                      CAST(? AS BIGINT) IS NOT NULL AND EXISTS (
                          SELECT 1 FROM employees scoped_employee
                          WHERE scoped_employee.company_id = item.company_id
                            AND scoped_employee.user_id = item.user_id
                            AND scoped_employee.department_id = ?
                            AND scoped_employee.status <> 'departed'
                      )
                  )
              )
            """, (rs, rowNum) -> new RecurringMetrics(rs.getInt("overdue_count"), rs.getInt("upcoming_count")),
            today.toString(), today.toString(), weekEnd.toString(), companyId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public List<RecentTransaction> recentTransactions(long companyId, int limit, DataScope scope) {
        return jdbc.query("""
            SELECT t.id, t.type, t.amount, t.date, t.note,
                   COALESCE(c.name, '未分类') AS category_name,
                   COALESCE(a.name, '未关联账户') AS account_name
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN accounts a ON a.id = t.account_id
            WHERE t.company_id = ?
              AND (
                  ? OR t.user_id = ? OR (
                      CAST(? AS BIGINT) IS NOT NULL AND EXISTS (
                          SELECT 1 FROM employees scoped_employee
                          WHERE scoped_employee.company_id = t.company_id
                            AND scoped_employee.user_id = t.user_id
                            AND scoped_employee.department_id = ?
                            AND scoped_employee.status <> 'departed'
                      )
                  )
              )
            ORDER BY t.date DESC, t.id DESC
            LIMIT ?
            """, this::mapTransaction, companyId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId(), Math.max(1, Math.min(limit, 20)));
    }

    public List<UpcomingItem> upcomingItems(long companyId, LocalDate today, int limit, DataScope scope) {
        return jdbc.query("""
            SELECT id, name, next_execution
            FROM recurring_items item
            WHERE item.company_id = ? AND item.status = 1 AND item.next_execution <= ?
              AND (
                  ? OR item.user_id = ? OR (
                      CAST(? AS BIGINT) IS NOT NULL AND EXISTS (
                          SELECT 1 FROM employees scoped_employee
                          WHERE scoped_employee.company_id = item.company_id
                            AND scoped_employee.user_id = item.user_id
                            AND scoped_employee.department_id = ?
                            AND scoped_employee.status <> 'departed'
                      )
                  )
              )
            ORDER BY item.next_execution, item.id
            LIMIT ?
            """, (rs, rowNum) -> new UpcomingItem(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("next_execution"),
                LocalDate.parse(rs.getString("next_execution")).isBefore(today),
                "/recurring"
            ), companyId, today.plusDays(14).toString(), scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId(), Math.max(1, Math.min(limit, 20)));
    }

    private RecentTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new RecentTransaction(
            rs.getLong("id"),
            rs.getInt("type"),
            money(rs.getString("amount")),
            rs.getString("date"),
            rs.getString("note"),
            rs.getString("category_name"),
            rs.getString("account_name")
        );
    }

    private BigDecimal money(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    public record OperatingMetrics(BigDecimal income, BigDecimal expense, int reviewCount) {
    }

    public record FinanceMetrics(BigDecimal availableCash, int issueCount) {
    }

    public record RecurringMetrics(int overdueCount, int upcomingCount) {
    }

    public record DataScope(long actorUserId, Long departmentId, boolean companyWide) {
    }
}
