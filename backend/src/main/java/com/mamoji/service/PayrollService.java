package com.mamoji.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.PayrollRun;
import com.mamoji.domain.Models.PayrollRunItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.textOr;

@Service
public class PayrollService {
    private final JdbcTemplate jdbc;
    private final EnterpriseStore enterpriseStore;
    private final AccessControlService accessControl;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PayrollService(
        JdbcTemplate jdbc,
        EnterpriseStore enterpriseStore,
        AccessControlService accessControl,
        OutboxEventService outboxEventService
    ) {
        this.jdbc = jdbc;
        this.enterpriseStore = enterpriseStore;
        this.accessControl = accessControl;
        this.outboxEventService = outboxEventService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PayrollRun> listRuns(String authorization, Long companyId, String period) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        accessControl.requirePayrollManager(user, company.id);
        String normalizedPeriod = period == null || period.isBlank() ? null : normalizedPeriod(period);
        List<PayrollRun> runs = normalizedPeriod == null
            ? jdbc.query("""
                SELECT * FROM payroll_runs
                WHERE company_id = ?
                ORDER BY period DESC, id DESC
                """, this::mapRun, company.id)
            : jdbc.query("""
                SELECT * FROM payroll_runs
                WHERE company_id = ? AND period = ?
                ORDER BY id DESC
                """, this::mapRun, company.id, normalizedPeriod);
        return attachItems(runs);
    }

    @Transactional
    public PayrollRun createRun(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        accessControl.requirePayrollManager(user, company.id);
        String period = normalizedPeriod(body.get("period"));
        if (payrollRunExists(company.id, period)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payroll run already exists for this period");
        }
        List<PayrollRunItem> items = enterpriseStore.sortedEmployees(company.id, false).stream()
            .filter(employee -> List.of("active", "probation").contains(employee.status))
            .map(employee -> snapshotItem(company.id, period, employee))
            .toList();
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active employees for payroll run");
        }
        PayrollRun run = aggregateRun(company.id, period, user.id, items);
        try {
            run.id = insert("""
                INSERT INTO payroll_runs (
                    company_id, period, name, status, employee_count, salary_total, social_personal_total,
                    social_company_total, housing_personal_total, housing_company_total, tax_total,
                    personal_deduction_total, net_pay_total, company_cost_total, created_by_user_id,
                    closed_by_user_id, closed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> bindRun(ps, run));
        } catch (DuplicateKeyException ignored) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payroll run already exists for this period");
        }
        for (PayrollRunItem item : items) {
            item.runId = run.id;
            item.id = insert("""
                INSERT INTO payroll_run_items (
                    run_id, company_id, employee_id, employee_name, department_name, period, salary, payable_salary,
                    social_personal_amount, social_company_amount, housing_personal_amount, housing_company_amount,
                    tax_amount, personal_deduction, net_pay, company_cost, snapshot_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> bindItem(ps, item));
        }
        run.items = items;
        enterpriseStore.auditLog(company.id, "payroll_run", run.id, "create", "生成薪酬月结批次: " + run.period, user.id, user.nickname);
        outboxEventService.publish("payroll.run.created", company.id, "payroll_run", run.id, user.id, Map.of(
            "period", run.period,
            "employeeCount", run.employeeCount,
            "salaryTotal", moneyText(run.salaryTotal),
            "netPayTotal", moneyText(run.netPayTotal),
            "companyCostTotal", moneyText(run.companyCostTotal)
        ));
        return run;
    }

