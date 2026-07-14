package com.mamoji.service;

import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.common.Roles;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.nullableText;
import static com.mamoji.common.PayloadReader.number;
import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.textOr;

@Service
public class ApprovalService {
    private static final Set<String> REQUEST_TYPES = Set.of(
        "reimbursement", "payment", "budget_adjustment", "onboarding", "offboarding", "payroll_close", "other"
    );
    private static final Set<String> ENTITY_TYPES = Set.of(
        "receipt_voucher", "transaction", "budget", "employee", "payroll_run", "other"
    );

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    private final EnterpriseStore enterpriseStore;
    private final ReceiptService receiptService;

    public ApprovalService(
        JdbcTemplate jdbc,
        AccessControlService accessControl,
        EnterpriseStore enterpriseStore,
        ReceiptService receiptService
    ) {
        this.jdbc = jdbc;
        this.accessControl = accessControl;
        this.enterpriseStore = enterpriseStore;
        this.receiptService = receiptService;
    }

    public PagedResponse<ApprovalRequest> list(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        PageRequest page = PageRequest.from(params);
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(company.id);
        if (user.role != Roles.ADMIN) {
            where.append(" AND (applicant_user_id = ? OR assignee_user_id = ?)");
            args.add(user.id);
            args.add(user.id);
        }
        addFilter(where, args, "status", params.get("status"));
        addFilter(where, args, "request_type", params.get("requestType"));
        String keyword = Objects.toString(params.get("keyword"), "").trim().toLowerCase(Locale.ROOT);
        if (!keyword.isBlank()) {
            where.append(" AND (LOWER(title) LIKE ? OR LOWER(COALESCE(description, '')) LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM approval_requests" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(page.size());
        pageArgs.add((long) page.page() * page.size());
        List<ApprovalRequest> content = jdbc.query(
            "SELECT * FROM approval_requests" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
            this::mapRequest,
            pageArgs.toArray()
        );
        long totalElements = total == null ? 0 : total;
        int totalPages = (int) Math.ceil((double) totalElements / page.size());
        return new PagedResponse<>(content, totalElements, totalPages, page.size(), page.page());
    }

    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        String accessClause = user.role == Roles.ADMIN ? "" : " AND (applicant_user_id = ? OR assignee_user_id = ?)";
        Object[] args = user.role == Roles.ADMIN ? new Object[] { company.id } : new Object[] { company.id, user.id, user.id };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count("SELECT COUNT(*) FROM approval_requests WHERE company_id = ?" + accessClause, args));
        result.put("pending", count("SELECT COUNT(*) FROM approval_requests WHERE company_id = ? AND status = 'pending'" + accessClause, args));
        result.put("approved", count("SELECT COUNT(*) FROM approval_requests WHERE company_id = ? AND status = 'approved'" + accessClause, args));
        result.put("rejected", count("SELECT COUNT(*) FROM approval_requests WHERE company_id = ? AND status = 'rejected'" + accessClause, args));
        result.put("minePending", count("SELECT COUNT(*) FROM approval_requests WHERE company_id = ? AND status = 'pending' AND assignee_user_id = ?", company.id, user.id));
        return result;
    }

    public ApprovalDetail get(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequest(id);
        accessControl.resolveCompany(user, request.companyId);
        assertCanView(user, request);
        List<ApprovalAction> actions = jdbc.query(
            "SELECT * FROM approval_actions WHERE request_id = ? ORDER BY id",
            this::mapAction,
            id
        );
        return new ApprovalDetail(request, actions);
    }

