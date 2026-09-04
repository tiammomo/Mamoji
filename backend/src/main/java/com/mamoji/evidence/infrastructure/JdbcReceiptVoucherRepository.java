package com.mamoji.evidence.infrastructure;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.evidence.application.ReceiptListQuery;
import com.mamoji.evidence.application.ReceiptVoucherRepository;
import com.mamoji.evidence.domain.ReceiptSummary;
import com.mamoji.evidence.domain.ReceiptVoucherDraft;
import com.mamoji.evidence.domain.ReceiptVoucherPolicy;
import com.mamoji.evidence.domain.ReceiptVoucher;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Evidence-owned JDBC persistence for receipt vouchers. */
@Repository
public class JdbcReceiptVoucherRepository implements ReceiptVoucherRepository {
    private final JdbcTemplate jdbc;

    public JdbcReceiptVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResponse<ReceiptVoucher> findPage(
        long companyId,
        ReceiptListQuery filters
    ) {
        PageRequest pageRequest = filters.pageRequest();
        SqlReceiptQuery query = receiptQuery(companyId, filters);
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) " + query.fromAndWhere(),
            Long.class,
            query.arguments().toArray()
        );
        List<Object> pageArguments = new ArrayList<>(query.arguments());
        pageArguments.add(pageRequest.size());
        pageArguments.add((long) pageRequest.page() * pageRequest.size());
        List<ReceiptVoucher> content = jdbc.query(
            "SELECT * " + query.fromAndWhere() + " ORDER BY issue_date DESC, id LIMIT ? OFFSET ?",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            pageArguments.toArray()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PagedResponse<>(content, totalElements, totalPages, pageRequest.size(), pageRequest.page());
    }

    @Override
    public ReceiptSummary summarize(long companyId) {
        return jdbc.queryForObject("""
            SELECT
                COUNT(*) AS total_count,
                COALESCE(SUM(amount), 0) AS total_amount,
                COALESCE(SUM(amount) FILTER (WHERE voucher_type = 'sales_invoice'), 0)
                    AS sales_invoice_amount,
                COALESCE(SUM(amount) FILTER (WHERE voucher_type = 'purchase_invoice'), 0)
                    AS purchase_invoice_amount,
                COALESCE(SUM(tax_amount) FILTER (WHERE voucher_type = 'sales_invoice'), 0)
                    AS output_tax_amount,
                COALESCE(SUM(tax_amount) FILTER (WHERE voucher_type = 'purchase_invoice'), 0)
                    AS deductible_tax_amount,
                COALESCE(SUM(amount) FILTER (WHERE voucher_type = 'reimbursement'), 0)
                    AS reimbursement_amount,
                COALESCE(SUM(amount) FILTER (
                    WHERE voucher_type = 'reimbursement'
                      AND reimbursement_status NOT IN ('paid', 'archived')
                ), 0) AS reimbursement_pending_amount,
                COALESCE(SUM(amount) FILTER (WHERE status = 'pending_review'), 0) AS pending_amount,
                COUNT(*) FILTER (WHERE status = 'pending_review') AS pending_review_count,
                COUNT(*) FILTER (WHERE file_name IS NULL OR BTRIM(file_name) = '') AS missing_attachment_count,
                COUNT(*) FILTER (WHERE transaction_id IS NULL) AS missing_transaction_count,
                COUNT(*) FILTER (WHERE risk_level IN ('high', 'critical')) AS high_risk_count,
                COUNT(*) FILTER (WHERE invoice_check_status NOT IN ('not_required', 'verified'))
                    AS unchecked_invoice_count,
                COUNT(*) FILTER (WHERE deduction_status IN ('pending', 'deductible')) AS pending_deduction_count,
                COUNT(*) FILTER (
                    WHERE voucher_type = 'reimbursement'
                      AND reimbursement_status NOT IN ('paid', 'archived')
                ) AS pending_reimbursement_count,
                COUNT(*) FILTER (
                    WHERE (tax_period IS NULL OR BTRIM(tax_period) = '')
                      AND voucher_type IN ('sales_invoice', 'purchase_invoice', 'tax_receipt')
                ) AS missing_tax_period_count,
                COUNT(*) FILTER (WHERE approval_status = 'pending') AS pending_approval_count,
                COUNT(*) FILTER (WHERE accounting_status IN ('not_started', 'draft'))
                    AS pending_accounting_count,
                COUNT(*) FILTER (WHERE accounting_status = 'posted') AS posted_accounting_count
            FROM receipt_vouchers
            WHERE company_id = ?
            """, (rs, rowNum) -> new ReceiptSummary(
            rs.getLong("total_count"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("sales_invoice_amount"),
            rs.getBigDecimal("purchase_invoice_amount"),
            rs.getBigDecimal("output_tax_amount"),
            rs.getBigDecimal("deductible_tax_amount"),
            rs.getBigDecimal("reimbursement_amount"),
            rs.getBigDecimal("reimbursement_pending_amount"),
            rs.getBigDecimal("pending_amount"),
            rs.getLong("pending_review_count"),
            rs.getLong("missing_attachment_count"),
            rs.getLong("missing_transaction_count"),
            rs.getLong("high_risk_count"),
            rs.getLong("unchecked_invoice_count"),
            rs.getLong("pending_deduction_count"),
            rs.getLong("pending_reimbursement_count"),
            rs.getLong("missing_tax_period_count"),
            rs.getLong("pending_approval_count"),
            rs.getLong("pending_accounting_count"),
            rs.getLong("posted_accounting_count")
        ), companyId);
    }

    @Override
    public List<ReceiptVoucher> findByCompany(long companyId) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE company_id = ? ORDER BY issue_date DESC, id",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            companyId
        );
    }

    @Override
    public List<ReceiptVoucher> findAll() {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers ORDER BY company_id, issue_date DESC, id",
            (rs, rowNum) -> mapReceiptVoucher(rs)
        );
    }

    @Override
    public Optional<ReceiptVoucher> findById(long id) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE id = ?",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            id
        ).stream().findFirst();
    }

    @Override
    public Optional<ReceiptVoucher> findByIdForUpdate(long id) {
        return jdbc.query(
            "SELECT * FROM receipt_vouchers WHERE id = ? FOR UPDATE",
            (rs, rowNum) -> mapReceiptVoucher(rs),
            id
        ).stream().findFirst();
    }

    @Override
    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM receipt_vouchers", Long.class);
        return count == null ? 0 : count;
    }

    @Override
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

    @Override
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
            voucher.direction, voucher.counterparty, voucher.amount, voucher.taxAmount,
            voucher.taxRate, voucher.taxPeriod, voucher.invoiceCheckStatus, voucher.deductionStatus,
            voucher.reimbursementStatus, voucher.approvalStatus, voucher.accountingStatus, voucher.accountingVoucherNo,
            voucher.accountingEntry, voucher.approvedByUserId, timestamp(voucher.approvedAt),
            timestamp(voucher.accountedAt), voucher.businessPurpose, voucher.expenseOwner, date(voucher.issueDate),
            date(voucher.dueDate), voucher.status,
            voucher.fileName, voucher.fileSize, voucher.fileType, voucher.fileStorageProvider, voucher.fileBucket,
            voucher.fileObjectKey, voucher.fileUrl, voucher.riskLevel, voucher.note, voucher.operatorUserId,
            timestamp(voucher.updatedAt), voucher.id, voucher.version);
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
        voucher.amount = rs.getBigDecimal("amount");
        voucher.taxAmount = rs.getBigDecimal("tax_amount");
        voucher.taxRate = rs.getBigDecimal("tax_rate");
        voucher.taxPeriod = rs.getString("tax_period");
        voucher.invoiceCheckStatus = rs.getString("invoice_check_status");
        voucher.deductionStatus = rs.getString("deduction_status");
        voucher.reimbursementStatus = rs.getString("reimbursement_status");
        voucher.approvalStatus = rs.getString("approval_status");
        voucher.accountingStatus = rs.getString("accounting_status");
        voucher.accountingVoucherNo = rs.getString("accounting_voucher_no");
        voucher.accountingEntry = rs.getString("accounting_entry");
        voucher.approvedByUserId = nullableLong(rs, "approved_by_user_id");
        voucher.approvedAt = timestampText(rs, "approved_at");
        voucher.accountedAt = timestampText(rs, "accounted_at");
        voucher.businessPurpose = rs.getString("business_purpose");
        voucher.expenseOwner = rs.getString("expense_owner");
        voucher.issueDate = dateText(rs, "issue_date");
        voucher.dueDate = dateText(rs, "due_date");
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
        voucher.createdAt = timestampText(rs, "created_at");
        voucher.updatedAt = timestampText(rs, "updated_at");
        return voucher;
    }

    private SqlReceiptQuery receiptQuery(long companyId, ReceiptListQuery filters) {
        StringBuilder fromAndWhere = new StringBuilder("FROM receipt_vouchers WHERE company_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(companyId);
        addCondition(fromAndWhere, arguments, "voucher_type = ?", filters.voucherType());
        addCondition(fromAndWhere, arguments, "direction = ?", filters.direction());
        addCondition(fromAndWhere, arguments, "status = ?", filters.status());
        addCondition(fromAndWhere, arguments, "invoice_check_status = ?", filters.invoiceCheckStatus());
        addCondition(fromAndWhere, arguments, "deduction_status = ?", filters.deductionStatus());
        addCondition(fromAndWhere, arguments, "reimbursement_status = ?", filters.reimbursementStatus());
        addCondition(fromAndWhere, arguments, "tax_period = ?", filters.taxPeriod());
        addCondition(
            fromAndWhere,
            arguments,
            "issue_date >= ?",
            filters.startDate()
        );
        addCondition(
            fromAndWhere,
            arguments,
            "issue_date <= ?",
            filters.endDate()
        );
        addCondition(fromAndWhere, arguments, "amount >= ?", filters.minAmount());
        addCondition(fromAndWhere, arguments, "amount <= ?", filters.maxAmount());
        if ("missing".equals(filters.linkState())) {
            fromAndWhere.append(" AND transaction_id IS NULL");
        } else if ("linked".equals(filters.linkState())) {
            fromAndWhere.append(" AND transaction_id IS NOT NULL");
        }
        if (!filters.keyword().isBlank()) {
            fromAndWhere.append(" AND (LOWER(COALESCE(title, '')) LIKE ? ESCAPE '!'"
                + " OR LOWER(COALESCE(voucher_no, '')) LIKE ? ESCAPE '!'"
                + " OR LOWER(COALESCE(counterparty, '')) LIKE ? ESCAPE '!'"
                + " OR LOWER(COALESCE(note, '')) LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(filters.keyword().toLowerCase(Locale.ROOT)) + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        return new SqlReceiptQuery(fromAndWhere.toString(), List.copyOf(arguments));
    }

    private static void addCondition(
        StringBuilder sql,
        List<Object> arguments,
        String condition,
        Object value
    ) {
        if (value == null) return;
        sql.append(" AND ").append(condition);
        arguments.add(value);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static void bindReceiptVoucher(PreparedStatement ps, ReceiptVoucher voucher) throws SQLException {
        ps.setLong(1, voucher.companyId);
        setLongOrNull(ps, 2, voucher.transactionId);
        ps.setString(3, voucher.voucherNo);
        ps.setString(4, voucher.title);
        ps.setString(5, voucher.voucherType);
        ps.setString(6, voucher.direction);
        ps.setString(7, voucher.counterparty);
        ps.setBigDecimal(8, voucher.amount);
        ps.setBigDecimal(9, voucher.taxAmount);
        ps.setBigDecimal(10, voucher.taxRate);
        ps.setString(11, voucher.taxPeriod);
        ps.setString(12, voucher.invoiceCheckStatus);
        ps.setString(13, voucher.deductionStatus);
        ps.setString(14, voucher.reimbursementStatus);
        ps.setString(15, voucher.approvalStatus);
        ps.setString(16, voucher.accountingStatus);
        ps.setString(17, voucher.accountingVoucherNo);
        ps.setString(18, voucher.accountingEntry);
        setLongOrNull(ps, 19, voucher.approvedByUserId);
        setTimestampOrNull(ps, 20, voucher.approvedAt);
        setTimestampOrNull(ps, 21, voucher.accountedAt);
        ps.setString(22, voucher.businessPurpose);
        ps.setString(23, voucher.expenseOwner);
        setDateOrNull(ps, 24, voucher.issueDate);
        setDateOrNull(ps, 25, voucher.dueDate);
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
        setTimestampOrNull(ps, 37, voucher.createdAt);
        setTimestampOrNull(ps, 38, voucher.updatedAt);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static void setLongOrNull(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setDateOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        LocalDate date = date(value);
        if (date == null) {
            ps.setNull(index, Types.DATE);
        } else {
            ps.setObject(index, date);
        }
    }

    private static void setTimestampOrNull(PreparedStatement ps, int index, String value) throws SQLException {
        OffsetDateTime timestamp = timestamp(value);
        if (timestamp == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(index, timestamp);
        }
    }

    private static LocalDate date(String value) {
        return isBlank(value) ? null : LocalDate.parse(value);
    }

    private static OffsetDateTime timestamp(String value) {
        return isBlank(value) ? null : OffsetDateTime.parse(value);
    }

    private static String dateText(ResultSet rs, String column) throws SQLException {
        LocalDate value = rs.getObject(column, LocalDate.class);
        return value == null ? null : value.toString();
    }

    private static String timestampText(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SqlReceiptQuery(String fromAndWhere, List<Object> arguments) {
    }
}
