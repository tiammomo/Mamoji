package com.mamoji.approval.application;

import com.mamoji.approval.api.ApprovalActionRequest;
import com.mamoji.approval.api.ApprovalCreateRequest;
import com.mamoji.approval.api.ApprovalDetail;
import com.mamoji.approval.application.ApprovalRepository.NewAction;
import com.mamoji.approval.application.ApprovalRepository.NewApproval;
import com.mamoji.approval.domain.ApprovalAction;
import com.mamoji.approval.domain.ApprovalRequest;
import com.mamoji.approval.domain.ApprovalWorkflow;
import com.mamoji.approval.domain.ApprovalWorkflow.Action;
import com.mamoji.approval.domain.ApprovalWorkflow.Transition;
import com.mamoji.common.PageRequest;
import com.mamoji.common.PagedResponse;
import com.mamoji.common.Roles;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.tenant.Company;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.optionalLong;

@Service
public class ApprovalApplicationService {
    private static final Set<String> REQUEST_TYPES = Set.of(
        "reimbursement", "payment", "budget_adjustment", "onboarding", "offboarding", "payroll_close", "other"
    );
    private static final Set<String> ENTITY_TYPES = Set.of(
        "receipt_voucher", "transaction", "budget", "employee", "payroll_run", "other"
    );

    private final ApprovalRepository repository;
    private final AccessControlService accessControl;
    private final AuditTrailService auditTrail;
    private final ApprovalEntityGateway entityGateway;

