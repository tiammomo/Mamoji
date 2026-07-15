package com.mamoji.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.mamoji.domain.Models.Employee;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class EnterpriseStoreReadTest {

    @Test
    void employeeProfilesUseTwoBatchQueriesRegardlessOfEmployeeCount() {
        CountingJdbcTemplate jdbc = new CountingJdbcTemplate();
        EnterpriseStore store = new EnterpriseStore(
            jdbc,
            mock(InMemoryStore.class),
            false,
            "demo",
            "Test",
            "",
            "test",
            "test",
            "CNY"
        );
        store.employees.put(1L, employee(1, 9));
        store.employees.put(2L, employee(2, 9));
        store.employees.put(3L, employee(3, 9));

        List<Employee> employees = store.sortedEmployees(9);

        assertEquals(3, employees.size());
        assertEquals(2, jdbc.queryCount);
        assertTrue(employees.stream().allMatch(employee -> employee.certificates.isEmpty()));
        assertTrue(employees.stream().allMatch(employee -> employee.experiences.isEmpty()));

        store.sortedEmployees(9, false);
        assertEquals(2, jdbc.queryCount, "Basic employee reads must not load unused profile collections");
    }

    private Employee employee(long id, long companyId) {
        Employee employee = new Employee();
        employee.id = id;
        employee.companyId = companyId;
        employee.status = "active";
        return employee;
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private int queryCount;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount++;
            return List.of();
        }
    }
}
