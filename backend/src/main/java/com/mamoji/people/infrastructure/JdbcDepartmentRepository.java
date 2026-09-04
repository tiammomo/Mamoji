package com.mamoji.people.infrastructure;

import com.mamoji.people.application.DepartmentRepository;
import com.mamoji.people.domain.Department;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDepartmentRepository implements DepartmentRepository {
    private static final String COLUMNS = """
        id, company_id, name, cost_center, manager_employee_id, budget, status, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcDepartmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Department> findByCompany(long companyId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM departments WHERE company_id = ? ORDER BY id",
            this::map,
            companyId
        );
    }

    @Override
    public Optional<Department> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM departments WHERE id = ?", this::map, id)
            .stream().findFirst();
    }

    @Override
    public Optional<Department> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM departments WHERE id = ? FOR UPDATE", this::map, id)
            .stream().findFirst();
    }

    @Override
    public Department insert(Department department) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO departments (
                    company_id, name, cost_center, manager_employee_id, budget, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            bind(statement, department);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated department id");
        department.id = key.longValue();
        return department;
    }

    @Override
    public void update(Department department) {
        int updated = jdbc.update("""
            UPDATE departments
            SET name = ?, cost_center = ?, manager_employee_id = ?, budget = ?, status = ?, updated_at = ?
            WHERE id = ? AND company_id = ?
            """,
            department.name,
            department.costCenter,
            department.managerEmployeeId,
            department.budget,
            department.status,
            timestamp(department.updatedAt),
            department.id,
            department.companyId
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Department was changed by another request: " + department.id);
        }
    }

    @Override
    public boolean employeeBelongsToCompany(long employeeId, long companyId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM employees WHERE id = ? AND company_id = ?",
            Integer.class,
            employeeId,
            companyId
        );
        return count != null && count == 1;
    }

    private Department map(ResultSet result, int rowNumber) throws SQLException {
        Department department = new Department();
        department.id = result.getLong("id");
        department.companyId = result.getLong("company_id");
        department.name = result.getString("name");
        department.costCenter = result.getString("cost_center");
        department.managerEmployeeId = nullableLong(result, "manager_employee_id");
        department.budget = result.getBigDecimal("budget");
        department.status = result.getInt("status");
        department.createdAt = result.getObject("created_at", OffsetDateTime.class).toString();
        department.updatedAt = result.getObject("updated_at", OffsetDateTime.class).toString();
        return department;
    }

    private void bind(PreparedStatement statement, Department department) throws SQLException {
        statement.setLong(1, department.companyId);
        statement.setString(2, department.name);
        statement.setString(3, department.costCenter);
        if (department.managerEmployeeId == null) statement.setObject(4, null);
        else statement.setLong(4, department.managerEmployeeId);
        statement.setBigDecimal(5, department.budget);
        statement.setInt(6, department.status);
        statement.setObject(7, timestamp(department.createdAt));
        statement.setObject(8, timestamp(department.updatedAt));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private OffsetDateTime timestamp(String value) {
        return OffsetDateTime.parse(value);
    }
}
