package com.mamoji.people.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.people.domain.Employee;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEmployeeRepositoryTest {
    @Test
    void employeeProfilesUseTwoBatchQueriesRegardlessOfEmployeeCount() {
        CountingJdbcTemplate jdbc = new CountingJdbcTemplate(List.of(
            employee(1, 9),
            employee(2, 9),
            employee(3, 9)
        ));
        JdbcEmployeeRepository repository = new JdbcEmployeeRepository(jdbc);

        List<Employee> employees = repository.findByCompany(9, true);

        assertEquals(3, employees.size());
        assertEquals(3, jdbc.queryCount);
        assertTrue(employees.stream().allMatch(employee -> employee.certificates.isEmpty()));
        assertTrue(employees.stream().allMatch(employee -> employee.experiences.isEmpty()));

        repository.findByCompany(9, false);
        assertEquals(4, jdbc.queryCount, "Basic employee reads must only execute the employee query");
    }

    private Employee employee(long id, long companyId) {
        Employee employee = new Employee();
        employee.id = id;
        employee.companyId = companyId;
        employee.status = "active";
        return employee;
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private final List<Employee> employees;
        private int queryCount;

        private CountingJdbcTemplate(List<Employee> employees) {
            this.employees = employees;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount++;
            if (sql.contains("FROM employees employee")) return (List<T>) employees;
            return List.of();
        }
    }
}
