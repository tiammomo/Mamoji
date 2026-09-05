package com.mamoji.accountingperiod.api;

import com.mamoji.accountingperiod.application.AccountingPeriodService;
import com.mamoji.accountingperiod.domain.AccountingPeriodControl;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting-periods/control")
public class AccountingPeriodController {
    private final AccountingPeriodService service;

    public AccountingPeriodController(AccountingPeriodService service) {
        this.service = service;
    }

    @GetMapping
    public AccountingPeriodControl current(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.current(actor, companyId);
    }

    @PostMapping("/close")
    public AccountingPeriodControl close(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody CloseAccountingPeriodRequest request
    ) {
        return service.close(actor, request);
    }

    @PostMapping("/reopen")
    public AccountingPeriodControl reopen(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody ReopenAccountingPeriodRequest request
    ) {
        return service.reopen(actor, request);
    }
}
