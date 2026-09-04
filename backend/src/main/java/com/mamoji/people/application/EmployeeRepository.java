package com.mamoji.people.application;

import com.mamoji.people.domain.Employee;
import com.mamoji.people.domain.EmployeeCertificate;
import com.mamoji.people.domain.EmployeeExperience;
import java.util.List;
import java.util.Optional;

/** Persistence port for employee records and their owned profile details. */
public interface EmployeeRepository {
    List<Employee> findByCompany(long companyId, boolean includeProfileDetails);

    default List<Employee> findByCompany(long companyId) {
        return findByCompany(companyId, true);
    }

    List<Employee> findAll();

    Optional<Employee> findById(long id);

    Optional<Employee> findByIdForUpdate(long id);

    Optional<Employee> findActiveByUser(long userId, long companyId);

    boolean existsByCompanyAndEmail(long companyId, String email);

    Employee insert(Employee employee);

    void update(Employee employee);

    List<EmployeeCertificate> replaceCertificates(long employeeId, List<EmployeeCertificate> certificates);

    List<EmployeeExperience> replaceExperiences(long employeeId, List<EmployeeExperience> experiences);

    void deleteForDemoReset(long id);
}
