package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.service.AccountingService;
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
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final AccountingService service;

    public TransactionController(AccountingService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<TransactionRecord> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listTransactions(authorization, params);
    }

    @GetMapping("/{id}")
    public TransactionRecord get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        return service.getTransaction(authorization, id);
    }

    @PostMapping
    public Map<String, Object> create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createTransaction(authorization, body);
    }

    @PutMapping("/{id}")
    public TransactionRecord update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.updateTransaction(authorization, id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        service.deleteTransaction(authorization, id);
    }

    @GetMapping("/refundable")
    public List<TransactionRecord> refundable(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.refundableTransactions(authorization);
    }

    @PostMapping("/{id}/refund")
    public Map<String, Object> refund(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.refundTransaction(authorization, id, body);
    }
}
