package com.mamoji.controller;

import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.AuditLog;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.service.ReceiptService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {
    private final ReceiptService service;

    public ReceiptController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<ReceiptVoucher> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params
    ) {
        return service.list(authorization, params);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.summary(authorization, companyId);
    }

    @GetMapping("/{id}/audit-logs")
    public List<AuditLog> auditLogs(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.auditLogs(authorization, id);
    }

    @GetMapping("/{id}/file-link")
    public Map<String, Object> fileLink(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        return service.fileLink(authorization, id);
    }

    @GetMapping("/{id}/file-download")
    public ResponseEntity<byte[]> fileDownload(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id
    ) {
        ReceiptService.FileDownload file = service.fileDownload(authorization, id);
        return ResponseEntity.ok()
            .contentType(mediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(file.content());
    }

    @PostMapping
    public ReceiptVoucher create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.create(authorization, body);
    }

    @PutMapping("/{id}")
    public ReceiptVoucher update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.update(authorization, id, body);
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam Map<String, String> params,
        @RequestParam("file") MultipartFile file
    ) {
        return service.upload(authorization, file, params);
    }

    private MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
