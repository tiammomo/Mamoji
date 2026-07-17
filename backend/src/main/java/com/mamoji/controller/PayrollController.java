package com.mamoji.controller;

import com.mamoji.platform.product.RequiresProductModule;
import com.mamoji.domain.Models.PayrollRun;
import com.mamoji.service.PayrollService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payroll-runs")
@RequiresProductModule("workforce-cost")
public class PayrollController {
    private final PayrollService service;

    public PayrollController(PayrollService service) {
        this.service = service;
    }

    @GetMapping
    public List<PayrollRun> payrollRuns(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestParam(value = "period", required = false) String period
    ) {
        return service.listRuns(authorization, companyId, period);
    }

    @PostMapping
    public PayrollRun createPayrollRun(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createRun(authorization, body);
    }

    @PostMapping("/{id}/close")
    public PayrollRun closePayrollRun(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.closeRun(authorization, id);
    }
}
