package com.mamoji.people.application;

import com.mamoji.platform.tenant.Company;
import com.mamoji.people.api.DepartmentCreateRequest;
import com.mamoji.people.api.DepartmentUpdateRequest;
import com.mamoji.people.domain.Department;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** People Core application boundary for company organization maintenance. */
@Service
public class DepartmentApplicationService {
    private final DepartmentRepository repository;
    private final AccessContextService accessContext;
    private final AccessControlService accessControl;
    private final EnterpriseStore auditStore;
    private final OutboxEventService outbox;

    public DepartmentApplicationService(
        DepartmentRepository repository,
        AccessContextService accessContext,
        AccessControlService accessControl,
        EnterpriseStore auditStore,
        OutboxEventService outbox
    ) {
        this.repository = repository;
        this.accessContext = accessContext;
        this.accessControl = accessControl;
        this.auditStore = auditStore;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Department> list(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        return repository.findByCompany(company.id);
    }

    @Transactional
    public Department create(ActorContext actor, DepartmentCreateRequest request) {
        Company company = accessContext.requireCompany(actor, request.companyId());
        accessControl.requirePeopleManager(actor.user(), company.id);
        requireManagerInCompany(request.managerEmployeeId(), company.id);
        String now = OffsetDateTime.now().toString();
        Department department = new Department();
        department.companyId = company.id;
        department.name = request.name().trim();
        department.costCenter = normalizedOr(request.costCenter(), "GENERAL");
        department.managerEmployeeId = request.managerEmployeeId();
        department.budget = request.budget() == null ? BigDecimal.ZERO : request.budget();
        department.status = request.status() == null ? 1 : request.status();
        department.createdAt = now;
        department.updatedAt = now;
        repository.insert(department);
        audit(department, "create", "创建部门: " + department.name, actor);
        return department;
    }

    @Transactional
    public Department update(ActorContext actor, long id, DepartmentUpdateRequest request) {
        Department department = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Department not found"));
        long requestedCompanyId = request.companyId == null ? department.companyId : request.companyId;
        Company company = accessContext.requireCompany(actor, requestedCompanyId);
        if (company.id != department.companyId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Department is outside the selected company");
        }
        accessControl.requirePeopleManager(actor.user(), company.id);
        if (request.hasManagerEmployeeId()) {
            requireManagerInCompany(request.managerEmployeeId(), company.id);
            department.managerEmployeeId = request.managerEmployeeId();
        }
        if (request.name != null) department.name = request.name.trim();
        if (request.costCenter != null) department.costCenter = request.costCenter.trim();
        if (request.budget != null) department.budget = request.budget;
        if (request.status != null) department.status = request.status;
        department.updatedAt = OffsetDateTime.now().toString();
        repository.update(department);
        audit(department, "update", "更新部门: " + department.name, actor);
        return department;
    }

    private void requireManagerInCompany(Long managerEmployeeId, long companyId) {
        if (managerEmployeeId != null && !repository.employeeBelongsToCompany(managerEmployeeId, companyId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Department manager must belong to the same company");
        }
    }

    private String normalizedOr(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    private void audit(Department department, String action, String summary, ActorContext actor) {
        auditStore.auditLog(
            department.companyId,
            "department",
            department.id,
            action,
            summary,
            actor.userId(),
            actor.user().nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("action", action);
        payload.put("departmentName", department.name);
        payload.put("costCenter", department.costCenter);
        payload.put("budget", department.budget);
        payload.put("status", department.status);
        payload.put("managerEmployeeId", department.managerEmployeeId);
        outbox.publish(
            "people.department." + action,
            department.companyId,
            "department",
            department.id,
            actor.userId(),
            payload
        );
    }
}