    @Transactional
    public PayrollRun closeRun(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        PayrollRun run = findRunForUpdate(id);
        Company company = accessControl.resolveCompany(user, run.companyId);
        accessControl.requirePayrollManager(user, company.id);
        if ("closed".equals(run.status)) {
            return attachItems(run);
        }
        String now = InMemoryStore.now();
        jdbc.update("""
            UPDATE payroll_runs
            SET status = 'closed', closed_by_user_id = ?, closed_at = ?, updated_at = ?
            WHERE id = ?
            """, user.id, now, now, id);
        run.status = "closed";
        run.closedByUserId = user.id;
        run.closedAt = now;
        run.updatedAt = now;
        PayrollRun closed = attachItems(run);
        enterpriseStore.auditLog(company.id, "payroll_run", closed.id, "close", "锁定薪酬月结批次: " + closed.period, user.id, user.nickname);
        outboxEventService.publish("payroll.run.closed", company.id, "payroll_run", closed.id, user.id, Map.of(
            "period", closed.period,
            "employeeCount", closed.employeeCount,
            "netPayTotal", moneyText(closed.netPayTotal),
            "companyCostTotal", moneyText(closed.companyCostTotal),
            "closedAt", closed.closedAt
        ));
        return closed;
    }

    private PayrollRun findRunForUpdate(long id) {
        List<PayrollRun> runs = jdbc.query("SELECT * FROM payroll_runs WHERE id = ? FOR UPDATE", this::mapRun, id);
        if (runs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payroll run not found");
        }
        return runs.getFirst();
    }

