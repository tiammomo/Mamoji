package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.service.AccountingService;
import com.mamoji.service.TransactionImportService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final AccountingService service;
    private final TransactionImportService importService;

    public TransactionController(AccountingService service, TransactionImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping
    public PagedResponse<TransactionRecord> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.listTransactions(authorization, params);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.transactionSummary(authorization, params);
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("mamoji-transaction-import.csv", StandardCharsets.UTF_8).build().toString())
            .body(importService.template(authorization));
    }

    @PostMapping("/import/preview")
    public Map<String, Object> importPreview(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return importService.preview(authorization, file, companyId);
    }

    @PostMapping("/import")
    public Map<String, Object> importTransactions(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestParam(value = "skipDuplicates", defaultValue = "true") boolean skipDuplicates
    ) {
        return importService.commit(authorization, file, companyId, skipDuplicates);
    }

    @GetMapping("/{id}")
    public TransactionRecord get(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.getTransaction(authorization, id, companyId);
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
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        Map<String, Object> scopedBody = new java.util.LinkedHashMap<>(body);
        if (companyId != null) scopedBody.put("companyId", companyId);
        return service.updateTransaction(authorization, id, scopedBody);
    }

    @DeleteMapping("/{id}")
    public void delete(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        service.deleteTransaction(authorization, id, companyId);
    }

    @GetMapping("/refundable")
    public List<TransactionRecord> refundable(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.refundableTransactions(authorization, companyId);
    }

    @PostMapping("/{id}/refund")
    public Map<String, Object> refund(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @RequestBody Map<String, Object> body
    ) {
        Map<String, Object> scopedBody = new java.util.LinkedHashMap<>(body);
        if (companyId != null) scopedBody.put("companyId", companyId);
        return service.refundTransaction(authorization, id, scopedBody);
    }
}
