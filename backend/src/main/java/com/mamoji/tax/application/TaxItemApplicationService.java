package com.mamoji.tax.application;

import com.mamoji.platform.tenant.Company;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import com.mamoji.tax.api.TaxItemCreateRequest;
import com.mamoji.tax.api.TaxItemUpdateRequest;
import com.mamoji.tax.domain.TaxItem;
import com.mamoji.tax.domain.TaxItemPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaxItemApplicationService {
    private final TaxItemRepository repository;
    private final TaxItemPolicy policy;
    private final AccessContextService accessContext;
    private final AccessControlService accessControl;
    private final AuditTrailService auditTrail;
    private final OutboxEventService outbox;

    public TaxItemApplicationService(
        TaxItemRepository repository,
        TaxItemPolicy policy,
        AccessContextService accessContext,
        AccessControlService accessControl,
        AuditTrailService auditTrail,
        OutboxEventService outbox
    ) {
        this.repository = repository;
        this.policy = policy;
        this.accessContext = accessContext;
        this.accessControl = accessControl;
        this.auditTrail = auditTrail;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<TaxItem> list(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        return repository.findByCompany(company.id);
    }

    @Transactional
    public TaxItem create(ActorContext actor, TaxItemCreateRequest request) {
        Company company = accessContext.requireCompany(actor, request.companyId());
        accessControl.requireFinanceManager(actor.user(), company.id);
        LocalDate today = LocalDate.now();
        String now = OffsetDateTime.now().toString();
        TaxItem item = new TaxItem();
        item.companyId = company.id;
        item.name = normalizedOr(request.name(), "新税务事项");
        item.period = upperOr(request.period(), YearMonth.now().toString());
        item.taxType = lowerOr(request.taxType(), "vat");
        item.taxableAmount = moneyOrZero(request.taxableAmount());
        item.taxAmount = moneyOrZero(request.taxAmount());
        item.paidAmount = moneyOrZero(request.paidAmount());
        item.deductibleAmount = moneyOrZero(request.deductibleAmount());
        item.taxRate = request.taxRate();
        item.dueDate = (request.dueDate() == null ? today.plusDays(15) : request.dueDate()).toString();
        item.status = lowerOr(request.status(), "estimated");
        item.filingStatus = normalizedLower(request.filingStatus());
        item.paymentStatus = normalizedLower(request.paymentStatus());
        item.frequency = normalizedLower(request.frequency());
        item.declarationDate = text(request.declarationDate());
        item.paymentDate = text(request.paymentDate());
        item.responsiblePerson = normalized(request.responsiblePerson());
        item.riskLevel = normalizedLower(request.riskLevel());
        item.policyBasis = normalized(request.policyBasis());
        item.sourceType = lowerOr(request.sourceType(), "manual");
        item.note = normalized(request.note());
        item.createdAt = now;
        item.updatedAt = now;
        policy.apply(item, request.status() != null, request.taxRate() != null, company.policyProfileKey, today);
        repository.insert(item);
        audit(item, "create", "创建税费事项: " + item.name, actor);
        return item;
    }

    @Transactional
    public TaxItem update(ActorContext actor, long id, TaxItemUpdateRequest request) {
        TaxItem item = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tax item not found"));
        Company company = accessContext.requireCompany(actor, item.companyId);
        accessControl.requireFinanceManager(actor.user(), company.id);
        if (request.name != null) item.name = request.name.trim();
        if (request.period != null) item.period = request.period.trim().toUpperCase(Locale.ROOT);
        if (request.taxType != null) item.taxType = request.taxType.trim().toLowerCase(Locale.ROOT);
        if (request.taxableAmount != null) item.taxableAmount = request.taxableAmount;
        if (request.taxAmount != null) item.taxAmount = request.taxAmount;
        if (request.paidAmount != null) item.paidAmount = request.paidAmount;
        if (request.deductibleAmount != null) item.deductibleAmount = request.deductibleAmount;
        if (request.taxRate != null) item.taxRate = request.taxRate;
        if (request.dueDate != null) item.dueDate = request.dueDate.toString();
        if (request.status != null) item.status = request.status.trim().toLowerCase(Locale.ROOT);
        if (request.filingStatus != null) item.filingStatus = request.filingStatus.trim().toLowerCase(Locale.ROOT);
        if (request.frequency != null) item.frequency = request.frequency.trim().toLowerCase(Locale.ROOT);
        if (request.sourceType != null) item.sourceType = request.sourceType.trim().toLowerCase(Locale.ROOT);
        if (request.hasDeclarationDate()) item.declarationDate = text(request.declarationDate());
        if (request.hasPaymentDate()) item.paymentDate = text(request.paymentDate());
        if (request.hasResponsiblePerson()) item.responsiblePerson = normalized(request.responsiblePerson());
        if (request.hasPolicyBasis()) item.policyBasis = normalized(request.policyBasis());
        if (request.hasNote()) item.note = normalized(request.note());
        item.updatedAt = OffsetDateTime.now().toString();
        policy.apply(item, request.status != null, request.taxRate != null, company.policyProfileKey, LocalDate.now());
        repository.update(item);
        audit(item, "update", "更新税费事项: " + item.name, actor);
        return item;
    }

    @Transactional
    public void delete(ActorContext actor, long id) {
        TaxItem item = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tax item not found"));
        Company company = accessContext.requireCompany(actor, item.companyId);
        accessControl.requireFinanceManager(actor.user(), company.id);
        repository.delete(id);
        audit(item, "delete", "删除税费事项: " + item.name, actor);
    }

    private void audit(TaxItem item, String action, String summary, ActorContext actor) {
        auditTrail.record(
            item.companyId,
            "tax_item",
            item.id,
            action,
            summary,
            actor.userId(),
            actor.user().nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", actor.user().nickname);
        payload.put("entityType", "tax_item");
        payload.put("action", action);
        payload.put("period", item.period);
        payload.put("taxType", item.taxType);
        payload.put("taxAmount", item.taxAmount);
        outbox.publish(
            "enterprise.tax_item." + action,
            item.companyId,
            "tax_item",
            item.id,
            actor.userId(),
            payload
        );
    }

    private BigDecimal moneyOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalized(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizedLower(String value) {
        String normalized = normalized(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizedOr(String value, String fallback) {
        String normalized = normalized(value);
        return normalized == null ? fallback : normalized;
    }

    private String lowerOr(String value, String fallback) {
        String normalized = normalizedLower(value);
        return normalized == null ? fallback : normalized;
    }

    private String upperOr(String value, String fallback) {
        String normalized = normalized(value);
        return normalized == null ? fallback : normalized.toUpperCase(Locale.ROOT);
    }

    private String text(LocalDate value) {
        return value == null ? null : value.toString();
    }
}
