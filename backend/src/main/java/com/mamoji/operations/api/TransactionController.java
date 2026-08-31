package com.mamoji.operations.api;

import com.mamoji.common.PagedResponse;
import com.mamoji.operations.application.TransactionApplicationService;
import com.mamoji.operations.application.TransactionMutationService;
import com.mamoji.operations.application.TransactionQueryService;
import com.mamoji.operations.application.TransactionRefundService;
import com.mamoji.operations.domain.RefundTransactionCommand;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.operations.domain.TransactionSummary;
import com.mamoji.operations.domain.UpdateTransactionCommand;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import com.mamoji.service.TransactionImportService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionApplicationService applicationService;
    private final TransactionMutationService mutationService;
    private final TransactionQueryService queryService;
    private final TransactionRefundService refundService;
    private final TransactionImportService importService;

    public TransactionController(
        TransactionApplicationService applicationService,
        TransactionMutationService mutationService,
        TransactionQueryService queryService,
        TransactionRefundService refundService,
        TransactionImportService importService
    ) {
        this.applicationService = applicationService;
        this.mutationService = mutationService;
        this.queryService = queryService;
        this.refundService = refundService;
        this.importService = importService;
    }

    @GetMapping
    public PagedResponse<TransactionRecord> list(
        @CurrentActor ActorContext actor,
        @Valid @ModelAttribute TransactionQueryRequest request
    ) {
        return queryService.list(actor, request);
    }

    @GetMapping("/summary")
    public TransactionSummary summary(
        @CurrentActor ActorContext actor,
        @Valid @ModelAttribute TransactionQueryRequest request
    ) {
        return queryService.summary(actor, request);
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate(
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("mamoji-transaction-import.csv", StandardCharsets.UTF_8).build().toString())
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
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return queryService.get(actor, id, companyId);
    }

    @PostMapping
    public Map<String, Object> create(
        @CurrentActor ActorContext actor,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody TransactionCreateRequest request
    ) {
        return applicationService.create(actor, request, idempotencyKey);
    }

    @PutMapping("/{id}")
    public TransactionRecord update(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @Valid @RequestBody TransactionUpdateRequest request
    ) {
        return mutationService.update(actor, id, new UpdateTransactionCommand(
            companyId == null ? request.companyId() : companyId,
            request.amount(),
            request.categoryId(),
            request.accountId(),
            request.date(),
            request.note()
        ));
    }

    @DeleteMapping("/{id}")
    public void delete(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        mutationService.delete(actor, id, companyId);
    }

    @GetMapping("/refundable")
    public List<TransactionRecord> refundable(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return refundService.refundable(actor, companyId);
    }

    @PostMapping("/{id}/refund")
    public Map<String, Object> refund(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId,
        @Valid @RequestBody TransactionRefundRequest request
    ) {
        return refundService.refund(actor, id, new RefundTransactionCommand(
            companyId == null ? request.companyId() : companyId,
            request.amount(),
            request.date(),
            request.note()
        ));
    }
}