    private boolean payrollRunExists(long companyId, String period) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(1) FROM payroll_runs WHERE company_id = ? AND period = ?",
            Integer.class,
            companyId,
            period
        );
        return count != null && count > 0;
    }

    private PayrollRun aggregateRun(long companyId, String period, long createdByUserId, List<PayrollRunItem> items) {
        PayrollRun run = new PayrollRun();
        run.companyId = companyId;
        run.period = period;
        run.name = period + " 薪酬月结";
        run.status = "draft";
        run.employeeCount = items.size();
        run.salaryTotal = sum(items, item -> item.payableSalary);
        run.socialPersonalTotal = sum(items, item -> item.socialPersonalAmount);
        run.socialCompanyTotal = sum(items, item -> item.socialCompanyAmount);
        run.housingPersonalTotal = sum(items, item -> item.housingPersonalAmount);
        run.housingCompanyTotal = sum(items, item -> item.housingCompanyAmount);
        run.taxTotal = sum(items, item -> item.taxAmount);
        run.personalDeductionTotal = sum(items, item -> item.personalDeduction);
        run.netPayTotal = sum(items, item -> item.netPay);
        run.companyCostTotal = sum(items, item -> item.companyCost);
        run.createdByUserId = createdByUserId;
        run.createdAt = InMemoryStore.now();
        run.updatedAt = run.createdAt;
        return run;
    }

    private PayrollRunItem snapshotItem(long companyId, String period, Employee employee) {
        PayrollRunItem item = new PayrollRunItem();
        item.companyId = companyId;
        item.employeeId = employee.id;
        item.employeeName = employee.name;
        item.departmentName = employee.departmentName;
        item.period = period;
        item.salary = money(employee.salary);
        item.payableSalary = money(employee.salary).add(money(employee.overtimePay));
        item.socialPersonalAmount = money(employee.socialInsurancePersonalAmount);
        item.socialCompanyAmount = money(employee.socialInsuranceCompanyAmount);
        item.housingPersonalAmount = money(employee.housingFundPersonalAmount);
        item.housingCompanyAmount = money(employee.housingFundCompanyAmount);
        item.taxAmount = money(employee.taxEstimate);
        item.personalDeduction = money(employee.personalDeduction);
        item.netPay = money(employee.netPayEstimate);
        item.companyCost = money(employee.monthlyCost);
        item.createdAt = InMemoryStore.now();
        item.snapshotJson = snapshotJson(employee, item);
        return item;
    }

    private String snapshotJson(Employee employee, PayrollRunItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employeeNo", employee.employeeNo);
        snapshot.put("position", employee.position);
        snapshot.put("departmentName", item.departmentName);
        snapshot.put("socialInsuranceRegion", employee.socialInsuranceRegion);
        snapshot.put("hukouType", employee.hukouType);
        snapshot.put("medicalTier", employee.medicalTier);
        snapshot.put("pensionBase", moneyText(employee.pensionBase));
        snapshot.put("medicalBase", moneyText(employee.medicalBase));
        snapshot.put("unemploymentBase", moneyText(employee.unemploymentBase));
        snapshot.put("workInjuryBase", moneyText(employee.workInjuryBase));
        snapshot.put("maternityBase", moneyText(employee.maternityBase));
        snapshot.put("housingFundBase", moneyText(employee.housingFundBase));
        snapshot.put("housingFundPersonalRate", moneyText(employee.housingFundPersonalRate));
        snapshot.put("housingFundCompanyRate", moneyText(employee.housingFundCompanyRate));
        snapshot.put("salary", moneyText(item.salary));
        snapshot.put("overtimeBase", moneyText(employee.overtimeBase));
        snapshot.put("weekdayOvertimeHours", moneyText(employee.weekdayOvertimeHours));
        snapshot.put("restDayOvertimeHours", moneyText(employee.restDayOvertimeHours));
        snapshot.put("holidayOvertimeHours", moneyText(employee.holidayOvertimeHours));
        snapshot.put("overtimePay", moneyText(employee.overtimePay));
        snapshot.put("overtimePolicyNote", employee.overtimePolicyNote);
        snapshot.put("payableSalary", moneyText(item.payableSalary));
        snapshot.put("netPay", moneyText(item.netPay));
        snapshot.put("companyCost", moneyText(item.companyCost));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create payroll snapshot", e);
        }
    }

    private PayrollRun attachItems(PayrollRun run) {
        run.items = jdbc.query("""
            SELECT * FROM payroll_run_items
            WHERE run_id = ?
            ORDER BY employee_name, id
            """, this::mapItem, run.id);
        return run;
    }

    private List<PayrollRun> attachItems(List<PayrollRun> runs) {
        if (runs.isEmpty()) {
            return runs;
        }
        String placeholders = String.join(", ", Collections.nCopies(runs.size(), "?"));
        Object[] runIds = runs.stream().map(run -> run.id).toArray();
        List<PayrollRunItem> items = jdbc.query("""
            SELECT * FROM payroll_run_items
            WHERE run_id IN (%s)
            ORDER BY run_id, employee_name, id
            """.formatted(placeholders), this::mapItem, runIds);
        Map<Long, List<PayrollRunItem>> itemsByRun = new HashMap<>();
        for (PayrollRunItem item : items) {
            itemsByRun.computeIfAbsent(item.runId, ignored -> new ArrayList<>()).add(item);
        }
        for (PayrollRun run : runs) {
            run.items = List.copyOf(itemsByRun.getOrDefault(run.id, List.of()));
        }
        return runs;
    }

    private PayrollRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        PayrollRun run = new PayrollRun();
        run.id = rs.getLong("id");
        run.companyId = rs.getLong("company_id");
        run.period = rs.getString("period");
        run.name = rs.getString("name");
        run.status = rs.getString("status");
        run.employeeCount = rs.getInt("employee_count");
        run.salaryTotal = money(rs.getString("salary_total"));
        run.socialPersonalTotal = money(rs.getString("social_personal_total"));
        run.socialCompanyTotal = money(rs.getString("social_company_total"));
        run.housingPersonalTotal = money(rs.getString("housing_personal_total"));
        run.housingCompanyTotal = money(rs.getString("housing_company_total"));
        run.taxTotal = money(rs.getString("tax_total"));
        run.personalDeductionTotal = money(rs.getString("personal_deduction_total"));
        run.netPayTotal = money(rs.getString("net_pay_total"));
        run.companyCostTotal = money(rs.getString("company_cost_total"));
        run.createdByUserId = rs.getLong("created_by_user_id");
        run.closedByUserId = nullableLong(rs, "closed_by_user_id");
        run.closedAt = rs.getString("closed_at");
        run.createdAt = rs.getString("created_at");
        run.updatedAt = rs.getString("updated_at");
        return run;
    }

    private PayrollRunItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        PayrollRunItem item = new PayrollRunItem();
        item.id = rs.getLong("id");
        item.runId = rs.getLong("run_id");
        item.companyId = rs.getLong("company_id");
        item.employeeId = rs.getLong("employee_id");
        item.employeeName = rs.getString("employee_name");
        item.departmentName = rs.getString("department_name");
        item.period = rs.getString("period");
        item.salary = money(rs.getString("salary"));
        item.payableSalary = money(rs.getString("payable_salary"));
        item.socialPersonalAmount = money(rs.getString("social_personal_amount"));
        item.socialCompanyAmount = money(rs.getString("social_company_amount"));
        item.housingPersonalAmount = money(rs.getString("housing_personal_amount"));
        item.housingCompanyAmount = money(rs.getString("housing_company_amount"));
        item.taxAmount = money(rs.getString("tax_amount"));
        item.personalDeduction = money(rs.getString("personal_deduction"));
        item.netPay = money(rs.getString("net_pay"));
        item.companyCost = money(rs.getString("company_cost"));
        item.snapshotJson = rs.getString("snapshot_json");
        item.createdAt = rs.getString("created_at");
        return item;
    }

    private void bindRun(PreparedStatement ps, PayrollRun run) throws SQLException {
        ps.setLong(1, run.companyId);
        ps.setString(2, run.period);
        ps.setString(3, run.name);
        ps.setString(4, run.status);
        ps.setInt(5, run.employeeCount);
        ps.setString(6, moneyText(run.salaryTotal));
        ps.setString(7, moneyText(run.socialPersonalTotal));
        ps.setString(8, moneyText(run.socialCompanyTotal));
        ps.setString(9, moneyText(run.housingPersonalTotal));
        ps.setString(10, moneyText(run.housingCompanyTotal));
        ps.setString(11, moneyText(run.taxTotal));
        ps.setString(12, moneyText(run.personalDeductionTotal));
        ps.setString(13, moneyText(run.netPayTotal));
        ps.setString(14, moneyText(run.companyCostTotal));
        ps.setLong(15, run.createdByUserId);
        setLongOrNull(ps, 16, run.closedByUserId);
        ps.setString(17, run.closedAt);
        ps.setString(18, run.createdAt);
        ps.setString(19, run.updatedAt);
    }

    private void bindItem(PreparedStatement ps, PayrollRunItem item) throws SQLException {
        ps.setLong(1, item.runId);
        ps.setLong(2, item.companyId);
        ps.setLong(3, item.employeeId);
        ps.setString(4, item.employeeName);
        ps.setString(5, item.departmentName);
        ps.setString(6, item.period);
        ps.setString(7, moneyText(item.salary));
        ps.setString(8, moneyText(item.payableSalary));
        ps.setString(9, moneyText(item.socialPersonalAmount));
        ps.setString(10, moneyText(item.socialCompanyAmount));
        ps.setString(11, moneyText(item.housingPersonalAmount));
        ps.setString(12, moneyText(item.housingCompanyAmount));
        ps.setString(13, moneyText(item.taxAmount));
        ps.setString(14, moneyText(item.personalDeduction));
        ps.setString(15, moneyText(item.netPay));
        ps.setString(16, moneyText(item.companyCost));
        ps.setString(17, item.snapshotJson);
        ps.setString(18, item.createdAt);
    }

    private long insert(String sql, SqlBinder binder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[] { "id" });
            binder.bind(ps);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated key returned");
        }
        return key.longValue();
    }

    private String normalizedPeriod(Object value) {
        String raw = textOr(value, YearMonth.now().toString());
        try {
            return YearMonth.parse(raw).toString();
        } catch (RuntimeException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payroll period");
        }
    }

    private static BigDecimal sum(List<PayrollRunItem> items, MoneyAccessor accessor) {
        return items.stream().map(accessor::value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String moneyText(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface MoneyAccessor {
        BigDecimal value(PayrollRunItem item);
    }
}
