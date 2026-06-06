package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
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

    public ReceiptService(
        AccessControlService accessControl,
        EnterpriseStore enterpriseStore,
        InMemoryStore coreStore
    ) {
        this.accessControl = accessControl;
        this.enterpriseStore = enterpriseStore;
        this.coreStore = coreStore;
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
        BigDecimal deductibleTaxAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        long missingAttachmentCount = 0;
        long missingTransactionCount = 0;
        long pendingReviewCount = 0;
        long highRiskCount = 0;

        for (ReceiptVoucher voucher : vouchers) {
            totalAmount = totalAmount.add(voucher.amount);
            if ("purchase_invoice".equals(voucher.voucherType)) {
                deductibleTaxAmount = deductibleTaxAmount.add(voucher.taxAmount);
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
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", vouchers.size());
        result.put("totalAmount", totalAmount);
        result.put("deductibleTaxAmount", deductibleTaxAmount);
        result.put("pendingAmount", pendingAmount);
        result.put("pendingReviewCount", pendingReviewCount);
        result.put("missingAttachmentCount", missingAttachmentCount);
        result.put("missingTransactionCount", missingTransactionCount);
        result.put("highRiskCount", highRiskCount);
        return result;
    }

    public ReceiptVoucher create(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        ReceiptVoucher voucher = enterpriseStore.receiptVoucher(
            company.id,
            validateTransaction(user, optionalLong(body.get("transactionId")).orElse(null)).map(tx -> tx.id).orElse(null),
            textOr(body.get("voucherNo"), nextVoucherNo()),
            textOr(body.get("title"), "新票据凭证"),
            normalizeVoucherType(textOr(body.get("voucherType"), "purchase_invoice")),
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
        voucher.riskLevel = riskFor(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        return voucher;
    }

    public ReceiptVoucher update(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        ReceiptVoucher voucher = require(enterpriseStore.receiptVouchers.get(id), "Receipt voucher not found");
        if (!accessControl.canAccessCompany(user, voucher.companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        applyVoucherFields(user, voucher, body);
        voucher.riskLevel = riskFor(voucher);
        touch(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        return voucher;
    }

    public Map<String, Object> upload(String authorization, MultipartFile file, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image is required");
        }
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        String filename = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
        ReceiptVoucher voucher = enterpriseStore.receiptVoucher(
            company.id,
            validateTransaction(user, longParam(params, "transactionId", 0)).map(tx -> tx.id).orElse(null),
            params.getOrDefault("voucherNo", nextVoucherNo()),
            params.getOrDefault("title", filename),
            normalizeVoucherType(params.getOrDefault("voucherType", "purchase_invoice")),
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
        voucher.riskLevel = riskFor(voucher);
        enterpriseStore.saveReceiptVoucher(voucher);
        return Map.of(
            "success", true,
            "voucher", voucher,
            "message", "Receipt uploaded"
        );
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

    private String normalizeDirection(String value) {
        return "income".equals(value) ? "income" : "expense";
    }

    private String normalizeStatus(String value) {
        return switch (value) {
            case "pending_review", "verified", "linked", "archived", "rejected" -> value;
            default -> "pending_review";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
