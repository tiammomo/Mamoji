package com.mamoji.operations.infrastructure;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.domain.Models.TransactionRecord;
import com.mamoji.operations.application.TransactionQueryRepository;
import com.mamoji.operations.domain.TransactionSearchCriteria;
import com.mamoji.operations.domain.TransactionSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for database-paged transaction reads and aggregate summaries. */
@Repository
public class JdbcTransactionQueryRepository implements TransactionQueryRepository {
    private final JdbcTemplate jdbc;

    public JdbcTransactionQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResponse<TransactionRecord> findPage(
        long userId,
        long companyId,
        TransactionSearchCriteria criteria,
        PageRequest pageRequest
    ) {
        SqlTransactionQuery query = transactionQuery(userId, companyId, criteria);
        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) " + query.from(),
            Long.class,
            query.arguments().toArray()
        );
        List<Object> pageArguments = new ArrayList<>(query.arguments());
        pageArguments.add(pageRequest.size());
        pageArguments.add((long) pageRequest.page() * pageRequest.size());
        List<TransactionRecord> content = jdbc.query(
            "SELECT t.*, c.name AS resolved_category_name, c.icon AS resolved_category_icon, "
                + "c.color AS resolved_category_color, a.name AS resolved_account_name "
                + query.from() + " ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?",
            this::mapTransaction,
            pageArguments.toArray()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PagedResponse<>(content, totalElements, totalPages, pageRequest.size(), pageRequest.page());
    }

    @Override
    public TransactionSummary summarize(long userId, long companyId, TransactionSearchCriteria criteria) {
        SqlTransactionQuery query = transactionQuery(userId, companyId, criteria);
        String sql = """
            WITH filtered AS (
                SELECT t.type, CAST(t.amount AS NUMERIC) AS amount, t.note, t.is_refundable,
                       LOWER(
                           COALESCE(t.note, '') || ' ' ||
                           COALESCE(c.name, '') || ' ' ||
                           COALESCE(a.name, '')
                       ) AS searchable
            """ + query.from() + """
            ), classified AS (
                SELECT *,
                       type = 1 AND searchable LIKE ANY (ARRAY[
                           '%待回款%', '%应收%', '%未回款%', '%尾款%', '%分期%',
                           '%验收后%', '%交付后%', '%回款中%'
                       ]) AS pending,
                       type = 2 AND searchable LIKE ANY (ARRAY[
                           '%客户退款%', '%退款给客户%', '%收入退款%', '%订单退款%',
                           '%项目退款%', '%退货退款%', '%服务退款%'
                       ]) AS customer_refund,
                       type = 2 AND searchable LIKE ANY (ARRAY[
                           '%裁员%', '%离职补偿%', '%经济补偿%', '%遣散%', '%n+1%',
                           '%n+ 1%', '%补偿金%', '%解除劳动%'
                       ]) AS severance
                FROM filtered
            )
            SELECT
                COALESCE(SUM(amount) FILTER (WHERE type = 1), 0) AS income,
                COALESCE(SUM(amount) FILTER (WHERE type = 2), 0) AS expense,
                COALESCE(SUM(amount) FILTER (WHERE type = 3), 0) AS refund,
                COALESCE(SUM(amount) FILTER (WHERE pending), 0) AS pending_collection,
                COALESCE(SUM(amount) FILTER (WHERE customer_refund), 0) AS customer_refund,
                COALESCE(SUM(amount) FILTER (WHERE severance), 0) AS severance,
                COUNT(*) AS row_count,
                COUNT(*) FILTER (WHERE amount >= 10000) AS large_count,
                COUNT(*) FILTER (
                    WHERE amount >= 10000
                       OR (type = 2 AND is_refundable = 1)
                       OR pending
                       OR customer_refund
                       OR severance
                       OR COALESCE(BTRIM(note), '') = ''
                ) AS review_count
            FROM classified
            """;
        return jdbc.queryForObject(sql, (rs, rowNum) -> TransactionSummary.fromTotals(
            rs.getBigDecimal("income"),
            rs.getBigDecimal("expense"),
            rs.getBigDecimal("refund"),
            rs.getBigDecimal("pending_collection"),
            rs.getBigDecimal("customer_refund"),
            rs.getBigDecimal("severance"),
            rs.getLong("row_count"),
            rs.getLong("large_count"),
            rs.getLong("review_count")
        ), query.arguments().toArray());
    }

    @Override
    public Optional<TransactionRecord> findById(long id) {
        return jdbc.query("""
            SELECT t.*, c.name AS resolved_category_name, c.icon AS resolved_category_icon,
                   c.color AS resolved_category_color, a.name AS resolved_account_name
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN accounts a ON a.id = t.account_id
            WHERE t.id = ?
            """, this::mapTransaction, id).stream().findFirst();
    }

    private SqlTransactionQuery transactionQuery(
        long userId,
        long companyId,
        TransactionSearchCriteria criteria
    ) {
        StringBuilder from = new StringBuilder("""
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN accounts a ON a.id = t.account_id
            WHERE t.user_id = ? AND t.company_id = ?
            """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.add(companyId);
        addCondition(from, arguments, "t.type = ?", criteria.type());
        addCondition(from, arguments, "t.category_id = ?", criteria.categoryId());
        addCondition(from, arguments, "t.account_id = ?", criteria.accountId());
        addCondition(from, arguments, "t.date >= ?", criteria.startDate());
        addCondition(from, arguments, "t.date <= ?", criteria.endDate());
        addCondition(from, arguments, "CAST(t.amount AS NUMERIC) >= ?", criteria.minAmount());
        addCondition(from, arguments, "CAST(t.amount AS NUMERIC) <= ?", criteria.maxAmount());
        String keyword = criteria.keyword().toLowerCase(Locale.ROOT);
        if (!keyword.isBlank()) {
            from.append(" AND (LOWER(t.note) LIKE ? OR LOWER(COALESCE(c.name, '')) LIKE ? "
                + "OR LOWER(COALESCE(a.name, '')) LIKE ?)");
            String pattern = "%" + keyword + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        return new SqlTransactionQuery(from.toString(), List.copyOf(arguments));
    }

    private void addCondition(StringBuilder sql, List<Object> arguments, String condition, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(condition);
        arguments.add(value instanceof LocalDate date ? date.toString() : value);
    }

    private TransactionRecord mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        TransactionRecord transaction = new TransactionRecord();
        transaction.id = rs.getLong("id");
        transaction.version = rs.getLong("version");
        transaction.idempotencyKey = rs.getString("idempotency_key");
        transaction.companyId = nullableLong(rs, "company_id");
        transaction.userId = rs.getLong("user_id");
        transaction.familyId = nullableLong(rs, "family_id");
        transaction.type = rs.getInt("type");
        transaction.amount = money(rs.getString("amount"));
        transaction.categoryId = rs.getLong("category_id");
        transaction.accountId = rs.getLong("account_id");
        transaction.date = rs.getString("date");
        transaction.note = rs.getString("note");
        transaction.originalTransactionId = nullableLong(rs, "original_transaction_id");
        transaction.refundedAmount = money(rs.getString("refunded_amount"));
        transaction.isRefundable = rs.getInt("is_refundable") == 1;
        transaction.budgetId = nullableLong(rs, "budget_id");
        transaction.createdAt = rs.getString("created_at");
        transaction.updatedAt = rs.getString("updated_at");
        transaction.categoryName = rs.getString("resolved_category_name");
        transaction.categoryIcon = rs.getString("resolved_category_icon");
        transaction.categoryColor = rs.getString("resolved_category_color");
        transaction.accountName = rs.getString("resolved_account_name");
        return transaction;
    }

    private BigDecimal money(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record SqlTransactionQuery(String from, List<Object> arguments) {
    }
}
