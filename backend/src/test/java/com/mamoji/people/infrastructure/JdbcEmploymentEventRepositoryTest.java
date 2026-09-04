package com.mamoji.people.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mamoji.people.domain.EmploymentEvent;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEmploymentEventRepositoryTest {
    @Test
    void appendNormalizesAndBindsTypedLifecycleValues() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEmploymentEventRepository repository = new JdbcEmploymentEventRepository(jdbc);
        EmploymentEvent event = event();

        EmploymentEvent saved = repository.append(event);

        assertEquals(41, saved.id);
        assertEquals("status_change", saved.type);
        assertEquals("员工转正", saved.note);
        assertNotNull(saved.createdAt);
        assertEquals(7L, jdbc.arguments[0]);
        assertEquals(13L, jdbc.arguments[1]);
        assertEquals("status_change", jdbc.arguments[2]);
        assertInstanceOf(LocalDate.class, jdbc.arguments[3]);
        assertEquals("员工转正", jdbc.arguments[4]);
        assertEquals(5L, jdbc.arguments[5]);
        assertInstanceOf(OffsetDateTime.class, jdbc.arguments[6]);
    }

    @Test
    void companyHistoryUsesOneCompanyScopedQuery() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcEmploymentEventRepository repository = new JdbcEmploymentEventRepository(jdbc);

        List<EmploymentEvent> events = repository.findByCompany(7);

        assertEquals(1, events.size());
        assertEquals(7L, jdbc.arguments[0]);
    }

    private EmploymentEvent event() {
        EmploymentEvent event = new EmploymentEvent();
        event.companyId = 7;
        event.employeeId = 13;
        event.type = " STATUS_CHANGE ";
        event.effectiveDate = "2026-09-04";
        event.note = " 员工转正 ";
        event.operatorUserId = 5;
        return event;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] arguments;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            arguments = args;
            return (T) Long.valueOf(41);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            arguments = args;
            EmploymentEvent event = new EmploymentEvent();
            event.companyId = 7;
            return (List<T>) List.of(event);
        }
    }
}
