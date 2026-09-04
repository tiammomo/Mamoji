package com.mamoji.people.application;

import com.mamoji.people.domain.Department;
import java.util.List;
import java.util.Optional;

/** Persistence port for authoritative company-scoped organization units. */
public interface DepartmentRepository {
    List<Department> findByCompany(long companyId);

    Optional<Department> findById(long id);

    Optional<Department> findByIdForUpdate(long id);

    Department insert(Department department);

    void update(Department department);

    boolean employeeBelongsToCompany(long employeeId, long companyId);
}
