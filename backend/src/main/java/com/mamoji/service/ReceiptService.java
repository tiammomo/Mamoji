package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.AuditLog;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.service.support.ObjectStorageService;
import com.mamoji.service.support.ObjectStorageService.StoredObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.decimalParam;
import static com.mamoji.common.PayloadReader.longParam;
import static com.mamoji.common.PayloadReader.longValue;
import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.text;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.require;
import static com.mamoji.service.support.DomainSupport.touch;

@Service
public class ReceiptService {
    private static final BigDecimal LARGE_AMOUNT = new BigDecimal("10000");
    private static final BigDecimal CRITICAL_AMOUNT = new BigDecimal("50000");

    private final AccessControlService accessControl;
    private final EnterpriseStore enterpriseStore;
    private final InMemoryStore coreStore;
    private final ObjectStorageService objectStorageService;

    public ReceiptService(
        AccessControlService accessControl,
        EnterpriseStore enterpriseStore,
        InMemoryStore coreStore,
        ObjectStorageService objectStorageService
    ) {
        this.accessControl = accessControl;
        this.enterpriseStore = enterpriseStore;
        this.coreStore = coreStore;
        this.objectStorageService = objectStorageService;
    }

    public PagedResponse<ReceiptVoucher> list(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        List<ReceiptVoucher> vouchers = enterpriseStore.sortedReceiptVouchers(company.id).stream()
            .filter(voucher -> filterVoucher(voucher, params))
            .toList();
        return PagedResponse.of(vouchers, PageRequest.from(params));
    }

    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<ReceiptVoucher> vouchers = enterpriseStore.sortedReceiptVouchers(company.id);
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal salesInvoiceAmount = BigDecimal.ZERO;
        BigDecimal purchaseInvoiceAmount = BigDecimal.ZERO;
        BigDecimal outputTaxAmount = BigDecimal.ZERO;
        BigDecimal deductibleTaxAmount = BigDecimal.ZERO;
        BigDecimal reimbursementAmount = BigDecimal.ZERO;
        BigDecimal reimbursementPendingAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        long missingAttachmentCount = 0;
        long missingTransactionCount = 0;
        long pendingReviewCount = 0;
        long highRiskCount = 0;
        long uncheckedInvoiceCount = 0;
        long pendingDeductionCount = 0;
        long pendingReimbursementCount = 0;
        long missingTaxPeriodCount = 0;
        long pendingApprovalCount = 0;
        long pendingAccountingCount = 0;
        long postedAccountingCount = 0;

