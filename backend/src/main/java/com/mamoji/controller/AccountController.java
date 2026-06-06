package com.mamoji.controller;

import com.mamoji.domain.Models.Account;
import com.mamoji.service.AccountingService;
import java.math.BigDecimal;
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

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountingService service;

    public AccountController(AccountingService service) {
        this.service = service;
    }

    @GetMapping
    public List<Account> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.listAccounts(authorization);
    }

    @GetMapping("/summary")
    public Map<String, BigDecimal> summary(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.accountSummary(authorization);
    }

    @GetMapping("/{id}")
    public Account get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        return service.getAccount(authorization, id);
    }

    @PostMapping
    public Account create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createAccount(authorization, body);
    }

    @PutMapping("/{id}")
    public Account update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateAccount(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        service.deleteAccount(authorization, id);
    }
}
