package com.mamoji.approval.infrastructure;

import com.mamoji.approval.application.ApprovalEntityGateway;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.infrastructure.ReceiptVoucherRepository;
import com.mamoji.platform.identity.User;
import com.mamoji.service.ReceiptService;
import com.mamoji.service.support.AccessControlService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL-backed adapter for entities referenced by the approval workflow. */
@Component
public class JdbcApprovalEntityGateway implements ApprovalEntityGateway {
    private final JdbcTemplate jdbc;
    private final ReceiptVoucherRepository receiptVouchers;
    private final ReceiptService receiptService;
    private final AccessControlService accessControl;

    public JdbcApprovalEntityGateway(
        JdbcTemplate jdbc,
        ReceiptVoucherRepository receiptVouchers,
        ReceiptService receiptService,
        AccessControlService accessControl
    ) {
        this.jdbc = jdbc;
        this.receiptVouchers = receiptVouchers;
        this.receiptService = receiptService;
        this.accessControl = accessControl;
    }

    @Override
    public void validateReference(User applicant, long companyId, String entityType, Long entityId) {
        if (entityId == null || "other".equals(entityType)) return;
        if ("receipt_voucher".equals(entityType)) {
            validateReceipt(applicant, companyId, entityId);
            return;
        }

        String table = switch (entityType) {
            case "transaction" -> "transactions";
            case "budget" -> "budgets";
            case "employee" -> "employees";
            case "payroll_run" -> "payroll_runs";
            default -> throw new IllegalArgumentException("Unsupported approval entity type: " + entityType);
        };
        Integer matches = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE id = ? AND company_id = ?",
            Integer.class,
            entityId,
            companyId
        );
        if (matches == null || matches == 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                entityLabel(entityType) + " is outside the selected company"
            );
        }
    }

    @Override
    public void synchronizeStatus(String authorization, String entityType, Long entityId, String status) {
        if ("receipt_voucher".equals(entityType) && entityId != null) {
            receiptService.updateApprovalStatus(authorization, entityId, status);
        }
    }

    private void validateReceipt(User applicant, long companyId, long entityId) {
        ReceiptVoucher voucher = receiptVouchers.findById(entityId).orElse(null);
        if (voucher == null || voucher.companyId != companyId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt voucher is outside the selected company");
        }
        if (!accessControl.hasFinanceManagerRole(applicant, companyId) && voucher.operatorUserId != applicant.id) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the submitter or a finance manager can submit this receipt"
            );
        }
    }

    private String entityLabel(String entityType) {
        return switch (entityType) {
            case "transaction" -> "Transaction";
            case "budget" -> "Budget";
            case "employee" -> "Employee";
            case "payroll_run" -> "Payroll run";
            default -> "Entity";
        };
    }
}
