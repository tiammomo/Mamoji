package com.mamoji.people.infrastructure;

import com.mamoji.people.application.EmployeeRepository;
import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCertificate;
import com.mamoji.people.domain.EmployeeCompensationPolicy;
import com.mamoji.people.domain.EmployeeExperience;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmployeeRepository implements EmployeeRepository {
    private static final List<String> WRITE_COLUMNS = List.of(
        "company_id", "user_id", "department_id", "employee_no", "name", "legal_name", "preferred_name",
        "email", "phone", "position", "direct_manager_employee_id", "job_level", "work_location",
        "employment_type", "status", "access_role", "access_scope", "hire_date", "leave_date",
        "probation_start_date", "probation_end_date", "contract_start_date", "contract_end_date", "contract_type",
        "contract_status", "education_level", "graduation_school", "major", "graduation_date", "graduation_year",
        "graduate_status", "skill_tags", "resume_summary", "material_status", "profile_verified_at",
        "profile_verified_by", "salary", "overtime_base", "weekday_overtime_hours", "rest_day_overtime_hours",
        "holiday_overtime_hours", "overtime_pay", "overtime_policy_note", "social_insurance", "housing_fund",
        "tax_estimate", "social_insurance_base", "social_insurance_personal_rate", "social_insurance_company_rate",
        "social_insurance_personal_amount", "social_insurance_company_amount", "housing_fund_base",
        "housing_fund_personal_rate", "housing_fund_company_rate", "housing_fund_personal_amount",
        "housing_fund_company_amount", "personal_deduction", "net_pay_estimate", "social_insurance_region",
        "hukou_type", "medical_tier", "pension_base", "medical_base", "unemployment_base", "work_injury_base",
        "maternity_base", "work_injury_company_rate", "social_insurance_policy_note", "monthly_cost",
        "emergency_contact", "created_at", "updated_at"
    );
    private static final String SELECT_COLUMNS = """
        employee.*,
        (SELECT department.name FROM departments department WHERE department.id = employee.department_id)
            AS resolved_department_name
        """;

    private final JdbcTemplate jdbc;

    public JdbcEmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Employee> findByCompany(long companyId, boolean includeProfileDetails) {
        List<Employee> employees = jdbc.query("""
            SELECT %s
            FROM employees employee
            WHERE employee.company_id = ?
            ORDER BY CASE WHEN employee.status = 'departed' THEN 1 ELSE 0 END, employee.id
            """.formatted(SELECT_COLUMNS), this::mapEmployee, companyId);
        if (includeProfileDetails) attachProfileDetails(companyId, employees);
        return employees;
    }

    @Override
    public List<Employee> findAll() {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM employees employee ORDER BY employee.company_id, employee.id",
            this::mapEmployee
        );
    }

    @Override
    public Optional<Employee> findById(long id) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM employees employee WHERE employee.id = ?",
            this::mapEmployee,
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<Employee> findByIdForUpdate(long id) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM employees employee WHERE employee.id = ? FOR UPDATE",
            this::mapEmployee,
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<Employee> findActiveByUser(long userId, long companyId) {
        return jdbc.query("""
            SELECT %s
            FROM employees employee
            WHERE employee.user_id = ? AND employee.company_id = ? AND employee.status <> 'departed'
            ORDER BY employee.id
            LIMIT 1
            """.formatted(SELECT_COLUMNS), this::mapEmployee, userId, companyId).stream().findFirst();
    }

    @Override
    public boolean existsByCompanyAndEmail(long companyId, String email) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE company_id = ? AND LOWER(email) = LOWER(?)",
            Integer.class,
            companyId,
            email
        );
        return count != null && count > 0;
    }

    @Override
    public Employee insert(Employee employee) {
        EmployeeCompensationPolicy.hydrate(employee);
        Object[] values = values(employee);
        String sql = "INSERT INTO employees (" + String.join(", ", WRITE_COLUMNS) + ") VALUES ("
            + String.join(", ", Collections.nCopies(values.length, "?")) + ") RETURNING id";
        Long id = jdbc.queryForObject(sql, Long.class, values);
        if (id == null) throw new IllegalStateException("Database did not return a generated employee id");
        employee.id = id;
        employee.departmentName = departmentName(employee.departmentId);
        return employee;
    }

    @Override
    public void update(Employee employee) {
        EmployeeCompensationPolicy.hydrate(employee);
        Object[] persisted = values(employee);
        List<String> mutableColumns = WRITE_COLUMNS.subList(1, WRITE_COLUMNS.size());
        String assignments = String.join(", ", mutableColumns.stream().map(column -> column + " = ?").toList());
        Object[] arguments = new Object[persisted.length + 1];
        System.arraycopy(persisted, 1, arguments, 0, persisted.length - 1);
        arguments[persisted.length - 1] = employee.id;
        arguments[persisted.length] = employee.companyId;
        int updated = jdbc.update(
            "UPDATE employees SET " + assignments + " WHERE id = ? AND company_id = ?",
            arguments
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Employee was changed by another request: " + employee.id);
        }
        employee.departmentName = departmentName(employee.departmentId);
    }

    @Override
    public List<EmployeeCertificate> replaceCertificates(
        long employeeId,
        List<EmployeeCertificate> certificates
    ) {
        jdbc.update("DELETE FROM employee_certificates WHERE employee_id = ?", employeeId);
        List<EmployeeCertificate> saved = new ArrayList<>();
        for (EmployeeCertificate certificate : certificates) {
            if (certificate == null || isBlank(certificate.name)) continue;
            certificate.employeeId = employeeId;
            certificate.name = certificate.name.trim();
            certificate.verificationStatus = defaultText(certificate.verificationStatus, "unverified");
            certificate.materialStatus = defaultText(certificate.materialStatus, "missing");
            String now = OffsetDateTime.now().toString();
            certificate.createdAt = now;
            certificate.updatedAt = now;
            certificate.id = requiredId(jdbc.queryForObject("""
                INSERT INTO employee_certificates (
                    employee_id, name, category, level, issuer, certificate_no, issue_date, expiry_date,
                    verification_status, material_status, note, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, certificate.employeeId, certificate.name, certificate.category, certificate.level,
                certificate.issuer, certificate.certificateNo, certificate.issueDate, certificate.expiryDate,
                certificate.verificationStatus, certificate.materialStatus, certificate.note,
                certificate.createdAt, certificate.updatedAt));
            saved.add(certificate);
        }
        return List.copyOf(saved);
    }

    @Override
    public List<EmployeeExperience> replaceExperiences(
        long employeeId,
        List<EmployeeExperience> experiences
    ) {
        jdbc.update("DELETE FROM employee_experiences WHERE employee_id = ?", employeeId);
        List<EmployeeExperience> saved = new ArrayList<>();
        for (EmployeeExperience experience : experiences) {
            if (experience == null
                || (isBlank(experience.organization) && isBlank(experience.title) && isBlank(experience.description))) {
                continue;
            }
            experience.employeeId = employeeId;
            experience.type = defaultText(experience.type, "work");
            experience.organization = defaultText(experience.organization, "未填写");
            String now = OffsetDateTime.now().toString();
            experience.createdAt = now;
            experience.updatedAt = now;
            experience.id = requiredId(jdbc.queryForObject("""
                INSERT INTO employee_experiences (
                    employee_id, type, organization, title, start_date, end_date, description, achievements,
                    skills, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, experience.employeeId, experience.type, experience.organization, experience.title,
                experience.startDate, experience.endDate, experience.description, experience.achievements,
                experience.skills, experience.createdAt, experience.updatedAt));
            saved.add(experience);
        }
        return List.copyOf(saved);
    }

    @Override
    public void deleteForDemoReset(long id) {
        jdbc.update("DELETE FROM employee_certificates WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM employee_experiences WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM payroll_run_items WHERE employee_id = ?", id);
        jdbc.update("DELETE FROM employees WHERE id = ?", id);
    }

    private Employee mapEmployee(ResultSet result, int rowNumber) throws SQLException {
        Employee employee = new Employee();
        employee.id = result.getLong("id");
        employee.companyId = result.getLong("company_id");
        employee.userId = nullableLong(result, "user_id");
        employee.departmentId = nullableLong(result, "department_id");
        employee.departmentName = result.getString("resolved_department_name");
        employee.employeeNo = result.getString("employee_no");
        employee.name = result.getString("name");
        employee.legalName = result.getString("legal_name");
        employee.preferredName = result.getString("preferred_name");
        employee.email = result.getString("email");
        employee.phone = result.getString("phone");
        employee.position = result.getString("position");
        employee.directManagerEmployeeId = nullableLong(result, "direct_manager_employee_id");
        employee.jobLevel = result.getString("job_level");
        employee.workLocation = result.getString("work_location");
        employee.employmentType = result.getString("employment_type");
        employee.status = result.getString("status");
        employee.accessRole = result.getString("access_role");
        employee.accessScope = result.getString("access_scope");
        employee.hireDate = date(result, "hire_date");
        employee.leaveDate = nullableDate(result, "leave_date");
        employee.probationStartDate = nullableDate(result, "probation_start_date");
        employee.probationEndDate = nullableDate(result, "probation_end_date");
        employee.contractStartDate = nullableDate(result, "contract_start_date");
        employee.contractEndDate = nullableDate(result, "contract_end_date");
        employee.contractType = result.getString("contract_type");
        employee.contractStatus = result.getString("contract_status");
        employee.educationLevel = result.getString("education_level");
        employee.graduationSchool = result.getString("graduation_school");
        employee.major = result.getString("major");
        employee.graduationDate = nullableDate(result, "graduation_date");
        int graduationYear = result.getInt("graduation_year");
        employee.graduationYear = result.wasNull() ? null : graduationYear;
        employee.graduateStatus = result.getString("graduate_status");
        employee.skillTags = result.getString("skill_tags");
        employee.resumeSummary = result.getString("resume_summary");
        employee.materialStatus = result.getString("material_status");
        employee.profileVerifiedAt = nullableTimestamp(result, "profile_verified_at");
        employee.profileVerifiedBy = nullableLong(result, "profile_verified_by");
        employee.salary = result.getBigDecimal("salary");
        employee.overtimeBase = result.getBigDecimal("overtime_base");
        employee.weekdayOvertimeHours = result.getBigDecimal("weekday_overtime_hours");
        employee.restDayOvertimeHours = result.getBigDecimal("rest_day_overtime_hours");
        employee.holidayOvertimeHours = result.getBigDecimal("holiday_overtime_hours");
        employee.overtimePay = result.getBigDecimal("overtime_pay");
        employee.overtimePolicyNote = result.getString("overtime_policy_note");
        employee.socialInsurance = result.getBigDecimal("social_insurance");
        employee.housingFund = result.getBigDecimal("housing_fund");
        employee.taxEstimate = result.getBigDecimal("tax_estimate");
        employee.socialInsuranceBase = result.getBigDecimal("social_insurance_base");
        employee.socialInsurancePersonalRate = result.getBigDecimal("social_insurance_personal_rate");
        employee.socialInsuranceCompanyRate = result.getBigDecimal("social_insurance_company_rate");
        employee.socialInsurancePersonalAmount = result.getBigDecimal("social_insurance_personal_amount");
        employee.socialInsuranceCompanyAmount = result.getBigDecimal("social_insurance_company_amount");
        employee.housingFundBase = result.getBigDecimal("housing_fund_base");
        employee.housingFundPersonalRate = result.getBigDecimal("housing_fund_personal_rate");
        employee.housingFundCompanyRate = result.getBigDecimal("housing_fund_company_rate");
        employee.housingFundPersonalAmount = result.getBigDecimal("housing_fund_personal_amount");
        employee.housingFundCompanyAmount = result.getBigDecimal("housing_fund_company_amount");
        employee.personalDeduction = result.getBigDecimal("personal_deduction");
        employee.netPayEstimate = result.getBigDecimal("net_pay_estimate");
        employee.socialInsuranceRegion = result.getString("social_insurance_region");
        employee.hukouType = result.getString("hukou_type");
        employee.medicalTier = result.getString("medical_tier");
        employee.pensionBase = result.getBigDecimal("pension_base");
        employee.medicalBase = result.getBigDecimal("medical_base");
        employee.unemploymentBase = result.getBigDecimal("unemployment_base");
        employee.workInjuryBase = result.getBigDecimal("work_injury_base");
        employee.maternityBase = result.getBigDecimal("maternity_base");
        employee.workInjuryCompanyRate = result.getBigDecimal("work_injury_company_rate");
        employee.socialInsurancePolicyNote = result.getString("social_insurance_policy_note");
        employee.monthlyCost = result.getBigDecimal("monthly_cost");
        employee.emergencyContact = result.getString("emergency_contact");
        employee.createdAt = timestamp(result, "created_at");
        employee.updatedAt = timestamp(result, "updated_at");
        EmployeeCompensationPolicy.hydrate(employee);
        return employee;
    }

    private Object[] values(Employee employee) {
        return new Object[] {
            employee.companyId, employee.userId, employee.departmentId, normalizedNullable(employee.employeeNo),
            employee.name.trim(), normalizedNullable(employee.legalName), normalizedNullable(employee.preferredName),
            employee.email.trim().toLowerCase(Locale.ROOT), normalizedNullable(employee.phone), employee.position.trim(),
            employee.directManagerEmployeeId, normalizedNullable(employee.jobLevel), normalizedNullable(employee.workLocation),
            employee.employmentType, employee.status, employee.accessRole, employee.accessScope,
            LocalDate.parse(employee.hireDate), nullableDate(employee.leaveDate), nullableDate(employee.probationStartDate),
            nullableDate(employee.probationEndDate), nullableDate(employee.contractStartDate),
            nullableDate(employee.contractEndDate), normalizedNullable(employee.contractType),
            normalizedNullable(employee.contractStatus), normalizedNullable(employee.educationLevel),
            normalizedNullable(employee.graduationSchool), normalizedNullable(employee.major),
            nullableDate(employee.graduationDate), employee.graduationYear, normalizedNullable(employee.graduateStatus),
            normalizedNullable(employee.skillTags), normalizedNullable(employee.resumeSummary),
            normalizedNullable(employee.materialStatus), nullableTimestamp(employee.profileVerifiedAt),
            employee.profileVerifiedBy, employee.salary, employee.overtimeBase, employee.weekdayOvertimeHours,
            employee.restDayOvertimeHours, employee.holidayOvertimeHours, employee.overtimePay,
            normalizedNullable(employee.overtimePolicyNote), employee.socialInsurance, employee.housingFund,
            employee.taxEstimate, employee.socialInsuranceBase, employee.socialInsurancePersonalRate,
            employee.socialInsuranceCompanyRate, employee.socialInsurancePersonalAmount,
            employee.socialInsuranceCompanyAmount, employee.housingFundBase, employee.housingFundPersonalRate,
            employee.housingFundCompanyRate, employee.housingFundPersonalAmount, employee.housingFundCompanyAmount,
            employee.personalDeduction, employee.netPayEstimate, employee.socialInsuranceRegion.trim(),
            employee.hukouType, employee.medicalTier, employee.pensionBase, employee.medicalBase,
            employee.unemploymentBase, employee.workInjuryBase, employee.maternityBase,
            employee.workInjuryCompanyRate, normalizedNullable(employee.socialInsurancePolicyNote), employee.monthlyCost,
            normalizedNullable(employee.emergencyContact), OffsetDateTime.parse(employee.createdAt),
            OffsetDateTime.parse(employee.updatedAt)
        };
    }

    private void attachProfileDetails(long companyId, List<Employee> employees) {
        if (employees.isEmpty()) return;
        Map<Long, List<EmployeeCertificate>> certificates = new HashMap<>();
        jdbc.query("""
            SELECT certificate.*
            FROM employee_certificates certificate
            JOIN employees employee ON employee.id = certificate.employee_id
            WHERE employee.company_id = ?
            ORDER BY certificate.employee_id, COALESCE(certificate.expiry_date, '9999-12-31'), certificate.id
            """, this::mapCertificate, companyId).forEach(certificate ->
            certificates.computeIfAbsent(certificate.employeeId, ignored -> new ArrayList<>()).add(certificate));
        Map<Long, List<EmployeeExperience>> experiences = new HashMap<>();
        jdbc.query("""
            SELECT experience.*
            FROM employee_experiences experience
            JOIN employees employee ON employee.id = experience.employee_id
            WHERE employee.company_id = ?
            ORDER BY experience.employee_id, COALESCE(experience.start_date, '0000-01-01') DESC, experience.id DESC
            """, this::mapExperience, companyId).forEach(experience ->
            experiences.computeIfAbsent(experience.employeeId, ignored -> new ArrayList<>()).add(experience));
        for (Employee employee : employees) {
            employee.certificates = List.copyOf(certificates.getOrDefault(employee.id, List.of()));
            employee.experiences = List.copyOf(experiences.getOrDefault(employee.id, List.of()));
        }
    }

    private EmployeeCertificate mapCertificate(ResultSet result, int rowNumber) throws SQLException {
        EmployeeCertificate certificate = new EmployeeCertificate();
        certificate.id = result.getLong("id");
        certificate.employeeId = result.getLong("employee_id");
        certificate.name = result.getString("name");
        certificate.category = result.getString("category");
        certificate.level = result.getString("level");
        certificate.issuer = result.getString("issuer");
        certificate.certificateNo = result.getString("certificate_no");
        certificate.issueDate = result.getString("issue_date");
        certificate.expiryDate = result.getString("expiry_date");
        certificate.verificationStatus = result.getString("verification_status");
        certificate.materialStatus = result.getString("material_status");
        certificate.note = result.getString("note");
        certificate.createdAt = result.getString("created_at");
        certificate.updatedAt = result.getString("updated_at");
        return certificate;
    }

    private EmployeeExperience mapExperience(ResultSet result, int rowNumber) throws SQLException {
        EmployeeExperience experience = new EmployeeExperience();
        experience.id = result.getLong("id");
        experience.employeeId = result.getLong("employee_id");
        experience.type = result.getString("type");
        experience.organization = result.getString("organization");
        experience.title = result.getString("title");
        experience.startDate = result.getString("start_date");
        experience.endDate = result.getString("end_date");
        experience.description = result.getString("description");
        experience.achievements = result.getString("achievements");
        experience.skills = result.getString("skills");
        experience.createdAt = result.getString("created_at");
        experience.updatedAt = result.getString("updated_at");
        return experience;
    }

    private String departmentName(Long departmentId) {
        if (departmentId == null) return null;
        return jdbc.query(
            "SELECT name FROM departments WHERE id = ?",
            (result, rowNumber) -> result.getString(1),
            departmentId
        ).stream().findFirst().orElse(null);
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private String date(ResultSet result, String column) throws SQLException {
        return result.getObject(column, LocalDate.class).toString();
    }

    private String nullableDate(ResultSet result, String column) throws SQLException {
        LocalDate value = result.getObject(column, LocalDate.class);
        return value == null ? null : value.toString();
    }

    private String timestamp(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toString();
    }

    private String nullableTimestamp(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toString();
    }

    private LocalDate nullableDate(String value) {
        return isBlank(value) ? null : LocalDate.parse(value.trim());
    }

    private OffsetDateTime nullableTimestamp(String value) {
        return isBlank(value) ? null : OffsetDateTime.parse(value.trim());
    }

    private String normalizedNullable(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long requiredId(Long value) {
        if (value == null) throw new IllegalStateException("Database did not return a generated profile id");
        return value;
    }
}
