package com.mamoji.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.PayrollRun;
import com.mamoji.domain.Models.PayrollRunItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PayrollServiceReadTest {

    @Test
    void listRunsLoadsAllItemsInOneBatchQuery() {
        PayrollRun first = run(11);
        PayrollRun second = run(12);
        PayrollRunItem firstItem = item(101, first.id);
        PayrollRunItem secondItem = item(102, second.id);
        PayrollJdbcTemplate jdbc = new PayrollJdbcTemplate(List.of(first, second), List.of(firstItem, secondItem));
        AccessControlService accessControl = mock(AccessControlService.class);
        User user = new User();
        user.id = 7;
        Company company = new Company();
        company.id = 9;
        when(accessControl.requireUser("Bearer token")).thenReturn(user);
        when(accessControl.resolveCompany(user, 9L)).thenReturn(company);
        PayrollService service = new PayrollService(
            jdbc,
            mock(EnterpriseStore.class),
            accessControl,
            mock(OutboxEventService.class)
        );

        List<PayrollRun> result = service.listRuns("Bearer token", 9L, null);

        assertEquals(2, jdbc.queryCount);
        assertEquals(List.of(firstItem), result.get(0).items);
        assertEquals(List.of(secondItem), result.get(1).items);
    }

    private PayrollRun run(long id) {
        PayrollRun run = new PayrollRun();
        run.id = id;
        return run;
    }

    private PayrollRunItem item(long id, long runId) {
        PayrollRunItem item = new PayrollRunItem();
        item.id = id;
        item.runId = runId;
        return item;
    }

    private static final class PayrollJdbcTemplate extends JdbcTemplate {
        private final List<PayrollRun> runs;
        private final List<PayrollRunItem> items;
        private int queryCount;

        private PayrollJdbcTemplate(List<PayrollRun> runs, List<PayrollRunItem> items) {
            this.runs = runs;
            this.items = items;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queryCount++;
            return (List<T>) (sql.contains("FROM payroll_run_items") ? items : runs);
        }
    }
}