    public ApprovalApplicationService(
        ApprovalRepository repository,
        AccessControlService accessControl,
        AuditTrailService auditTrail,
        ApprovalEntityGateway entityGateway
    ) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.auditTrail = auditTrail;
        this.entityGateway = entityGateway;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PagedResponse<ApprovalRequest> list(String authorization, Map<String, String> params) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(params.get("companyId")).orElse(null));
        PageRequest page = PageRequest.from(params);
        return repository.findPage(
            company.id,
            user.role == Roles.ADMIN ? null : user.id,
            params.get("status"),
            params.get("requestType"),
            params.get("keyword"),
            page
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return repository.summarize(company.id, user.id, user.role == Roles.ADMIN);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalDetail get(String authorization, long id) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequest(id);
        accessControl.resolveCompany(user, request.companyId());
        assertCanView(user, request);
        List<ApprovalAction> actions = repository.findActions(id);
        return new ApprovalDetail(request, actions);
    }

    @Transactional
    public ApprovalDetail create(
        String authorization,
        ApprovalCreateRequest command,
        String headerIdempotencyKey
    ) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, command.companyId());
        String suppliedIdempotencyKey = headerIdempotencyKey == null || headerIdempotencyKey.isBlank()
            ? command.idempotencyKey()
            : headerIdempotencyKey;
        String idempotencyKey = idempotencyKey(suppliedIdempotencyKey);
        if (idempotencyKey != null) {
            repository.lockIdempotencyKey(company.id, idempotencyKey);
            ApprovalRequest replay = repository.findByIdempotencyKey(company.id, idempotencyKey).orElse(null);
            if (replay != null) return get(authorization, replay.id());
        }
        String requestType = allowed(valueOr(command.requestType(), "other"), REQUEST_TYPES, "requestType");
        String entityType = allowed(valueOr(command.entityType(), "other"), ENTITY_TYPES, "entityType");
        Long entityId = command.entityId();
        validateEntity(user, company.id, entityType, entityId);
        if (entityId != null) {
            repository.lockEntity(company.id, entityType, entityId);
            if (repository.hasPendingRequest(company.id, entityType, entityId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This entity already has a pending approval request");
            }
        }
        long assigneeId = command.assigneeUserId() == null ? company.ownerId : command.assigneeUserId();
        validateAssignee(company, assigneeId);
        String title = limited(valueOr(command.title(), "新审批申请"), 160, "title");
        String description = limitedNullable(blankToNull(command.description()), 1000, "description");
        BigDecimal amount = command.amount() == null ? BigDecimal.ZERO : command.amount();
        String now = OffsetDateTime.now().toString();
        Transition submission = ApprovalWorkflow.submission();
        ApprovalRequest request = repository.insert(new NewApproval(
            company.id,
            requestType,
            entityType,
            entityId,
            title,
            amount,
            user.id,
            assigneeId,
            submission.targetStatus().value(),
            submission.currentStep(),
            description,
            now,
            now,
            idempotencyKey
        ));
        addAction(
            request.id(),
            user.id,
            submission.action().value(),
            limitedNullable(blankToNull(command.comment()), 500, "comment")
        );
        syncEntity(authorization, request, submission.entityStatus());
        auditTrail.record(
            company.id,
            "approval_request",
            request.id(),
            submission.action().value(),
            auditSummary(submission.action(), title),
            user.id,
            user.nickname
        );
        return get(authorization, request.id());
    }

    @Transactional
    public ApprovalDetail decide(String authorization, long id, String action, ApprovalActionRequest command) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequestForUpdate(id);
        accessControl.resolveCompany(user, request.companyId());
        Action workflowAction = parseDecisionAction(action);
        Transition transition = requireTransition(request, workflowAction);
        if (user.role != Roles.ADMIN && !Objects.equals(request.assigneeUserId(), user.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assignee or an administrator can decide this request");
        }
        String comment = limitedNullable(blankToNull(command.comment()), 500, "comment");
        if (transition.commentRequired() && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection comment is required");
        }
        String now = OffsetDateTime.now().toString();
        repository.updateState(id, transition.targetStatus().value(), transition.currentStep(), now, now);
        addAction(id, user.id, transition.action().value(), comment);
        syncEntity(authorization, request, transition.entityStatus());
        auditTrail.record(
            request.companyId(),
            "approval_request",
            id,
            transition.action().value(),
            auditSummary(transition.action(), request.title()),
            user.id,
            user.nickname
        );
        return get(authorization, id);
    }

    @Transactional
    public ApprovalDetail withdraw(String authorization, long id, ApprovalActionRequest command) {
        User user = accessControl.requireUser(authorization);
        ApprovalRequest request = requireRequestForUpdate(id);
        accessControl.resolveCompany(user, request.companyId());
        if (request.applicantUserId() != user.id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the applicant can withdraw this request");
        }
        Transition transition = requireTransition(request, Action.WITHDRAW);
        String now = OffsetDateTime.now().toString();
        repository.updateState(id, transition.targetStatus().value(), transition.currentStep(), now, now);
        addAction(
            id,
            user.id,
            transition.action().value(),
            limitedNullable(blankToNull(command.comment()), 500, "comment")
        );
        syncEntity(authorization, request, transition.entityStatus());
        auditTrail.record(
            request.companyId(),
            "approval_request",
            id,
            transition.action().value(),
            auditSummary(transition.action(), request.title()),
            user.id,
            user.nickname
        );
        return get(authorization, id);
    }

    private Action parseDecisionAction(String action) {
        try {
            Action parsed = Action.fromExternal(action);
            if (parsed == Action.WITHDRAW) {
                throw new IllegalArgumentException("Withdraw uses the applicant workflow");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported approval action");
        }
    }

    private Transition requireTransition(ApprovalRequest request, Action action) {
        try {
            return ApprovalWorkflow.transition(ApprovalWorkflow.Status.fromStored(request.status()), action);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Approval request cannot transition from " + request.status() + " using " + action.value()
            );
        }
    }

    private String auditSummary(Action action, String title) {
        return switch (action) {
            case SUBMIT -> "提交审批: " + title;
            case APPROVE -> "审批通过: " + title;
            case REJECT -> "审批驳回: " + title;
            case WITHDRAW -> "撤回审批: " + title;
        };
    }

    private void syncEntity(String authorization, ApprovalRequest request, String status) {
        entityGateway.synchronizeStatus(authorization, request.entityType(), request.entityId(), status);
    }

    private void validateEntity(User user, long companyId, String entityType, Long entityId) {
        entityGateway.validateReference(user, companyId, entityType, entityId);
    }

    private void validateAssignee(Company company, long assigneeId) {
        if (!repository.isValidAssignee(company.id, company.ownerId, assigneeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee must be an active member of the selected company");
        }
    }

    private ApprovalRequest requireRequest(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found"));
    }

    private ApprovalRequest requireRequestForUpdate(long id) {
        return repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found"));
    }

    private void assertCanView(User user, ApprovalRequest request) {
        if (
            user.role != Roles.ADMIN
                && request.applicantUserId() != user.id
                && !Objects.equals(request.assigneeUserId(), user.id)
        ) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private void addAction(long requestId, long actorUserId, String action, String comment) {
        repository.insertAction(new NewAction(
            requestId,
            actorUserId,
            action,
            comment,
            OffsetDateTime.now().toString()
        ));
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

    private String idempotencyKey(String value) {
        String key = blankToNull(value);
        if (key == null) return null;
        key = key.trim();
        if (key.isEmpty()) return null;
        if (key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid idempotency key");
        }
        return key;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
