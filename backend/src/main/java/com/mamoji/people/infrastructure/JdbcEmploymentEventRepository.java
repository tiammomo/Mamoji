package com.mamoji.people.infrastructure;

import com.mamoji.people.application.EmploymentEventRepository;
import com.mamoji.people.domain.EmploymentEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEmploymentEventRepository implements EmploymentEventRepository {
    private static final String COLUMNS = """
        id, company_id, employee_id, type, effective_date, note, operator_user_id, created_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcEmploymentEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EmploymentEvent> findByCompany(long companyId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM employment_events WHERE company_id = ? ORDER BY effective_date DESC, id",
            this::map,
            companyId
        );
    }

    @Override
    public EmploymentEvent append(EmploymentEvent event) {
        event.type = event.type.trim().toLowerCase(Locale.ROOT);
        event.note = event.note.trim();
        event.createdAt = OffsetDateTime.now().toString();
        Long id = jdbc.queryForObject("""
            INSERT INTO employment_events (
                company_id, employee_id, type, effective_date, note, operator_user_id, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, event.companyId, event.employeeId, event.type, LocalDate.parse(event.effectiveDate),
            event.note, event.operatorUserId, OffsetDateTime.parse(event.createdAt));
        if (id == null) throw new IllegalStateException("Database did not return a generated employment event id");
        event.id = id;
        return event;
    }

    @Override
    public void deleteByEmployeeForDemoReset(long employeeId) {
        jdbc.update("DELETE FROM employment_events WHERE employee_id = ?", employeeId);
    }

    private EmploymentEvent map(ResultSet result, int rowNumber) throws SQLException {
        EmploymentEvent event = new EmploymentEvent();
        event.id = result.getLong("id");
        event.companyId = result.getLong("company_id");
        event.employeeId = result.getLong("employee_id");
        event.type = result.getString("type");
        event.effectiveDate = result.getObject("effective_date", LocalDate.class).toString();
        event.note = result.getString("note");
        event.operatorUserId = result.getLong("operator_user_id");
        event.createdAt = result.getObject("created_at", OffsetDateTime.class).toString();
        return event;
    }
}
