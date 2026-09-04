package com.mamoji.people.application;

import com.mamoji.people.domain.EmploymentEvent;
import java.util.List;

/** Append-only persistence port for employee lifecycle events. */
public interface EmploymentEventRepository {
    List<EmploymentEvent> findByCompany(long companyId);

    EmploymentEvent append(EmploymentEvent event);

    void deleteByEmployeeForDemoReset(long employeeId);
}
