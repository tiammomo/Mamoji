package com.mamoji.platform.tenant;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Employee;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyMembershipRepository {
    private final JdbcTemplate jdbc;

    public CompanyMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CompanyMembership> find(long userId, long companyId) {
        return jdbc.query("""
            SELECT company_id, user_id, department_id, role, scope, status
            FROM company_memberships
            WHERE user_id = ? AND company_id = ?
            """, (rs, rowNum) -> new CompanyMembership(
                rs.getLong("company_id"),
                rs.getLong("user_id"),
                nullableLong(rs.getObject("department_id")),
                rs.getString("role"),
                rs.getString("scope"),
                rs.getString("status")
            ), userId, companyId).stream().findFirst();
    }

    public List<CompanyMembership> findActiveByUser(long userId) {
        return jdbc.query("""
            SELECT company_id, user_id, department_id, role, scope, status
            FROM company_memberships
            WHERE user_id = ? AND status = 'active'
            ORDER BY company_id
            """, (rs, rowNum) -> new CompanyMembership(
                rs.getLong("company_id"),
                rs.getLong("user_id"),
                nullableLong(rs.getObject("department_id")),
                rs.getString("role"),
                rs.getString("scope"),
                rs.getString("status")
            ), userId);
    }

    public List<CompanyMembership> findActiveByCompany(long companyId) {
        return jdbc.query("""
            SELECT company_id, user_id, department_id, role, scope, status
            FROM company_memberships
            WHERE company_id = ? AND status = 'active'
            ORDER BY user_id
            """, (rs, rowNum) -> new CompanyMembership(
                rs.getLong("company_id"),
                rs.getLong("user_id"),
                nullableLong(rs.getObject("department_id")),
                rs.getString("role"),
                rs.getString("scope"),
                rs.getString("status")
            ), companyId);
    }

    public void ensureOwner(Company company) {
        upsert(company.id, company.ownerId, null, "founder", "company", "active", true);
    }

    public void synchronize(Employee employee) {
        if (employee.userId == null) return;
        upsert(
            employee.companyId,
            employee.userId,
            employee.departmentId,
            defaultText(employee.accessRole, "employee"),
            defaultText(employee.accessScope, "self"),
            "departed".equals(employee.status) ? "inactive" : "active",
            false
        );
    }

    private void upsert(
        long companyId,
        long userId,
        Long departmentId,
        String role,
        String scope,
        String status,
        boolean forceOwner
    ) {
        jdbc.update("""
            INSERT INTO company_memberships (
                company_id, user_id, department_id, role, scope, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (company_id, user_id) DO UPDATE SET
                department_id = CASE WHEN ? THEN company_memberships.department_id ELSE EXCLUDED.department_id END,
                role = CASE
                    WHEN ? OR company_memberships.role = 'founder' THEN 'founder'
                    ELSE EXCLUDED.role
                END,
                scope = CASE
                    WHEN ? OR company_memberships.role = 'founder' THEN 'company'
                    ELSE EXCLUDED.scope
                END,
                status = EXCLUDED.status,
                updated_at = CURRENT_TIMESTAMP
            """, companyId, userId, departmentId, role, scope, status, forceOwner, forceOwner, forceOwner);
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
