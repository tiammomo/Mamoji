package com.mamoji.evidence.application;

import com.mamoji.common.PagedResponse;
import com.mamoji.platform.audit.domain.AuditLog;
import com.mamoji.platform.tenant.Company;
import com.mamoji.evidence.domain.ReceiptSummary;
import com.mamoji.evidence.domain.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.domain.TransactionRecord;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.ObjectStorageService.StoredObject;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ReceiptFileValidator.InvalidReceiptFileException;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.service.support.DomainSupport.require;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class ReceiptApplicationService implements ReceiptApprovalStatusService {
    private static final BigDecimal LARGE_AMOUNT = new BigDecimal("10000");
    private static final String DEFAULT_FILE_NAME = "receipt-attachment";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final BigDecimal CRITICAL_AMOUNT = new BigDecimal("50000");

    private final AccessControlService accessControl;
    private final AuditTrailService auditTrail;
    private final ReceiptVoucherRepository receiptVouchers;
    private final ReceiptFileHashRepository receiptFileHashes;
    private final TransactionQueryRepository transactions;
    private final ObjectStorageService objectStorageService;
    private final OutboxEventService outboxEventService;

    public ReceiptApplicationService(
        AccessControlService accessControl,
        AuditTrailService auditTrail,
        ReceiptVoucherRepository receiptVouchers,
        ReceiptFileHashRepository receiptFileHashes,
        TransactionQueryRepository transactions,
        ObjectStorageService objectStorageService,
        OutboxEventService outboxEventService
    ) {
        this.accessControl = accessControl;
        this.auditTrail = auditTrail;
        this.receiptVouchers = receiptVouchers;
        this.receiptFileHashes = receiptFileHashes;
        this.transactions = transactions;
        this.objectStorageService = objectStorageService;
        this.outboxEventService = outboxEventService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<ReceiptVoucher> list(String authorization, ReceiptListQuery query) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, query.companyId());
        validateQueryBoundaries(query);
        return receiptVouchers.findPage(company.id, query);
    }

    @Transactional(readOnly = true)
    public ReceiptSummary summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return receiptVouchers.summarize(company.id);
    }

    @Transactional
    public ReceiptVoucher create(String authorization, ReceiptCreateCommand request) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, request.companyId());
        String voucherType = valueOr(request.voucherType(), "purchase_invoice");
        requireReceiptCreatePermission(user, company.id, voucherType);
        BigDecimal amount = valueOr(request.amount(), BigDecimal.ZERO);
        BigDecimal taxAmount = valueOr(request.taxAmount(), BigDecimal.ZERO);
        LocalDate issueDate = valueOr(request.issueDate(), LocalDate.now());
        validateVoucherBoundaries(amount, taxAmount, issueDate, request.dueDate());
        ReceiptVoucher voucher = receiptVouchers.insert(new ReceiptVoucherDraft(
            company.id,
            validateTransaction(user, request.transactionId(), company.id).map(tx -> tx.id).orElse(null),
            valueOr(request.voucherNo(), nextVoucherNo()),
            valueOr(request.title(), "新票据凭证"),
            voucherType,
            valueOr(request.direction(), "expense"),
            valueOr(request.counterparty(), "待补充"),
            amount,
            taxAmount,
            issueDate.toString(),
            stringValue(request.dueDate()),
            valueOr(request.status(), "pending_review"),
            nullableTextValue(request.fileName()),
            valueOr(request.fileSize(), 0L),
            nullableTextValue(request.fileType()),
            "low",
            nullableTextValue(request.note()),
            user.id
        ));
        applyCreateFields(voucher, request);
        voucher.riskLevel = riskFor(voucher);
        receiptVouchers.save(voucher);
        logVoucher(user, voucher, "create", "创建票据凭证「" + voucher.title + "」");
        return voucher;
    }

    @Transactional
    public ReceiptVoucher update(String authorization, long id, ReceiptUpdateCommand request) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = requireReceiptVoucherForUpdate(id);
        accessControl.resolveCompany(user, voucher.companyId);
        requireReceiptWritePermission(user, voucher.companyId);
        String previousSnapshot = workflowSnapshot(voucher);
        applyUpdateFields(user, voucher, request);
        validateVoucherBoundaries(
            voucher.amount,
            voucher.taxAmount,
            LocalDate.parse(voucher.issueDate),
            voucher.dueDate == null ? null : LocalDate.parse(voucher.dueDate)
        );
        voucher.riskLevel = riskFor(voucher);
        touch(voucher);
        receiptVouchers.save(voucher);
        String summary = updateSummary(previousSnapshot, voucher);
        logVoucher(user, voucher, "update", summary);
        return voucher;
    }

    /**
     * Workflow-only entry point. User-facing receipt endpoints deliberately do not
     * accept approvalStatus so an approval cannot be bypassed with a direct update.
     */
    @Transactional
    @Override
    public void updateApprovalStatus(String authorization, long id, String approvalStatus) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = requireReceiptVoucherForUpdate(id);
        accessControl.resolveCompany(user, voucher.companyId);
        String previousSnapshot = workflowSnapshot(voucher);
        applyApprovalStatus(user, voucher, approvalStatus);
        voucher.riskLevel = riskFor(voucher);
        touch(voucher);
        receiptVouchers.save(voucher);
        logVoucher(user, voucher, "approval_workflow", updateSummary(previousSnapshot, voucher));
    }

    @Transactional
    public ReceiptVoucher upload(String authorization, MultipartFile file, ReceiptUploadCommand request) {
        User user = accessControl.requireUser(authorization);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is required");
        }
        Company company = accessControl.resolveCompany(user, request.companyId());
        String voucherType = valueOr(request.voucherType(), "purchase_invoice");
        requireReceiptCreatePermission(user, company.id, voucherType);
        BigDecimal amount = valueOr(request.amount(), BigDecimal.ZERO);
        BigDecimal taxAmount = valueOr(request.taxAmount(), BigDecimal.ZERO);
        LocalDate issueDate = valueOr(request.issueDate(), LocalDate.now());
        validateVoucherBoundaries(amount, taxAmount, issueDate, request.dueDate());
        String fileHash = sha256(file);
        receiptFileHashes.lock(company.id, fileHash);
        OptionalLong duplicateVoucherId = receiptFileHashes.findVoucherId(company.id, fileHash);
        if (duplicateVoucherId.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate receipt file; existing voucher #" + duplicateVoucherId.getAsLong());
        }
        String filename = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
        StoredObject storedObject;
        try {
            storedObject = objectStorageService.storeReceiptFile(company.id, file);
        } catch (InvalidReceiptFileException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage upload failed");
        }
        ReceiptVoucher voucher = receiptVouchers.insert(new ReceiptVoucherDraft(
            company.id,
            validateTransaction(user, request.transactionId(), company.id).map(tx -> tx.id).orElse(null),
            valueOr(request.voucherNo(), nextVoucherNo()),
            valueOr(request.title(), filename),
            voucherType,
            valueOr(request.direction(), "expense"),
            valueOr(request.counterparty(), "待补充"),
            amount,
            taxAmount,
            issueDate.toString(),
            stringValue(request.dueDate()),
            valueOr(request.status(), "pending_review"),
            filename,
            file.getSize(),
            storedObject.contentType(),
            "low",
            nullableTextValue(request.note()),
            user.id
        ));
        voucher.taxRate = valueOr(request.taxRate(), voucher.taxRate);
        if (request.taxPeriod() != null) {
            voucher.taxPeriod = request.taxPeriod();
        }
        voucher.invoiceCheckStatus = valueOr(request.invoiceCheckStatus(), voucher.invoiceCheckStatus);
        voucher.deductionStatus = valueOr(request.deductionStatus(), voucher.deductionStatus);
        voucher.reimbursementStatus = valueOr(request.reimbursementStatus(), voucher.reimbursementStatus);
        voucher.businessPurpose = nullableTextValue(request.businessPurpose());
        voucher.expenseOwner = nullableTextValue(request.expenseOwner());
        voucher.fileStorageProvider = storedObject.provider();
        voucher.fileBucket = storedObject.bucket();
        voucher.fileObjectKey = storedObject.objectKey();
        voucher.fileUrl = storedObject.url();
        voucher.riskLevel = riskFor(voucher);
        receiptVouchers.save(voucher);
        receiptFileHashes.register(
            company.id,
            voucher.id,
            fileHash,
            filename,
            file.getSize(),
            OffsetDateTime.now().toString()
        );
        logVoucher(user, voucher, "upload", "上传并创建票据凭证「" + voucher.title + "」");
        return voucher;
    }

    @Transactional
    public ReceiptBatchUploadResult batchUpload(
        String authorization,
        List<MultipartFile> files,
        ReceiptUploadCommand request
    ) {
        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one receipt file is required");
        }
        if (files.size() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload at most 8 receipt files at a time");
        }
        List<ReceiptVoucher> uploaded = new ArrayList<>();
        List<ReceiptUploadFailure> failed = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                uploaded.add(upload(authorization, file, request));
            } catch (ResponseStatusException ex) {
                failed.add(new ReceiptUploadFailure(
                    Objects.toString(file.getOriginalFilename(), "receipt"),
                    Objects.toString(ex.getReason(), "Upload failed"),
                    ex.getStatusCode().value()
                ));
            }
        }
        return new ReceiptBatchUploadResult(uploaded, failed);
    }

    private String sha256(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt file could not be read");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public List<AuditLog> auditLogs(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = requireReceiptVoucher(id);
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return auditTrail.findByEntity(voucher.companyId, "receipt_voucher", voucher.id);
    }

    public Map<String, Object> fileLink(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = requireReceiptVoucher(id);
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        try {
            String url = objectStorageService
                .presignedDownloadUrl(
                    voucher.fileStorageProvider,
                    voucher.fileBucket,
                    voucher.fileObjectKey,
                    voucher.fileUrl,
                    voucher.fileName
                )
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt file is not stored in object storage"));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("provider", voucher.fileStorageProvider);
            result.put("objectKey", voucher.fileObjectKey);
            result.put("expiresInSeconds", objectStorageService.presignedUrlExpirySeconds());
            return result;
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage link failed");
        }
    }

    public ReceiptFileDownload fileDownload(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = requireReceiptVoucher(id);
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        try {
            byte[] content = objectStorageService
                .downloadObject(voucher.fileStorageProvider, voucher.fileBucket, voucher.fileObjectKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt file is not stored in object storage"));
            return new ReceiptFileDownload(
                content,
                isBlank(voucher.fileName) ? DEFAULT_FILE_NAME : voucher.fileName,
                isBlank(voucher.fileType) ? DEFAULT_CONTENT_TYPE : voucher.fileType
            );
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage download failed");
        }
    }

    private void validateQueryBoundaries(ReceiptListQuery query) {
        if (query.startDate() != null
            && query.endDate() != null
            && query.startDate().isAfter(query.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must not be after endDate");
        }
        if (query.minAmount() != null
            && query.maxAmount() != null
            && query.minAmount().compareTo(query.maxAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minAmount must not exceed maxAmount");
        }
    }

    private void validateVoucherBoundaries(
        BigDecimal amount,
        BigDecimal taxAmount,
        LocalDate issueDate,
        LocalDate dueDate
    ) {
        if (taxAmount.compareTo(amount) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxAmount must not exceed amount");
        }
        BigDecimal taxExcludedAmount = amount.subtract(taxAmount);
        if (taxExcludedAmount.signum() > 0 && taxAmount.compareTo(taxExcludedAmount) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxAmount implies a taxRate above 100");
        }
        if (dueDate != null && dueDate.isBefore(issueDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate must not be before issueDate");
        }
    }

    private void applyCreateFields(ReceiptVoucher voucher, ReceiptCreateCommand request) {
        if (request.taxRate() != null) {
            voucher.taxRate = request.taxRate();
        }
        if (request.taxPeriod() != null) {
            voucher.taxPeriod = request.taxPeriod();
        }
        if (request.invoiceCheckStatus() != null) {
            voucher.invoiceCheckStatus = request.invoiceCheckStatus();
        }
        if (request.deductionStatus() != null) {
            voucher.deductionStatus = request.deductionStatus();
        }
        if (request.reimbursementStatus() != null) {
            voucher.reimbursementStatus = request.reimbursementStatus();
        }
        if (request.accountingStatus() != null) {
            applyAccountingStatus(voucher, request.accountingStatus());
        }
        if (request.accountingVoucherNo() != null) {
            voucher.accountingVoucherNo = nullableTextValue(request.accountingVoucherNo());
        }
        if (request.accountingEntry() != null) {
            voucher.accountingEntry = nullableTextValue(request.accountingEntry());
        }
        if (request.businessPurpose() != null) {
            voucher.businessPurpose = nullableTextValue(request.businessPurpose());
        }
        if (request.expenseOwner() != null) {
            voucher.expenseOwner = nullableTextValue(request.expenseOwner());
        }
    }

    private void applyUpdateFields(User user, ReceiptVoucher voucher, ReceiptUpdateCommand request) {
        if (request.transactionIdPresent()) {
            voucher.transactionId = validateTransaction(
                user,
                request.transactionId(),
                voucher.companyId
            ).map(tx -> tx.id).orElse(null);
        }
        if (request.voucherNo() != null) {
            voucher.voucherNo = request.voucherNo();
        }
        if (request.title() != null) {
            voucher.title = request.title();
        }
        if (request.voucherType() != null) {
            voucher.voucherType = request.voucherType();
            if (request.invoiceCheckStatus() == null) {
                voucher.invoiceCheckStatus = switch (voucher.voucherType) {
                    case "sales_invoice", "purchase_invoice" -> "pending";
                    default -> "not_required";
                };
            }
            if (request.deductionStatus() == null) {
                voucher.deductionStatus = "purchase_invoice".equals(voucher.voucherType) ? "pending" : "not_applicable";
            }
            if (request.reimbursementStatus() == null) {
                voucher.reimbursementStatus = "reimbursement".equals(voucher.voucherType) ? "submitted" : "not_applicable";
            }
        }
        if (request.direction() != null) {
            voucher.direction = request.direction();
        }
        if (request.counterparty() != null) {
            voucher.counterparty = request.counterparty();
        }
        if (request.amount() != null) {
            voucher.amount = request.amount();
        }
        if (request.taxAmount() != null) {
            voucher.taxAmount = request.taxAmount();
        }
        if (request.taxRate() != null) {
            voucher.taxRate = request.taxRate();
        }
        if (request.taxPeriodPresent()) {
            voucher.taxPeriod = nullableTextValue(request.taxPeriod());
        }
        if (request.invoiceCheckStatus() != null) {
            voucher.invoiceCheckStatus = request.invoiceCheckStatus();
        }
        if (request.deductionStatus() != null) {
            voucher.deductionStatus = request.deductionStatus();
        }
        if (request.reimbursementStatus() != null) {
            voucher.reimbursementStatus = request.reimbursementStatus();
        }
        if (request.accountingStatus() != null) {
            applyAccountingStatus(voucher, request.accountingStatus());
        }
        if (request.accountingVoucherNoPresent()) {
            voucher.accountingVoucherNo = nullableTextValue(request.accountingVoucherNo());
        }
        if (request.accountingEntryPresent()) {
            voucher.accountingEntry = nullableTextValue(request.accountingEntry());
        }
        if (request.businessPurposePresent()) {
            voucher.businessPurpose = nullableTextValue(request.businessPurpose());
        }
        if (request.expenseOwnerPresent()) {
            voucher.expenseOwner = nullableTextValue(request.expenseOwner());
        }
        if (request.issueDate() != null) {
            voucher.issueDate = request.issueDate().toString();
        }
        if (request.dueDatePresent()) {
            voucher.dueDate = stringValue(request.dueDate());
        }
        if (request.status() != null) {
            voucher.status = request.status();
        }
        if (request.fileNamePresent()) {
            voucher.fileName = nullableTextValue(request.fileName());
        }
        if (request.fileSize() != null) {
            voucher.fileSize = request.fileSize();
        }
        if (request.fileTypePresent()) {
            voucher.fileType = nullableTextValue(request.fileType());
        }
        if (request.notePresent()) {
            voucher.note = nullableTextValue(request.note());
        }
        voucher.operatorUserId = user.id;
    }

    private void applyApprovalStatus(User user, ReceiptVoucher voucher, String approvalStatus) {
        String nextApprovalStatus = normalizeApprovalStatus(approvalStatus);
        if ("approved".equals(nextApprovalStatus) && !"approved".equals(voucher.approvalStatus)) {
            voucher.approvedByUserId = user.id;
            voucher.approvedAt = OffsetDateTime.now().toString();
            if ("submitted".equals(voucher.reimbursementStatus)) {
                voucher.reimbursementStatus = "approved";
            }
        }
        voucher.approvalStatus = nextApprovalStatus;
    }

    private void applyAccountingStatus(ReceiptVoucher voucher, String nextAccountingStatus) {
        if ("posted".equals(nextAccountingStatus) && !"posted".equals(voucher.accountingStatus)) {
            if (!Set.of("approved", "not_required").contains(voucher.approvalStatus)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete or waive approval before posting the accounting voucher");
            }
            voucher.accountedAt = OffsetDateTime.now().toString();
            if (isBlank(voucher.accountingVoucherNo)) {
                String period = isBlank(voucher.taxPeriod) ? voucher.issueDate.substring(0, 7) : voucher.taxPeriod;
                voucher.accountingVoucherNo = "JV-" + period.replace("-", "") + "-" + String.format("%04d", voucher.id);
            }
        }
        voucher.accountingStatus = nextAccountingStatus;
    }

    private Optional<TransactionRecord> validateTransaction(User user, Long transactionId, long companyId) {
        if (transactionId == null || transactionId == 0) {
            return Optional.empty();
        }
        TransactionRecord transaction = require(transactions.findById(transactionId).orElse(null), "Transaction not found");
        if (transaction.userId != user.id || !Objects.equals(transaction.companyId, companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden transaction");
        }
        return Optional.of(transaction);
    }

    private String riskFor(ReceiptVoucher voucher) {
        if ("rejected".equals(voucher.status)) {
            return "critical";
        }
        boolean active = !"verified".equals(voucher.status) && !"linked".equals(voucher.status) && !"archived".equals(voucher.status);
        if (active && voucher.dueDate != null && voucher.dueDate.compareTo(LocalDate.now().toString()) < 0) {
            return "high";
        }
        if (!"not_required".equals(voucher.invoiceCheckStatus) && !"verified".equals(voucher.invoiceCheckStatus)) {
            return "high";
        }
        if ("purchase_invoice".equals(voucher.voucherType) && ("pending".equals(voucher.deductionStatus) || "deductible".equals(voucher.deductionStatus))) {
            return "medium";
        }
            if ("pending".equals(voucher.approvalStatus) || "not_submitted".equals(voucher.approvalStatus)) {
            return "medium";
        }
        if ("not_started".equals(voucher.accountingStatus) && !"pending_review".equals(voucher.status)) {
            return "medium";
        }
        if ("reimbursement".equals(voucher.voucherType) && !List.of("paid", "archived").contains(voucher.reimbursementStatus)) {
            return "medium";
        }
        if ((voucher.voucherType.equals("sales_invoice") || voucher.voucherType.equals("purchase_invoice") || voucher.voucherType.equals("tax_receipt"))
            && isBlank(voucher.taxPeriod)) {
            return "medium";
        }
        if (voucher.amount.compareTo(CRITICAL_AMOUNT) >= 0 && voucher.transactionId == null) {
            return "high";
        }
        if (voucher.amount.compareTo(LARGE_AMOUNT) >= 0 || voucher.fileName == null || voucher.fileName.isBlank()) {
            return "medium";
        }
        return "low";
    }

    private String nextVoucherNo() {
        return "RC-" + LocalDate.now().toString().replace("-", "") + "-" + (receiptVouchers.count() + 1);
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static String stringValue(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String nullableTextValue(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ReceiptVoucher requireReceiptVoucher(long id) {
        return receiptVouchers.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt voucher not found"));
    }

    private ReceiptVoucher requireReceiptVoucherForUpdate(long id) {
        return receiptVouchers.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt voucher not found"));
    }

    private String normalizeApprovalStatus(String value) {
        return switch (value) {
            case "not_required", "not_submitted", "pending", "approved", "rejected" -> value;
            default -> "not_required";
        };
    }

    private String normalizeAccountingStatus(String value) {
        return switch (value) {
            case "not_started", "draft", "posted", "reversed" -> value;
            default -> "not_started";
        };
    }

    private void requireReceiptWritePermission(User user, long companyId) {
        accessControl.requireFinanceManager(user, companyId);
    }

    private void requireReceiptCreatePermission(User user, long companyId, String voucherType) {
        if ("reimbursement".equals(voucherType)) {
            return;
        }
        requireReceiptWritePermission(user, companyId);
    }

    private String workflowSnapshot(ReceiptVoucher voucher) {
        return String.join("|",
            text(voucher.status),
            text(voucher.invoiceCheckStatus),
            text(voucher.deductionStatus),
            text(voucher.reimbursementStatus),
            text(voucher.approvalStatus),
            text(voucher.accountingStatus)
        );
    }

    private String updateSummary(String previousSnapshot, ReceiptVoucher voucher) {
        String currentSnapshot = workflowSnapshot(voucher);
        if (previousSnapshot.equals(currentSnapshot)) {
            return "更新票据基础信息「" + voucher.title + "」";
        }
        if (currentSnapshot.endsWith("|posted")) {
            return "会计过账「" + voucher.title + "」" + (isBlank(voucher.accountingVoucherNo) ? "" : "，凭证号 " + voucher.accountingVoucherNo);
        }
        if (currentSnapshot.contains("|approved|")) {
            return "审批通过票据「" + voucher.title + "」";
        }
        if ("verified".equals(voucher.invoiceCheckStatus)) {
            return "完成发票查验「" + voucher.title + "」";
        }
        return "更新票据流程状态「" + voucher.title + "」";
    }

    private void logVoucher(User user, ReceiptVoucher voucher, String action, String summary) {
        auditTrail.record(voucher.companyId, "receipt_voucher", voucher.id, action, summary, user.id, user.nickname);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("voucherNo", voucher.voucherNo);
        payload.put("voucherType", voucher.voucherType);
        payload.put("status", voucher.status);
        payload.put("invoiceCheckStatus", voucher.invoiceCheckStatus);
        payload.put("deductionStatus", voucher.deductionStatus);
        payload.put("approvalStatus", voucher.approvalStatus);
        payload.put("accountingStatus", voucher.accountingStatus);
        payload.put("amount", voucher.amount);
        payload.put("taxAmount", voucher.taxAmount);
        outboxEventService.publish(
            "receipt_voucher." + action,
            voucher.companyId,
            "receipt_voucher",
            voucher.id,
            user.id,
            payload
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
