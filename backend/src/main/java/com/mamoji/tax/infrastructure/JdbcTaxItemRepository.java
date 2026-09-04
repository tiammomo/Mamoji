package com.mamoji.tax.infrastructure;

import com.mamoji.tax.application.TaxItemRepository;
import com.mamoji.tax.domain.TaxItem;
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

@Repository
public class JdbcTaxItemRepository implements TaxItemRepository {
    private static final String COLUMNS = """
        id, company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount,
        deductible_amount, tax_rate, due_date, status, filing_status, payment_status,
        frequency, declaration_date, payment_date, responsible_person, risk_level,
        policy_basis, source_type, note, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcTaxItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TaxItem> findByCompany(long companyId) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM tax_items WHERE company_id = ? ORDER BY due_date, id",
            this::map,
            companyId
        );
    }

    @Override
    public List<TaxItem> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM tax_items ORDER BY company_id, due_date, id", this::map);
    }

    @Override
    public Optional<TaxItem> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tax_items WHERE id = ? FOR UPDATE", this::map, id)
            .stream().findFirst();
    }

    @Override
    public TaxItem insert(TaxItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tax_items (
                    company_id, name, period, tax_type, taxable_amount, tax_amount, paid_amount,
                    deductible_amount, tax_rate, due_date, status, filing_status, payment_status,
                    frequency, declaration_date, payment_date, responsible_person, risk_level,
                    policy_basis, source_type, note, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[] { "id" });
            bind(statement, item);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Database did not return a generated tax item id");
        item.id = key.longValue();
        return item;
    }

    @Override
    public void update(TaxItem item) {
        int updated = jdbc.update("""
            UPDATE tax_items SET name = ?, period = ?, tax_type = ?, taxable_amount = ?, tax_amount = ?,
                paid_amount = ?, deductible_amount = ?, tax_rate = ?, due_date = ?, status = ?,
                filing_status = ?, payment_status = ?, frequency = ?, declaration_date = ?, payment_date = ?,
                responsible_person = ?, risk_level = ?, policy_basis = ?, source_type = ?, note = ?, updated_at = ?
            WHERE id = ? AND company_id = ?
            """,
            item.name, item.period, item.taxType, item.taxableAmount, item.taxAmount,
            item.paidAmount, item.deductibleAmount, item.taxRate, date(item.dueDate), item.status,
            item.filingStatus, item.paymentStatus, item.frequency, nullableDate(item.declarationDate),
            nullableDate(item.paymentDate), item.responsiblePerson, item.riskLevel, item.policyBasis,
            item.sourceType, item.note, timestamp(item.updatedAt), item.id, item.companyId
        );
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Tax item was changed by another request: " + item.id);
        }
    }

    @Override
    public void delete(long id) {
        if (jdbc.update("DELETE FROM tax_items WHERE id = ?", id) != 1) {
            throw new OptimisticLockingFailureException("Tax item was changed by another request: " + id);
        }
    }

    @Override
    public boolean hasLifecycleHistory(long companyId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE company_id = ? AND entity_type = 'tax_item'
            """, Integer.class, companyId);
        return count != null && count > 0;
    }

    private TaxItem map(ResultSet result, int rowNumber) throws SQLException {
        TaxItem item = new TaxItem();
        item.id = result.getLong("id");
        item.companyId = result.getLong("company_id");
        item.name = result.getString("name");
        item.period = result.getString("period");
        item.taxType = result.getString("tax_type");
        item.taxableAmount = result.getBigDecimal("taxable_amount");
        item.taxAmount = result.getBigDecimal("tax_amount");
        item.paidAmount = result.getBigDecimal("paid_amount");
        item.deductibleAmount = result.getBigDecimal("deductible_amount");
        item.taxRate = result.getBigDecimal("tax_rate");
        item.dueDate = result.getObject("due_date", LocalDate.class).toString();
        item.status = result.getString("status");
        item.filingStatus = result.getString("filing_status");
        item.paymentStatus = result.getString("payment_status");
        item.frequency = result.getString("frequency");
        item.declarationDate = nullableDate(result, "declaration_date");
        item.paymentDate = nullableDate(result, "payment_date");
        item.responsiblePerson = result.getString("responsible_person");
        item.riskLevel = result.getString("risk_level");
        item.policyBasis = result.getString("policy_basis");
        item.sourceType = result.getString("source_type");
        item.note = result.getString("note");
        item.createdAt = result.getObject("created_at", OffsetDateTime.class).toString();
        item.updatedAt = result.getObject("updated_at", OffsetDateTime.class).toString();
        return item;
    }

    private void bind(PreparedStatement statement, TaxItem item) throws SQLException {
        statement.setLong(1, item.companyId);
        statement.setString(2, item.name);
        statement.setString(3, item.period);
        statement.setString(4, item.taxType);
        statement.setBigDecimal(5, item.taxableAmount);
        statement.setBigDecimal(6, item.taxAmount);
        statement.setBigDecimal(7, item.paidAmount);
        statement.setBigDecimal(8, item.deductibleAmount);
        statement.setBigDecimal(9, item.taxRate);
        statement.setObject(10, date(item.dueDate));
        statement.setString(11, item.status);
        statement.setString(12, item.filingStatus);
        statement.setString(13, item.paymentStatus);
        statement.setString(14, item.frequency);
        statement.setObject(15, nullableDate(item.declarationDate));
        statement.setObject(16, nullableDate(item.paymentDate));
        statement.setString(17, item.responsiblePerson);
        statement.setString(18, item.riskLevel);
        statement.setString(19, item.policyBasis);
        statement.setString(20, item.sourceType);
        statement.setString(21, item.note);
        statement.setObject(22, timestamp(item.createdAt));
        statement.setObject(23, timestamp(item.updatedAt));
    }

    private LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    private LocalDate nullableDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private String nullableDate(ResultSet result, String column) throws SQLException {
        LocalDate value = result.getObject(column, LocalDate.class);
        return value == null ? null : value.toString();
    }

    private OffsetDateTime timestamp(String value) {
        return OffsetDateTime.parse(value);
    }
}
