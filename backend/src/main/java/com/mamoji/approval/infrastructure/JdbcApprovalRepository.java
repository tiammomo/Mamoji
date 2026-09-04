package com.mamoji.approval.infrastructure;

import com.mamoji.approval.application.ApprovalRepository;
import com.mamoji.approval.application.ApprovalRepository.NewAction;
import com.mamoji.approval.application.ApprovalRepository.NewApproval;
import com.mamoji.approval.domain.ApprovalAction;
import com.mamoji.approval.domain.ApprovalRequest;
import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for approval requests, actions, and creation locks. */
@Repository
public class JdbcApprovalRepository implements ApprovalRepository {
    private final JdbcTemplate jdbc;

    public JdbcApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResponse<ApprovalRequest> findPage(
        long companyId,
        Long participantUserId,
        String status,
        String requestType,
        String keyword,
        PageRequest page
    ) {
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(companyId);
        if (participantUserId != null) {
            where.append(" AND (applicant_user_id = ? OR assignee_user_id = ?)");
            arguments.add(participantUserId);
            arguments.add(participantUserId);
        }
        addFilter(where, arguments, "status", status);
        addFilter(where, arguments, "request_type", requestType);
        String normalizedKeyword = Objects.toString(keyword, "").trim().toLowerCase(Locale.ROOT);
        if (!normalizedKeyword.isBlank()) {
            where.append(" AND (LOWER(title) LIKE ? OR LOWER(COALESCE(description, '')) LIKE ?)");
            arguments.add("%" + normalizedKeyword + "%");
            arguments.add("%" + normalizedKeyword + "%");
        }

        Long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests" + where,
            Long.class,
            arguments.toArray()
        );
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(page.size());
        pageArguments.add((long) page.page() * page.size());
        List<ApprovalRequest> content = jdbc.query(
            "SELECT * FROM approval_requests" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            this::mapRequest,
            pageArguments.toArray()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / page.size());
        return new PagedResponse<>(content, totalElements, totalPages, page.size(), page.page());
    }

    @Override
    public Map<String, Object> summarize(long companyId, long userId, boolean administrator) {
        String accessClause = administrator ? "" : " AND (applicant_user_id = ? OR assignee_user_id = ?)";
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.add(companyId);
        if (!administrator) {
            arguments.add(userId);
            arguments.add(userId);
        }
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status = 'pending') AS pending,
                   COUNT(*) FILTER (WHERE status = 'approved') AS approved,
                   COUNT(*) FILTER (WHERE status = 'rejected') AS rejected,
                   COUNT(*) FILTER (WHERE status = 'pending' AND assignee_user_id = ?) AS mine_pending
            FROM approval_requests
            WHERE company_id = ?
            """ + accessClause, (result, rowNumber) -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", result.getLong("total"));
            summary.put("pending", result.getLong("pending"));
            summary.put("approved", result.getLong("approved"));
            summary.put("rejected", result.getLong("rejected"));
            summary.put("minePending", result.getLong("mine_pending"));
            return summary;
        }, arguments.toArray());
    }

    @Override
    public Optional<ApprovalRequest> findById(long id) {
        return jdbc.query("SELECT * FROM approval_requests WHERE id = ?", this::mapRequest, id)
            .stream()
            .findFirst();
    }

    @Override
    public Optional<ApprovalRequest> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM approval_requests WHERE id = ? FOR UPDATE", this::mapRequest, id)
            .stream()
            .findFirst();
    }

    @Override
    public List<ApprovalAction> findActions(long requestId) {
        return jdbc.query(
            "SELECT * FROM approval_actions WHERE request_id = ? ORDER BY id",
            this::mapAction,
            requestId
        );
    }

    @Override
    public void lockIdempotencyKey(long companyId, String idempotencyKey) {
        advisoryLock("approval:" + companyId + ":" + idempotencyKey);
    }

    @Override
    public Optional<ApprovalRequest> findByIdempotencyKey(long companyId, String idempotencyKey) {
        return jdbc.query(
            "SELECT * FROM approval_requests WHERE company_id = ? AND idempotency_key = ?",
            this::mapRequest,
            companyId,
            idempotencyKey
        ).stream().findFirst();
    }

    @Override
    public void lockEntity(long companyId, String entityType, long entityId) {
        advisoryLock("approval:" + companyId + ":" + entityType + ":" + entityId);
    }

    @Override
    public boolean hasPendingRequest(long companyId, String entityType, long entityId) {
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM approval_requests
            WHERE company_id = ? AND entity_type = ? AND entity_id = ? AND status = 'pending'
            """, Integer.class, companyId, entityType, entityId);
        return pending != null && pending > 0;
    }

    @Override
    public boolean isValidAssignee(long companyId, long ownerId, long assigneeId) {
        Integer allowed = jdbc.queryForObject("""
            SELECT COUNT(*) FROM users u
            WHERE u.id = ? AND (
                u.id = ? OR EXISTS (
                    SELECT 1 FROM employees e
                    WHERE e.company_id = ? AND e.user_id = u.id AND e.status <> 'departed'
                )
            )
            """, Integer.class, assigneeId, ownerId, companyId);
        return allowed != null && allowed > 0;
    }

    @Override
    public ApprovalRequest insert(NewApproval approval) {
        return jdbc.queryForObject("""
            INSERT INTO approval_requests (
                company_id, request_type, entity_type, entity_id, title, amount, applicant_user_id,
                assignee_user_id, status, current_step, description, decided_at, created_at, updated_at,
                idempotency_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            RETURNING *
            """, this::mapRequest,
            approval.companyId(),
            approval.requestType(),
            approval.entityType(),
            approval.entityId(),
            approval.title(),
            approval.amount().toPlainString(),
            approval.applicantUserId(),
            approval.assigneeUserId(),
            approval.status(),
            approval.currentStep(),
            approval.description(),
            approval.createdAt(),
            approval.updatedAt(),
            approval.idempotencyKey()
        );
    }

    @Override
    public void updateState(long id, String status, String currentStep, String decidedAt, String updatedAt) {
        jdbc.update("""
            UPDATE approval_requests
            SET status = ?, current_step = ?, decided_at = ?, updated_at = ?, version = version + 1
            WHERE id = ?
            """, status, currentStep, decidedAt, updatedAt, id);
    }

    @Override
    public void insertAction(NewAction action) {
        jdbc.update("""
            INSERT INTO approval_actions (request_id, actor_user_id, action, comment, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, action.requestId(), action.actorUserId(), action.action(), action.comment(), action.createdAt());
    }

    private void advisoryLock(String key) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) result -> { },
            key
        );
    }

    private void addFilter(StringBuilder where, List<Object> arguments, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.append(" AND ").append(column).append(" = ?");
        arguments.add(value);
    }

    private ApprovalRequest mapRequest(ResultSet result, int rowNumber) throws SQLException {
        long assignee = result.getLong("assignee_user_id");
        Long assigneeUserId = result.wasNull() ? null : assignee;
        return new ApprovalRequest(
            result.getLong("id"),
            result.getLong("version"),
            result.getString("idempotency_key"),
            result.getLong("company_id"),
            result.getString("request_type"),
            result.getString("entity_type"),
            nullableLong(result, "entity_id"),
            result.getString("title"),
            new BigDecimal(result.getString("amount")),
            result.getLong("applicant_user_id"),
            assigneeUserId,
            result.getString("status"),
            result.getString("current_step"),
            result.getString("description"),
            result.getString("decided_at"),
            result.getString("created_at"),
            result.getString("updated_at")
        );
    }

    private ApprovalAction mapAction(ResultSet result, int rowNumber) throws SQLException {
        return new ApprovalAction(
            result.getLong("id"),
            result.getLong("request_id"),
            result.getLong("actor_user_id"),
            result.getString("action"),
            result.getString("comment"),
            result.getString("created_at")
        );
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
