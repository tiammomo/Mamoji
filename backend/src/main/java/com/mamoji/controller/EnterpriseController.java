package com.mamoji.controller;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.Department;
import com.mamoji.domain.Models.Employee;
import com.mamoji.domain.Models.EntityTransfer;
import com.mamoji.domain.Models.EmploymentEvent;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.platform.product.RequiresProductModule;
import com.mamoji.service.EnterpriseManagementService;
import com.mamoji.service.TaxComplianceService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprise")
public class EnterpriseController {
    private final EnterpriseManagementService service;
    private final TaxComplianceService taxComplianceService;

    public EnterpriseController(EnterpriseManagementService service, TaxComplianceService taxComplianceService) {
        this.service = service;
        this.taxComplianceService = taxComplianceService;
    }

    @GetMapping("/summary")
    @RequiresProductModule("people-core")
    public Map<String, Object> summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.summary(authorization, companyId);
    }

    @GetMapping("/permission-matrix")
    public Map<String, Object> permissionMatrix(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.permissionMatrix(authorization);
    }

    @GetMapping("/companies")
    public List<Company> companies(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.listCompanies(authorization);
    }

    @PostMapping("/companies")
    public Company createCompany(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createCompany(authorization, body);
    }

    @GetMapping("/company")
    public Company company(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.companyProfile(authorization, companyId);
    }

    @PutMapping("/company")
    public Company updateCompany(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateCompanyProfile(authorization, companyId, body);
    }

    @GetMapping("/departments")
    @RequiresProductModule("people-core")
    public List<Department> departments(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listDepartments(authorization, companyId);
    }

    @PostMapping("/departments")
    @RequiresProductModule("people-core")
    public Department createDepartment(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createDepartment(authorization, body);
    }

    @PutMapping("/departments/{id}")
    @RequiresProductModule("people-core")
    public Department updateDepartment(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateDepartment(authorization, id, body);
    }

    @GetMapping("/employees")
    @RequiresProductModule("people-core")
    public List<Employee> employees(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listEmployees(authorization, params);
    }

    @PostMapping("/employees")
    @RequiresProductModule("people-core")
    public Employee createEmployee(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createEmployee(authorization, body);
    }

    @PutMapping("/employees/{id}")
    @RequiresProductModule("people-core")
    public Employee updateEmployee(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateEmployee(authorization, id, body);
    }

    @GetMapping("/employment-events")
    @RequiresProductModule("people-core")
    public List<EmploymentEvent> employmentEvents(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listEmploymentEvents(authorization, companyId);
    }

    @GetMapping("/tax-items")
    @RequiresProductModule("tax")
    public List<TaxItem> taxItems(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listTaxItems(authorization, companyId);
    }

    @GetMapping("/tax-compliance")
    @RequiresProductModule("tax")
    public Map<String, Object> taxCompliance(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return taxComplianceService.report(authorization, companyId);
    }

    @PostMapping("/tax-items")
    @RequiresProductModule("tax")
    public TaxItem createTaxItem(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createTaxItem(authorization, body);
    }

    @PutMapping("/tax-items/{id}")
    @RequiresProductModule("tax")
    public TaxItem updateTaxItem(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateTaxItem(authorization, id, body);
    }

    @DeleteMapping("/tax-items/{id}")
    @RequiresProductModule("tax")
    public void deleteTaxItem(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        service.deleteTaxItem(authorization, id);
    }

    @GetMapping("/entity-transfers")
    public List<EntityTransfer> entityTransfers(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "entityId", required = false) Long entityId
    ) {
        return service.listEntityTransfers(authorization, entityId);
    }

    @PostMapping("/entity-transfers")
    public EntityTransfer createEntityTransfer(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createEntityTransfer(authorization, body);
    }
}
