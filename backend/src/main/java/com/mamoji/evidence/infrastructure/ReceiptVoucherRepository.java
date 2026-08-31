package com.mamoji.evidence.infrastructure;

import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.evidence.domain.ReceiptVoucherPolicy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Evidence-owned JDBC persistence for receipt vouchers. */
@Repository
public class ReceiptVoucherRepository {
    private final JdbcTemplate jdbc;

    public ReceiptVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReceiptVoucher> findByCompany(long companyId) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE company_id = ? ORDER BY issue_date DESC, id",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            companyId
        );
    }

    public List<ReceiptVoucher> findAll() {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers ORDER BY company_id, issue_date DESC, id",
            (rs, rowNum) -> mapReceiptVoucher(rs)
        );
    }

    public Optional<ReceiptVoucher> findById(long id) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE id = ?",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            id
        ).stream().findFirst();
    }

    public Optional<ReceiptVoucher> findByIdForUpdate(long id) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            id
        ).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM receipt_vouchers", Long.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public int repairLegacyDefaults() {
        List<ReceiptVoucher> vouchers = jdbc.query(
            "SELECT * FROM receipt_vouchers ORDER BY id",
            (rs, rowNum) -> mapStoredReceiptVoucher(rs)
        );
        int repaired = 0;
        LocalDate today = LocalDate.now();
        for (ReceiptVoucher voucher : vouchers) {
            if (ReceiptVoucherPolicy.hydrate(voucher, today)) {
                voucher.updatedAt = OffsetDateTime.now().toString();
                save(voucher);
                repaired++;
            }
        }
        return repaired;
    }

    public ReceiptVoucher insert(ReceiptVoucherDraft draft) {
        LocalDate today = LocalDate.now();
        ReceiptVoucher voucher = ReceiptVoucherPolicy.initialize(draft, today, OffsetDateTime.now().toString());
        voucher.id = insertVoucher(voucher);
        String accountingVoucherNoBeforeHydration = voucher.accountingVoucherNo;
        ReceiptVoucherPolicy.hydrate(voucher, today);
        if (isBlank(accountingVoucherNoBeforeHydration) && !isBlank(voucher.accountingVoucherNo)) {
            save(voucher);
        }
        return voucher;
    }

    public void save(ReceiptVoucher voucher) {
        int updated = jdbc.update("""
            UPDATE receipt_vouchers SET company_id = ?, transaction_id = ?, voucher_no = ?, title = ?, voucher_type = ?,
                direction = ?, counterparty = ?, amount = ?, tax_amount = ?, tax_rate = ?, tax_period = ?,
                invoice_check_status = ?, deduction_status = ?, reimbursement_status = ?, approval_status = ?,
                accounting_status = ?, accounting_voucher_no = ?, accounting_entry = ?, approved_by_user_id = ?,
                approved_at = ?, accounted_at = ?, business_purpose = ?, expense_owner = ?, issue_date = ?, due_date = ?,
                status = ?, file_name = ?, file_size = ?, file_type = ?, file_storage_provider = ?, file_bucket = ?,
                file_object_key = ?, file_url = ?, risk_level = ?, note = ?, operator_user_id = ?, updated_at = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """, voucher.companyId, voucher.transactionId, voucher.voucherNo, voucher.title, voucher.voucherType,
            voucher.direction, voucher.counterparty, moneyText(voucher.amount), moneyText(voucher.taxAmount),
            moneyText(voucher.taxRate), voucher.taxPeriod, voucher.invoiceCheckStatus, voucher.deductionStatus,
            voucher.reimbursementStatus, voucher.approvalStatus, voucher.accountingStatus, voucher.accountingVoucherNo,
            voucher.accountingEntry, voucher.approvedByUserId, voucher.approvedAt, voucher.accountedAt,
            voucher.businessPurpose, voucher.expenseOwner, voucher.issueDate, voucher.dueDate, voucher.status,
            voucher.fileName, voucher.fileSize, voucher.fileType, voucher.fileStorageProvider, voucher.fileBucket,
            voucher.fileObjectKey, voucher.fileUrl, voucher.riskLevel, voucher.note, voucher.operatorUserId,
            voucher.updatedAt, voucher.id, voucher.version);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Receipt voucher was changed by another request: " + voucher.id);
        }
        voucher.version++;
    }

    private long insertVoucher(ReceiptVoucher voucher) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO receipt_vouchers (
                    company_id, transaction_id, voucher_no, title, voucher_type, direction, counterparty,
                    amount, tax_amount, tax_rate, tax_period, invoice_check_status, deduction_status,
                    reimbursement_status, approval_status, accounting_status, accounting_voucher_no, accounting_entry,
                    approved_by_user_id, approved_at, accounted_at, business_purpose, expense_owner, issue_date, due_date,
                    status, file_name, file_size, file_type, file_storage_provider, file_bucket, file_object_key, file_url,
                    risk_level, note, operator_user_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            bindReceiptVoucher(ps, voucher);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a generated receipt voucher id");
        }
        return key.longValue();
    }

    private ReceiptVoucher mapReceiptVoucher(ResultSet rs) throws SQLException {
        ReceiptVoucher voucher = mapStoredReceiptVoucher(rs);
        ReceiptVoucherPolicy.hydrate(voucher, LocalDate.now());
        return voucher;
    }

    private ReceiptVoucher mapStoredReceiptVoucher(ResultSet rs) throws SQLException {
        ReceiptVoucher voucher = new ReceiptVoucher();
        voucher.id = rs.getLong("id");
        voucher.version = rs.getLong("version");
        voucher.companyId = rs.getLong("company_id");
        voucher.transactionId = nullableLong(rs, "transaction_id");
        voucher.voucherNo = rs.getString("voucher_no");
        voucher.title = rs.getString("title");
        voucher.voucherType = rs.getString("voucher_type");
        voucher.direction = rs.getString("direction");
        voucher.counterparty = rs.getString("counterparty");
        voucher.amount = money(rs.getString("amount"));
        voucher.taxAmount = money(rs.getString("tax_amount"));
        voucher.taxRate = money(rs.getString("tax_rate"));
        voucher.taxPeriod = rs.getString("tax_period");
        voucher.invoiceCheckStatus = rs.getString("invoice_check_status");
        voucher.deductionStatus = rs.getString("deduction_status");
        voucher.reimbursementStatus = rs.getString("reimbursement_status");
        voucher.approvalStatus = rs.getString("approval_status");
        voucher.accountingStatus = rs.getString("accounting_status");
        voucher.accountingVoucherNo = rs.getString("accounting_voucher_no");
        voucher.accountingEntry = rs.getString("accounting_entry");
        voucher.approvedByUserId = nullableLong(rs, "approved_by_user_id");
        voucher.approvedAt = rs.getString("approved_at");
        voucher.accountedAt = rs.getString("accounted_at");
        voucher.businessPurpose = rs.getString("business_purpose");
        voucher.expenseOwner = rs.getString("expense_owner");
        voucher.issueDate = rs.getString("issue_date");
        voucher.dueDate = rs.getString("due_date");
        voucher.status = rs.getString("status");
        voucher.fileName = rs.getString("file_name");
        voucher.fileSize = rs.getLong("file_size");
        voucher.fileType = rs.getString("file_type");
        voucher.fileStorageProvider = rs.getString("file_storage_provider");
        voucher.fileBucket = rs.getString("file_bucket");
        voucher.fileObjectKey = rs.getString("file_object_key");
        voucher.fileUrl = rs.getString("file_url");
        voucher.riskLevel = rs.getString("risk_level");
        voucher.note = rs.getString("note");
        voucher.operatorUserId = rs.getLong("operator_user_id");
        voucher.createdAt = rs.getString("created_at");
        voucher.updatedAt = rs.getString("updated_at");
        return voucher;
    }

    private static void bindReceiptVoucher(PreparedStatement ps, ReceiptVoucher voucher) throws SQLException {
        ps.setLong(1, voucher.companyId);
        setLongOrNull(ps, 2, voucher.transactionId);
        ps.setString(3, voucher.voucherNo);
        ps.setString(4, voucher.title);
        ps.setString(5, voucher.voucherType);
        ps.setString(6, voucher.direction);
        ps.setString(7, voucher.counterparty);
        ps.setString(8, moneyText(voucher.amount));
        ps.setString(9, moneyText(voucher.taxAmount));
        ps.setString(10, moneyText(voucher.taxRate));
        ps.setString(11, voucher.taxPeriod);
        ps.setString(12, voucher.invoiceCheckStatus);
        ps.setString(13, voucher.deductionStatus);
        ps.setString(14, voucher.reimbursementStatus);
        ps.setString(15, voucher.approvalStatus);
        ps.setString(16, voucher.accountingStatus);
        ps.setString(17, voucher.accountingVoucherNo);
        ps.setString(18, voucher.accountingEntry);
        setLongOrNull(ps, 19, voucher.approvedByUserId);
        ps.setString(20, voucher.approvedAt);
        ps.setString(21, voucher.accountedAt);
        ps.setString(22, voucher.businessPurpose);
        ps.setString(23, voucher.expenseOwner);
        ps.setString(24, voucher.issueDate);
        ps.setString(25, voucher.dueDate);
        ps.setString(26, voucher.status);
        ps.setString(27, voucher.fileName);
        ps.setLong(28, voucher.fileSize);
        ps.setString(29, voucher.fileType);
        ps.setString(30, voucher.fileStorageProvider);
        ps.setString(31, voucher.fileBucket);
        ps.setString(32, voucher.fileObjectKey);
        ps.setString(33, voucher.fileUrl);
        ps.setString(34, voucher.riskLevel);
        ps.setString(35, voucher.note);
        ps.setLong(36, voucher.operatorUserId);
        ps.setString(37, voucher.createdAt);
        ps.setString(38, voucher.updatedAt);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setLong(index, value);
        }
    }

    private static BigDecimal money(String value) {
        return isBlank(value) ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String moneyText(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
