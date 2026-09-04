package com.mamoji.evidence.api;

import com.mamoji.common.PagedResponse;
import com.mamoji.evidence.application.ReceiptApplicationService;
import com.mamoji.evidence.application.ReceiptFileDownload;
import com.mamoji.evidence.domain.ReceiptSummary;
import com.mamoji.evidence.domain.ReceiptVoucher;
import com.mamoji.platform.audit.domain.AuditLog;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    private final ReceiptApplicationService service;

    public ReceiptController(ReceiptApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResponse<ReceiptVoucher> list(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @ModelAttribute ReceiptQueryRequest request
    ) {
        return service.list(authorization, request.toQuery());
    }

    @GetMapping("/summary")
    public ReceiptSummary summary(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @ModelAttribute ReceiptSummaryRequest request
    ) {
        return service.summary(authorization, request.companyId());
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
        ReceiptFileDownload file = service.fileDownload(authorization, id);
        return ResponseEntity.ok()
            .contentType(mediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.content());
    }

    @PostMapping
    public ReceiptVoucher create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @RequestBody ReceiptCreateRequest request
    ) {
        return service.create(authorization, request.toCommand());
    }

    @PutMapping("/{id}")
    public ReceiptVoucher update(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @Valid @RequestBody ReceiptUpdateRequest request
    ) {
        return service.update(authorization, id, request.toCommand());
    }

    @PostMapping("/upload")
    public ReceiptUploadResponse upload(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @ModelAttribute ReceiptUploadRequest request,
        @RequestParam("file") MultipartFile file
    ) {
        return ReceiptUploadResponse.uploaded(service.upload(authorization, file, request.toCommand()));
    }

    @PostMapping("/batch-upload")
    public ReceiptBatchUploadResponse batchUpload(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @Valid @ModelAttribute ReceiptUploadRequest request,
        @RequestParam("files") List<MultipartFile> files
    ) {
        return ReceiptBatchUploadResponse.from(service.batchUpload(authorization, files, request.toCommand()));
    }

    private MediaType mediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
