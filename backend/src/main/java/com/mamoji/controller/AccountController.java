package com.mamoji.controller;

import com.mamoji.domain.Models.Account;
import com.mamoji.service.AccountingService;
import com.mamoji.service.AccountReconciliationService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountingService service;
    private final AccountReconciliationService reconciliationService;

    public AccountController(AccountingService service, AccountReconciliationService reconciliationService) {
        this.service = service;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public List<Account> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listAccounts(authorization, companyId);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.accountSummary(authorization, companyId);
    }

    @GetMapping("/{id}")
    public Account get(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.getAccount(authorization, id, companyId);
    }

    @PostMapping
    public Account create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        rejectDirectReconciliationUpdate(body);
        return service.createAccount(authorization, body);
    }

    @PutMapping("/{id}")
    public Account update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        rejectDirectReconciliationUpdate(body);
        return service.updateAccount(authorization, id, companyId, body);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.deleteAccount(authorization, id, companyId);
    }

    @GetMapping("/{id}/reconciliations")
    public List<AccountReconciliationService.ReconciliationRecord> reconciliations(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return reconciliationService.list(authorization, id, companyId);
    }

    @PostMapping("/{id}/reconciliations")
    public AccountReconciliationService.ReconciliationRecord reconcile(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        return reconciliationService.create(authorization, id, companyId, body);
    }

    private void rejectDirectReconciliationUpdate(Map<String, Object> body) {
        if (body.containsKey("reconciliationStatus") || body.containsKey("lastReconciledAt")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the reconciliation endpoint to update reconciliation state");
        }
    }
}