        for (ReceiptVoucher voucher : vouchers) {
            totalAmount = totalAmount.add(voucher.amount);
            if ("sales_invoice".equals(voucher.voucherType)) {
                salesInvoiceAmount = salesInvoiceAmount.add(voucher.amount);
                outputTaxAmount = outputTaxAmount.add(voucher.taxAmount);
            }
            if ("purchase_invoice".equals(voucher.voucherType)) {
                purchaseInvoiceAmount = purchaseInvoiceAmount.add(voucher.amount);
                deductibleTaxAmount = deductibleTaxAmount.add(voucher.taxAmount);
            }
            if ("reimbursement".equals(voucher.voucherType)) {
                reimbursementAmount = reimbursementAmount.add(voucher.amount);
                if (!List.of("paid", "archived").contains(voucher.reimbursementStatus)) {
                    reimbursementPendingAmount = reimbursementPendingAmount.add(voucher.amount);
                    pendingReimbursementCount++;
                }
            }
            if ("pending_review".equals(voucher.status)) {
                pendingAmount = pendingAmount.add(voucher.amount);
                pendingReviewCount++;
            }
            if (voucher.fileName == null || voucher.fileName.isBlank()) {
                missingAttachmentCount++;
            }
            if (voucher.transactionId == null) {
                missingTransactionCount++;
            }
            if ("high".equals(voucher.riskLevel) || "critical".equals(voucher.riskLevel)) {
                highRiskCount++;
            }
            if (!"not_required".equals(voucher.invoiceCheckStatus) && !"verified".equals(voucher.invoiceCheckStatus)) {
                uncheckedInvoiceCount++;
            }
            if ("pending".equals(voucher.deductionStatus) || "deductible".equals(voucher.deductionStatus)) {
                pendingDeductionCount++;
            }
            if ((voucher.taxPeriod == null || voucher.taxPeriod.isBlank())
                && (voucher.voucherType.equals("sales_invoice") || voucher.voucherType.equals("purchase_invoice") || voucher.voucherType.equals("tax_receipt"))) {
                missingTaxPeriodCount++;
            }
            if ("pending".equals(voucher.approvalStatus)) {
                pendingApprovalCount++;
            }
            if ("not_started".equals(voucher.accountingStatus) || "draft".equals(voucher.accountingStatus)) {
                pendingAccountingCount++;
            }
            if ("posted".equals(voucher.accountingStatus)) {
                postedAccountingCount++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", vouchers.size());
        result.put("totalAmount", totalAmount);
        result.put("salesInvoiceAmount", salesInvoiceAmount);
        result.put("purchaseInvoiceAmount", purchaseInvoiceAmount);
        result.put("outputTaxAmount", outputTaxAmount);
        result.put("deductibleTaxAmount", deductibleTaxAmount);
        result.put("reimbursementAmount", reimbursementAmount);
        result.put("reimbursementPendingAmount", reimbursementPendingAmount);
        result.put("pendingAmount", pendingAmount);
        result.put("pendingReviewCount", pendingReviewCount);
        result.put("missingAttachmentCount", missingAttachmentCount);
        result.put("missingTransactionCount", missingTransactionCount);
        result.put("highRiskCount", highRiskCount);
        result.put("uncheckedInvoiceCount", uncheckedInvoiceCount);
        result.put("pendingDeductionCount", pendingDeductionCount);
        result.put("pendingReimbursementCount", pendingReimbursementCount);
        result.put("missingTaxPeriodCount", missingTaxPeriodCount);
        result.put("pendingApprovalCount", pendingApprovalCount);
        result.put("pendingAccountingCount", pendingAccountingCount);
        result.put("postedAccountingCount", postedAccountingCount);
        return result;
    }

    public ReceiptVoucher create(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        String voucherType = normalizeVoucherType(textOr(body.get("voucherType"), "purchase_invoice"));
        requireReceiptWritePermission(authorization, voucherType);
        ReceiptVoucher voucher = enterpriseStore.receiptVoucher(
            company.id,
            validateTransaction(user, optionalLong(body.get("transactionId")).orElse(null)).map(tx -> tx.id).orElse(null),
            textOr(body.get("voucherNo"), nextVoucherNo()),
            textOr(body.get("title"), "新票据凭证"),
            voucherType,
            normalizeDirection(textOr(body.get("direction"), "expense")),
            textOr(body.get("counterparty"), "待补充"),
            String.valueOf(number(body.get("amount"), BigDecimal.ZERO)),
            String.valueOf(number(body.get("taxAmount"), BigDecimal.ZERO)),
            validateDate("issueDate", textOr(body.get("issueDate"), LocalDate.now().toString())),
            validateOptionalDate("dueDate", nullableText(body.get("dueDate"))),
            normalizeStatus(textOr(body.get("status"), "pending_review")),
            nullableText(body.get("fileName")),
            longValue(body.get("fileSize"), 0),
            nullableText(body.get("fileType")),
            "low",
            nullableText(body.get("note")),
            user.id
        );
        applyVoucherFields(user, voucher, body);
        voucher.riskLevel = riskFor(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        logVoucher(user, voucher, "create", "创建票据凭证「" + voucher.title + "」");
        return voucher;
    }

    public ReceiptVoucher update(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireFinanceManager(authorization);
        ReceiptVoucher voucher = require(enterpriseStore.receiptVouchers.get(id), "Receipt voucher not found");
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        String previousSnapshot = workflowSnapshot(voucher);
        applyVoucherFields(user, voucher, body);
        voucher.riskLevel = riskFor(voucher);
        touch(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        String summary = updateSummary(previousSnapshot, voucher);
        logVoucher(user, voucher, "update", summary);
        return voucher;
    }

    public Map<String, Object> upload(String authorization, MultipartFile file, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is required");
        }
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        String filename = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
        String voucherType = normalizeVoucherType(params.getOrDefault("voucherType", "purchase_invoice"));
        requireReceiptWritePermission(authorization, voucherType);
        StoredObject storedObject;
        try {
            storedObject = objectStorageService.storeReceiptFile(company.id, file);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage upload failed");
        }
        ReceiptVoucher voucher = enterpriseStore.receiptVoucher(
            company.id,
            validateTransaction(user, longParam(params, "transactionId", 0)).map(tx -> tx.id).orElse(null),
            params.getOrDefault("voucherNo", nextVoucherNo()),
            params.getOrDefault("title", filename),
            voucherType,
            normalizeDirection(params.getOrDefault("direction", "expense")),
            params.getOrDefault("counterparty", "待补充"),
            String.valueOf(decimalParam(params, "amount", BigDecimal.ZERO)),
            String.valueOf(decimalParam(params, "taxAmount", BigDecimal.ZERO)),
            validateDate("issueDate", params.getOrDefault("issueDate", LocalDate.now().toString())),
            validateOptionalDate("dueDate", params.get("dueDate")),
            normalizeStatus(params.getOrDefault("status", "pending_review")),
            filename,
            file.getSize(),
            file.getContentType(),
            "low",
            params.get("note"),
            user.id
        );
        voucher.taxRate = decimalParam(params, "taxRate", voucher.taxRate);
        if (params.containsKey("taxPeriod")) {
            voucher.taxPeriod = nullableText(params.get("taxPeriod"));
        }
        voucher.invoiceCheckStatus = normalizeInvoiceCheckStatus(params.getOrDefault("invoiceCheckStatus", voucher.invoiceCheckStatus));
        voucher.deductionStatus = normalizeDeductionStatus(params.getOrDefault("deductionStatus", voucher.deductionStatus));
        voucher.reimbursementStatus = normalizeReimbursementStatus(params.getOrDefault("reimbursementStatus", voucher.reimbursementStatus));
        voucher.businessPurpose = params.containsKey("businessPurpose") ? nullableText(params.get("businessPurpose")) : voucher.businessPurpose;
        voucher.expenseOwner = params.containsKey("expenseOwner") ? nullableText(params.get("expenseOwner")) : voucher.expenseOwner;
        voucher.fileStorageProvider = storedObject.provider();
        voucher.fileBucket = storedObject.bucket();
        voucher.fileObjectKey = storedObject.objectKey();
        voucher.fileUrl = storedObject.url();
        voucher.riskLevel = riskFor(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        logVoucher(user, voucher, "upload", "上传并创建票据凭证「" + voucher.title + "」");
        return Map.of(
            "success", true,
            "voucher", voucher,
            "message", "Receipt uploaded"
        );
    }

    public List<AuditLog> auditLogs(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = require(enterpriseStore.receiptVouchers.get(id), "Receipt voucher not found");
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return enterpriseStore.sortedAuditLogs(voucher.companyId, "receipt_voucher", voucher.id);
    }

    public Map<String, Object> fileLink(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = require(enterpriseStore.receiptVouchers.get(id), "Receipt voucher not found");
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        try {
            String url = objectStorageService
                .presignedDownloadUrl(voucher.fileStorageProvider, voucher.fileBucket, voucher.fileObjectKey, voucher.fileUrl)
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

    private boolean filterVoucher(ReceiptVoucher voucher, Map<String, String> params) {
        String keyword = params.getOrDefault("keyword", "").toLowerCase();
        if (!keyword.isBlank()
            && !text(voucher.title).toLowerCase().contains(keyword)
            && !text(voucher.voucherNo).toLowerCase().contains(keyword)
            && !text(voucher.counterparty).toLowerCase().contains(keyword)
            && !text(voucher.note).toLowerCase().contains(keyword)) {
            return false;
        }
        if (params.get("voucherType") != null && !params.get("voucherType").isBlank() && !voucher.voucherType.equals(params.get("voucherType"))) {
            return false;
        }
        if (params.get("status") != null && !params.get("status").isBlank() && !voucher.status.equals(params.get("status"))) {
            return false;
        }
        if (params.get("direction") != null && !params.get("direction").isBlank() && !voucher.direction.equals(params.get("direction"))) {
            return false;
        }
        if (!isBlank(params.get("invoiceCheckStatus")) && !voucher.invoiceCheckStatus.equals(params.get("invoiceCheckStatus"))) {
            return false;
        }
        if (!isBlank(params.get("deductionStatus")) && !voucher.deductionStatus.equals(params.get("deductionStatus"))) {
            return false;
        }
        if (!isBlank(params.get("reimbursementStatus")) && !voucher.reimbursementStatus.equals(params.get("reimbursementStatus"))) {
            return false;
        }
        if (!isBlank(params.get("taxPeriod")) && !params.get("taxPeriod").equals(voucher.taxPeriod)) {
            return false;
        }
        if ("missing".equals(params.get("linkState")) && voucher.transactionId != null) {
            return false;
        }
        if ("linked".equals(params.get("linkState")) && voucher.transactionId == null) {
            return false;
        }
        if (!isBlank(params.get("startDate")) && voucher.issueDate.compareTo(params.get("startDate")) < 0) {
            return false;
        }
        if (!isBlank(params.get("endDate")) && voucher.issueDate.compareTo(params.get("endDate")) > 0) {
            return false;
        }
        if (!isBlank(params.get("minAmount")) && voucher.amount.compareTo(decimalParam(params, "minAmount", voucher.amount)) < 0) {
            return false;
        }
        return isBlank(params.get("maxAmount")) || voucher.amount.compareTo(decimalParam(params, "maxAmount", voucher.amount)) <= 0;
    }

    private void applyVoucherFields(User user, ReceiptVoucher voucher, Map<String, Object> body) {
        if (body.containsKey("transactionId")) {
            voucher.transactionId = validateTransaction(user, optionalLong(body.get("transactionId")).orElse(null)).map(tx -> tx.id).orElse(null);
        }
        if (body.containsKey("voucherNo")) {
            voucher.voucherNo = textOr(body.get("voucherNo"), voucher.voucherNo);
        }
        if (body.containsKey("title")) {
            voucher.title = textOr(body.get("title"), voucher.title);
        }
        if (body.containsKey("voucherType")) {
            voucher.voucherType = normalizeVoucherType(textOr(body.get("voucherType"), voucher.voucherType));
            if (!body.containsKey("invoiceCheckStatus")) {
                voucher.invoiceCheckStatus = switch (voucher.voucherType) {
                    case "sales_invoice", "purchase_invoice" -> "pending";
                    default -> "not_required";
                };
            }
            if (!body.containsKey("deductionStatus")) {
                voucher.deductionStatus = "purchase_invoice".equals(voucher.voucherType) ? "pending" : "not_applicable";
            }
            if (!body.containsKey("reimbursementStatus")) {
                voucher.reimbursementStatus = "reimbursement".equals(voucher.voucherType) ? "submitted" : "not_applicable";
            }
        }
        if (body.containsKey("direction")) {
            voucher.direction = normalizeDirection(textOr(body.get("direction"), voucher.direction));
        }
        if (body.containsKey("counterparty")) {
            voucher.counterparty = textOr(body.get("counterparty"), voucher.counterparty);
        }
        if (body.containsKey("amount")) {
            voucher.amount = number(body.get("amount"), voucher.amount);
        }
        if (body.containsKey("taxAmount")) {
            voucher.taxAmount = number(body.get("taxAmount"), voucher.taxAmount);
        }
        if (body.containsKey("taxRate")) {
            voucher.taxRate = number(body.get("taxRate"), voucher.taxRate);
        }
        if (body.containsKey("taxPeriod")) {
            voucher.taxPeriod = nullableText(body.get("taxPeriod"));
        }
        if (body.containsKey("invoiceCheckStatus")) {
            voucher.invoiceCheckStatus = normalizeInvoiceCheckStatus(textOr(body.get("invoiceCheckStatus"), voucher.invoiceCheckStatus));
        }
        if (body.containsKey("deductionStatus")) {
            voucher.deductionStatus = normalizeDeductionStatus(textOr(body.get("deductionStatus"), voucher.deductionStatus));
        }
        if (body.containsKey("reimbursementStatus")) {
            voucher.reimbursementStatus = normalizeReimbursementStatus(textOr(body.get("reimbursementStatus"), voucher.reimbursementStatus));
        }
        if (body.containsKey("approvalStatus")) {
            String nextApprovalStatus = normalizeApprovalStatus(textOr(body.get("approvalStatus"), voucher.approvalStatus));
            if ("approved".equals(nextApprovalStatus) && !"approved".equals(voucher.approvalStatus)) {
                voucher.approvedByUserId = user.id;
                voucher.approvedAt = InMemoryStore.now();
                if ("submitted".equals(voucher.reimbursementStatus)) {
                    voucher.reimbursementStatus = "approved";
                }
            }
            voucher.approvalStatus = nextApprovalStatus;
        }
        if (body.containsKey("accountingStatus")) {
            String nextAccountingStatus = normalizeAccountingStatus(textOr(body.get("accountingStatus"), voucher.accountingStatus));
            if ("posted".equals(nextAccountingStatus) && !"posted".equals(voucher.accountingStatus)) {
                voucher.accountedAt = InMemoryStore.now();
                if (isBlank(voucher.accountingVoucherNo)) {
                    String period = isBlank(voucher.taxPeriod) ? voucher.issueDate.substring(0, 7) : voucher.taxPeriod;
                    voucher.accountingVoucherNo = "JV-" + period.replace("-", "") + "-" + String.format("%04d", voucher.id);
                }
            }
            voucher.accountingStatus = nextAccountingStatus;
        }
        if (body.containsKey("accountingVoucherNo")) {
            voucher.accountingVoucherNo = nullableText(body.get("accountingVoucherNo"));
        }
        if (body.containsKey("accountingEntry")) {
            voucher.accountingEntry = nullableText(body.get("accountingEntry"));
        }
        if (body.containsKey("businessPurpose")) {
            voucher.businessPurpose = nullableText(body.get("businessPurpose"));
        }
        if (body.containsKey("expenseOwner")) {
            voucher.expenseOwner = nullableText(body.get("expenseOwner"));
        }
        if (body.containsKey("issueDate")) {
            voucher.issueDate = validateDate("issueDate", textOr(body.get("issueDate"), voucher.issueDate));
        }
        if (body.containsKey("dueDate")) {
            voucher.dueDate = validateOptionalDate("dueDate", nullableText(body.get("dueDate")));
        }
        if (body.containsKey("status")) {
            voucher.status = normalizeStatus(textOr(body.get("status"), voucher.status));
        }
        if (body.containsKey("fileName")) {
            voucher.fileName = nullableText(body.get("fileName"));
        }
        if (body.containsKey("fileSize")) {
            voucher.fileSize = longValue(body.get("fileSize"), voucher.fileSize);
        }
        if (body.containsKey("fileType")) {
            voucher.fileType = nullableText(body.get("fileType"));
        }
        if (body.containsKey("note")) {
            voucher.note = nullableText(body.get("note"));
        }
        voucher.operatorUserId = user.id;
    }

    private Optional<TransactionRecord> validateTransaction(User user, Long transactionId) {
        if (transactionId == null || transactionId == 0) {
            return Optional.empty();
        }
        TransactionRecord transaction = require(coreStore.transactions.get(transactionId), "Transaction not found");
        if (transaction.userId != user.id) {
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
        if ("pending".equals(voucher.approvalStatus)) {
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
        return "RC-" + LocalDate.now().toString().replace("-", "") + "-" + (enterpriseStore.receiptVouchers.size() + 1);
    }

    private String validateDate(String field, String value) {
        try {
            LocalDate.parse(value);
            return value;
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must use yyyy-MM-dd format");
        }
    }

    private String validateOptionalDate(String field, String value) {
        return value == null || value.isBlank() ? null : validateDate(field, value);
    }

    private String normalizeVoucherType(String value) {
        return switch (value) {
            case "sales_invoice", "purchase_invoice", "receipt", "bank_slip", "contract", "reimbursement", "tax_receipt" -> value;
            default -> "purchase_invoice";
        };
    }

    private String normalizeInvoiceCheckStatus(String value) {
        return switch (value) {
            case "not_required", "pending", "verified", "failed" -> value;
            default -> "not_required";
        };
    }

    private String normalizeDeductionStatus(String value) {
        return switch (value) {
            case "not_applicable", "pending", "deductible", "deducted", "transferred_out" -> value;
            default -> "not_applicable";
        };
    }

    private String normalizeReimbursementStatus(String value) {
        return switch (value) {
            case "not_applicable", "submitted", "approved", "paid", "archived", "rejected" -> value;
            default -> "not_applicable";
        };
    }

    private String normalizeApprovalStatus(String value) {
        return switch (value) {
            case "not_required", "pending", "approved", "rejected" -> value;
            default -> "not_required";
        };
    }

    private String normalizeAccountingStatus(String value) {
        return switch (value) {
            case "not_started", "draft", "posted", "reversed" -> value;
            default -> "not_started";
        };
    }

    private String normalizeDirection(String value) {
        return "income".equals(value) ? "income" : "expense";
    }

    private String normalizeStatus(String value) {
        return switch (value) {
            case "pending_review", "verified", "linked", "archived", "rejected" -> value;
            default -> "pending_review";
        };
    }

    private void requireReceiptWritePermission(String authorization, String voucherType) {
        if (!"reimbursement".equals(voucherType)) {
            accessControl.requireFinanceManager(authorization);
        }
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
        enterpriseStore.auditLog(voucher.companyId, "receipt_voucher", voucher.id, action, summary, user.id, user.nickname);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
