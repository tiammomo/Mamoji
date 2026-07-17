package com.mamoji.workforce.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkforceCostReadRepository {
    private final JdbcTemplate jdbc;

    public WorkforceCostReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PayrollRunRef> payrollRun(long companyId, String period) {
        return jdbc.query("""
            SELECT id, status
            FROM payroll_runs
            WHERE company_id = ? AND period = ?
            ORDER BY id DESC
            LIMIT 1
            """, (rs, rowNum) -> new PayrollRunRef(rs.getLong("id"), rs.getString("status")), companyId, period)
            .stream()
            .findFirst();
    }

    public CostAggregate payrollCost(long companyId, long runId, DataScope scope) {
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) AS employee_count,
                COALESCE(SUM(CAST(NULLIF(item.salary, '') AS NUMERIC)), 0) AS salary,
                COALESCE(SUM(GREATEST(
                    CAST(NULLIF(item.payable_salary, '') AS NUMERIC) - CAST(NULLIF(item.salary, '') AS NUMERIC), 0
                )), 0) AS overtime,
                COALESCE(SUM(CAST(NULLIF(item.social_company_amount, '') AS NUMERIC)), 0) AS employer_social,
                COALESCE(SUM(CAST(NULLIF(item.housing_company_amount, '') AS NUMERIC)), 0) AS employer_housing,
                COALESCE(SUM(CAST(NULLIF(item.company_cost, '') AS NUMERIC)), 0) AS total
            FROM payroll_run_items item
            LEFT JOIN employees employee
              ON employee.id = item.employee_id AND employee.company_id = item.company_id
            WHERE item.company_id = ? AND item.run_id = ?
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            """, this::mapCost, companyId, runId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public CostAggregate estimatedCost(long companyId, DataScope scope) {
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) AS employee_count,
                COALESCE(SUM(CAST(NULLIF(employee.salary, '') AS NUMERIC)), 0) AS salary,
                COALESCE(SUM(CAST(NULLIF(employee.overtime_pay, '') AS NUMERIC)), 0) AS overtime,
                COALESCE(SUM(CAST(NULLIF(employee.social_insurance_company_amount, '') AS NUMERIC)), 0) AS employer_social,
                COALESCE(SUM(CAST(NULLIF(employee.housing_fund_company_amount, '') AS NUMERIC)), 0) AS employer_housing,
                COALESCE(SUM(CAST(NULLIF(employee.monthly_cost, '') AS NUMERIC)), 0) AS total
            FROM employees employee
            WHERE employee.company_id = ? AND employee.status IN ('active', 'probation')
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            """, this::mapCost, companyId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public HeadcountAggregate headcount(
        long companyId,
        LocalDate periodStart,
        LocalDate periodEnd,
        DataScope scope
    ) {
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) FILTER (WHERE employee.status = 'active') AS active_count,
                COUNT(*) FILTER (WHERE employee.status = 'probation') AS probation_count,
                COUNT(*) FILTER (WHERE employee.status = 'onboarding') AS onboarding_count,
                COUNT(*) FILTER (
                    WHERE employee.leave_date IS NOT NULL AND employee.leave_date <> ''
                      AND employee.leave_date BETWEEN ? AND ?
                ) AS departed_count
            FROM employees employee
            WHERE employee.company_id = ?
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            """, (rs, rowNum) -> new HeadcountAggregate(
                rs.getInt("active_count"),
                rs.getInt("probation_count"),
                rs.getInt("onboarding_count"),
                rs.getInt("departed_count")
            ), periodStart.toString(), periodEnd.toString(), companyId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public List<DepartmentAggregate> payrollDepartments(long companyId, long runId, DataScope scope) {
        return jdbc.query("""
            SELECT
                employee.department_id,
                COALESCE(NULLIF(department.name, ''), NULLIF(item.department_name, ''), '未分配部门') AS department_name,
                COALESCE(CAST(NULLIF(department.budget, '') AS NUMERIC), 0) AS budget,
                COUNT(*) AS employee_count,
                COALESCE(SUM(CAST(NULLIF(item.salary, '') AS NUMERIC)), 0) AS salary,
                COALESCE(SUM(GREATEST(
                    CAST(NULLIF(item.payable_salary, '') AS NUMERIC) - CAST(NULLIF(item.salary, '') AS NUMERIC), 0
                )), 0) AS overtime,
                COALESCE(SUM(CAST(NULLIF(item.social_company_amount, '') AS NUMERIC)), 0) AS employer_social,
                COALESCE(SUM(CAST(NULLIF(item.housing_company_amount, '') AS NUMERIC)), 0) AS employer_housing,
                COALESCE(SUM(CAST(NULLIF(item.company_cost, '') AS NUMERIC)), 0) AS total
            FROM payroll_run_items item
            LEFT JOIN employees employee
              ON employee.id = item.employee_id AND employee.company_id = item.company_id
            LEFT JOIN departments department
              ON department.id = employee.department_id AND department.company_id = item.company_id
            WHERE item.company_id = ? AND item.run_id = ?
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            GROUP BY employee.department_id, department.name, item.department_name, department.budget
            ORDER BY total DESC, department_name
            """, this::mapDepartment, companyId, runId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public List<DepartmentAggregate> estimatedDepartments(long companyId, DataScope scope) {
        return jdbc.query("""
            SELECT
                employee.department_id,
                COALESCE(NULLIF(department.name, ''), '未分配部门') AS department_name,
                COALESCE(CAST(NULLIF(department.budget, '') AS NUMERIC), 0) AS budget,
                COUNT(*) AS employee_count,
                COALESCE(SUM(CAST(NULLIF(employee.salary, '') AS NUMERIC)), 0) AS salary,
                COALESCE(SUM(CAST(NULLIF(employee.overtime_pay, '') AS NUMERIC)), 0) AS overtime,
                COALESCE(SUM(CAST(NULLIF(employee.social_insurance_company_amount, '') AS NUMERIC)), 0) AS employer_social,
                COALESCE(SUM(CAST(NULLIF(employee.housing_fund_company_amount, '') AS NUMERIC)), 0) AS employer_housing,
                COALESCE(SUM(CAST(NULLIF(employee.monthly_cost, '') AS NUMERIC)), 0) AS total
            FROM employees employee
            LEFT JOIN departments department
              ON department.id = employee.department_id AND department.company_id = employee.company_id
            WHERE employee.company_id = ? AND employee.status IN ('active', 'probation')
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            GROUP BY employee.department_id, department.name, department.budget
            ORDER BY total DESC, department_name
            """, this::mapDepartment, companyId, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
    }

    public BigDecimal operatingExpense(
        long companyId,
        LocalDate periodStart,
        LocalDate periodEnd,
        DataScope scope
    ) {
        BigDecimal result = jdbc.queryForObject("""
            SELECT GREATEST(COALESCE(SUM(CASE
                WHEN transaction_record.type = 2 THEN CAST(NULLIF(transaction_record.amount, '') AS NUMERIC)
                WHEN transaction_record.type = 3 THEN -CAST(NULLIF(transaction_record.amount, '') AS NUMERIC)
                ELSE 0 END), 0), 0)
            FROM transactions transaction_record
            WHERE transaction_record.company_id = ? AND transaction_record.date BETWEEN ? AND ?
              AND (? OR transaction_record.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND EXISTS (
                      SELECT 1 FROM employees scoped_employee
                      WHERE scoped_employee.company_id = transaction_record.company_id
                        AND scoped_employee.user_id = transaction_record.user_id
                        AND scoped_employee.department_id = ?
                  )
              ))
            """, BigDecimal.class, companyId, periodStart.toString(), periodEnd.toString(),
            scope.companyWide(), scope.actorUserId(), scope.departmentId(), scope.departmentId());
        return result == null ? BigDecimal.ZERO : result;
    }

    public List<TrendAggregate> payrollTrend(long companyId, String throughPeriod, DataScope scope) {
        List<TrendAggregate> points = jdbc.query("""
            SELECT
                payroll.id AS run_id,
                payroll.period,
                payroll.status,
                COUNT(*) AS employee_count,
                COALESCE(SUM(CAST(NULLIF(item.company_cost, '') AS NUMERIC)), 0) AS total
            FROM payroll_runs payroll
            JOIN payroll_run_items item ON item.run_id = payroll.id AND item.company_id = payroll.company_id
            LEFT JOIN employees employee
              ON employee.id = item.employee_id AND employee.company_id = item.company_id
            WHERE payroll.company_id = ? AND payroll.period <= ?
              AND (? OR employee.user_id = ? OR (
                  CAST(? AS BIGINT) IS NOT NULL AND employee.department_id = ?
              ))
            GROUP BY payroll.id, payroll.period, payroll.status
            ORDER BY payroll.period DESC, payroll.id DESC
            LIMIT 6
            """, (rs, rowNum) -> new TrendAggregate(
                rs.getLong("run_id"),
                rs.getString("period"),
                rs.getString("status"),
                rs.getInt("employee_count"),
                rs.getBigDecimal("total")
            ), companyId, throughPeriod, scope.companyWide(), scope.actorUserId(),
            scope.departmentId(), scope.departmentId());
        Collections.reverse(points);
        return points;
    }

    private CostAggregate mapCost(ResultSet rs, int rowNum) throws SQLException {
        return new CostAggregate(
            rs.getInt("employee_count"),
            rs.getBigDecimal("salary"),
            rs.getBigDecimal("overtime"),
            rs.getBigDecimal("employer_social"),
            rs.getBigDecimal("employer_housing"),
            rs.getBigDecimal("total")
        );
    }

    private DepartmentAggregate mapDepartment(ResultSet rs, int rowNum) throws SQLException {
        Number departmentId = (Number) rs.getObject("department_id");
        return new DepartmentAggregate(
            departmentId == null ? null : departmentId.longValue(),
            rs.getString("department_name"),
            rs.getBigDecimal("budget"),
            mapCost(rs, rowNum)
        );
    }

    public record PayrollRunRef(long id, String status) {
    }

    public record CostAggregate(
        int employeeCount,
        BigDecimal salary,
        BigDecimal overtime,
        BigDecimal employerSocial,
        BigDecimal employerHousing,
        BigDecimal total
    ) {
    }

    public record DepartmentAggregate(Long departmentId, String departmentName, BigDecimal budget, CostAggregate cost) {
    }

    public record HeadcountAggregate(int active, int probation, int onboarding, int departedThisMonth) {
    }

    public record TrendAggregate(long runId, String period, String status, int employeeCount, BigDecimal total) {
    }

    public record DataScope(long actorUserId, Long departmentId, boolean companyWide) {
    }
}