    @Transactional
    public ApprovalDetail create(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        String requestType = allowed(textOr(body.get("requestType"), "other"), REQUEST_TYPES, "requestType");
        String entityType = allowed(textOr(body.get("entityType"), "other"), ENTITY_TYPES, "entityType");
        Long entityId = optionalLong(body.get("entityId")).orElse(null);
        validateEntity(user, company.id, entityType, entityId);
        if (entityId != null) {
            Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_requests
                WHERE company_id = ? AND entity_type = ? AND entity_id = ? AND status = 'pending'
                """, Integer.class, company.id, entityType, entityId);
            if (pending != null && pending > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This entity already has a pending approval request");
            }
        }
        long assigneeId = optionalLong(body.get("assigneeUserId")).orElse(company.ownerId);
        validateAssignee(company, assigneeId);
        String title = limited(textOr(body.get("title"), "新审批申请"), 160, "title");
        String description = limitedNullable(nullableText(body.get("description")), 1000, "description");
        BigDecimal amount = number(body.get("amount"), BigDecimal.ZERO);
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must not be negative");
        }
        String now = com.mamoji.repository.InMemoryStore.now();
        ApprovalRequest request = jdbc.queryForObject("""
            INSERT INTO approval_requests (
                company_id, request_type, entity_type, entity_id, title, amount, applicant_user_id,
                assignee_user_id, status, current_step, description, decided_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'review', ?, NULL, ?, ?)
            RETURNING *
            """, this::mapRequest, company.id, requestType, entityType, entityId, title, amount.toPlainString(),
            user.id, assigneeId, description, now, now);
        addAction(request.id, user.id, "submit", limitedNullable(nullableText(body.get("comment")), 500, "comment"));
        syncEntity(authorization, request, "pending");
        enterpriseStore.auditLog(company.id, "approval_request", request.id, "submit", "提交审批: " + title, user.id, user.nickname);
        return get(authorization, request.id);
    }

    @Transactional
    public ApprovalDetail decide(String authorization, long id, String action, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequestForUpdate(id);
        accessControl.resolveCompany(user, request.companyId);
        if (!"pending".equals(request.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending requests can be decided");
        }
        if (user.role != Roles.ADMIN && !Objects.equals(request.assigneeUserId, user.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assignee or an administrator can decide this request");
        }
        String status = switch (action) {
            case "approve" -> "approved";
            case "reject" -> "rejected";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported approval action");
        };
        String comment = limitedNullable(nullableText(body.get("comment")), 500, "comment");
        if ("rejected".equals(status) && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection comment is required");
        }
        String now = com.mamoji.repository.InMemoryStore.now();
        jdbc.update("UPDATE approval_requests SET status = ?, current_step = 'completed', decided_at = ?, updated_at = ? WHERE id = ?",
            status, now, now, id);
        addAction(id, user.id, action, comment);
        syncEntity(authorization, request, status);
        enterpriseStore.auditLog(request.companyId, "approval_request", id, action, ("approved".equals(status) ? "审批通过: " : "审批驳回: ") + request.title, user.id, user.nickname);
        return get(authorization, id);
    }

    @Transactional
    public ApprovalDetail withdraw(String authorization, long id, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequestForUpdate(id);
        accessControl.resolveCompany(user, request.companyId);
        if (request.applicantUserId != user.id || !"pending".equals(request.status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the applicant can withdraw a pending request");
        }
        String now = com.mamoji.repository.InMemoryStore.now();
        jdbc.update("UPDATE approval_requests SET status = 'withdrawn', current_step = 'completed', decided_at = ?, updated_at = ? WHERE id = ?", now, now, id);
        addAction(id, user.id, "withdraw", limitedNullable(nullableText(body.get("comment")), 500, "comment"));
        syncEntity(authorization, request, "not_submitted");
        enterpriseStore.auditLog(request.companyId, "approval_request", id, "withdraw", "撤回审批: " + request.title, user.id, user.nickname);
        return get(authorization, id);
    }

    private void syncEntity(String authorization, ApprovalRequest request, String status) {
        if ("receipt_voucher".equals(request.entityType) && request.entityId != null) {
            receiptService.updateApprovalStatus(authorization, request.entityId, status);
        }
    }

    private void validateEntity(User user, long companyId, String entityType, Long entityId) {
        if (entityId == null) return;
        if ("receipt_voucher".equals(entityType)) {
            ReceiptVoucher voucher = enterpriseStore.receiptVouchers.get(entityId);
            if (voucher == null || voucher.companyId != companyId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt voucher is outside the selected company");
            }
            if (!accessControl.hasFinanceManagerRole(user, companyId) && voucher.operatorUserId != user.id) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the submitter or a finance manager can submit this receipt");
            }
        }
    }

    private void validateAssignee(Company company, long assigneeId) {
        Integer allowed = jdbc.queryForObject("""
            SELECT COUNT(*) FROM users u
            WHERE u.id = ? AND (
                u.id = ? OR EXISTS (
                    SELECT 1 FROM employees e
                    WHERE e.company_id = ? AND e.user_id = u.id AND e.status <> 'departed'
                )
            )
            """, Integer.class, assigneeId, company.ownerId, company.id);
        if (allowed == null || allowed == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee must be an active member of the selected company");
        }
    }

    private ApprovalRequest requireRequest(long id) {
        List<ApprovalRequest> rows = jdbc.query("SELECT * FROM approval_requests WHERE id = ?", this::mapRequest, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found");
        return rows.getFirst();
    }

    private ApprovalRequest requireRequestForUpdate(long id) {
        List<ApprovalRequest> rows = jdbc.query("SELECT * FROM approval_requests WHERE id = ? FOR UPDATE", this::mapRequest, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found");
        return rows.getFirst();
    }

    private void assertCanView(User user, ApprovalRequest request) {
        if (user.role != Roles.ADMIN && request.applicantUserId != user.id && !Objects.equals(request.assigneeUserId, user.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void addAction(long requestId, long actorUserId, String action, String comment) {
        jdbc.update("INSERT INTO approval_actions (request_id, actor_user_id, action, comment, created_at) VALUES (?, ?, ?, ?, ?)",
            requestId, actorUserId, action, comment, com.mamoji.repository.InMemoryStore.now());
    }

    private void addFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) return;
        where.append(" AND ").append(column).append(" = ?");
        args.add(value);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String allowed(String value, Set<String> values, String field) {
        if (!values.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported " + field);
        return value;
    }

    private String limited(String value, int max, String field) {
        if (value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        if (value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        return value;
    }

    private String limitedNullable(String value, int max, String field) {
        if (value != null && value.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        return value;
    }

    private ApprovalRequest mapRequest(ResultSet rs, int rowNum) throws SQLException {
        long assignee = rs.getLong("assignee_user_id");
        Long assigneeUserId = rs.wasNull() ? null : assignee;
        return new ApprovalRequest(
            rs.getLong("id"), rs.getLong("company_id"), rs.getString("request_type"), rs.getString("entity_type"),
            nullableLong(rs, "entity_id"), rs.getString("title"), new BigDecimal(rs.getString("amount")),
            rs.getLong("applicant_user_id"), assigneeUserId, rs.getString("status"),
            rs.getString("current_step"), rs.getString("description"), rs.getString("decided_at"),
            rs.getString("created_at"), rs.getString("updated_at")
        );
    }

    private ApprovalAction mapAction(ResultSet rs, int rowNum) throws SQLException {
        return new ApprovalAction(rs.getLong("id"), rs.getLong("request_id"), rs.getLong("actor_user_id"),
            rs.getString("action"), rs.getString("comment"), rs.getString("created_at"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ApprovalRequest(
        long id, long companyId, String requestType, String entityType, Long entityId, String title,
        BigDecimal amount, long applicantUserId, Long assigneeUserId, String status, String currentStep,
        String description, String decidedAt, String createdAt, String updatedAt
    ) {}

    public record ApprovalAction(long id, long requestId, long actorUserId, String action, String comment, String createdAt) {}

    public record ApprovalDetail(ApprovalRequest request, List<ApprovalAction> actions) {}
}
