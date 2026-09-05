package com.mamoji.accountingperiod.application;

import com.mamoji.accountingperiod.api.CloseAccountingPeriodRequest;
import com.mamoji.accountingperiod.api.ReopenAccountingPeriodRequest;
import com.mamoji.accountingperiod.domain.AccountingPeriodClosedException;
import com.mamoji.accountingperiod.domain.AccountingPeriodControl;
import com.mamoji.platform.audit.application.AuditTrailService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.User;
import com.mamoji.platform.tenant.Company;
import com.mamoji.service.OutboxEventService;
import com.mamoji.service.support.AccessControlService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountingPeriodService {
    private final AccountingPeriodRepository periods;
    private final AccessControlService accessControl;
    private final AuditTrailService auditTrail;
    private final OutboxEventService outboxEventService;

    public AccountingPeriodService(
        AccountingPeriodRepository periods,
        AccessControlService accessControl,
        AuditTrailService auditTrail,
        OutboxEventService outboxEventService
    ) {
        this.periods = periods;
        this.accessControl = accessControl;
        this.auditTrail = auditTrail;
        this.outboxEventService = outboxEventService;
    }

    @Transactional(readOnly = true)
    public AccountingPeriodControl current(ActorContext actor, Long companyId) {
        Company company = accessControl.resolveCompany(actor.user(), companyId);
        return requireControl(company.id, false);
    }

    @Transactional
    public AccountingPeriodControl close(ActorContext actor, CloseAccountingPeriodRequest request) {
        Company company = accessControl.resolveCompany(actor.user(), request.companyId());
        accessControl.requireFinanceManager(actor.user(), company.id);
        YearMonth throughMonth = parseMonth(request.throughMonth());
        if (!throughMonth.isBefore(YearMonth.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only completed months can be closed");
        }
        AccountingPeriodControl current = requireControl(company.id, true);
        requireVersion(current, request.version().longValue());
        LocalDate closedThrough = throughMonth.atEndOfMonth();
        if (current.closedThrough() != null && closedThrough.isBefore(current.closedThrough())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use reopen to move the close watermark backwards");
        }
        if (closedThrough.equals(current.closedThrough())) {
            return current;
        }
        OffsetDateTime now = OffsetDateTime.now();
        AccountingPeriodControl updated = periods.update(new AccountingPeriodControl(
            current.companyId(),
            current.version(),
            closedThrough,
            "CLOSE",
            now,
            actor.userId(),
            null,
            current.createdAt(),
            current.updatedAt()
        ));
        recordAction(updated, actor.user(), "close", "关闭会计期间至 " + closedThrough);
        return updated;
    }

    @Transactional
    public AccountingPeriodControl reopen(ActorContext actor, ReopenAccountingPeriodRequest request) {
        Company company = accessControl.resolveCompany(actor.user(), request.companyId());
        accessControl.requireFinanceManager(actor.user(), company.id);
        AccountingPeriodControl current = requireControl(company.id, true);
        requireVersion(current, request.version().longValue());
        if (current.closedThrough() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No closed accounting period to reopen");
        }
        LocalDate reopenedThrough = request.throughMonth() == null || request.throughMonth().isBlank()
            ? null
            : parseMonth(request.throughMonth()).atEndOfMonth();
        if (reopenedThrough != null && !reopenedThrough.isBefore(current.closedThrough())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reopen watermark must be earlier than the current close watermark");
        }
        String reason = request.reason().trim();
        OffsetDateTime now = OffsetDateTime.now();
        AccountingPeriodControl updated = periods.update(new AccountingPeriodControl(
            current.companyId(),
            current.version(),
            reopenedThrough,
            "REOPEN",
            now,
            actor.userId(),
            reason,
            current.createdAt(),
            current.updatedAt()
        ));
        String summary = reopenedThrough == null
            ? "反结账并全部开放会计期间：" + reason
            : "反结账至 " + reopenedThrough + "：" + reason;
        recordAction(updated, actor.user(), "reopen", summary);
        return updated;
    }

    /**
     * Locks the same company control row used by close/reopen so a financial write
     * either commits before the close watermark advances or observes the new watermark.
     */
    @Transactional
    public void requireWritable(long companyId, LocalDate... transactionDates) {
        AccountingPeriodControl control = requireControl(companyId, true);
        for (LocalDate transactionDate : transactionDates) {
            if (transactionDate != null && control.closes(transactionDate)) {
                throw new AccountingPeriodClosedException(companyId, transactionDate, control.closedThrough());
            }
        }
    }

    private AccountingPeriodControl requireControl(long companyId, boolean forUpdate) {
        return (forUpdate ? periods.findByCompanyForUpdate(companyId) : periods.findByCompany(companyId))
            .orElseThrow(() -> new IllegalStateException(
                "Accounting period control is missing for company " + companyId
            ));
    }

    private void requireVersion(AccountingPeriodControl current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                "Accounting period control was changed by another request: " + current.companyId()
            );
        }
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "throughMonth must use YYYY-MM", error);
        }
    }

    private void recordAction(
        AccountingPeriodControl control,
        User actor,
        String action,
        String summary
    ) {
        auditTrail.record(
            control.companyId(),
            "accounting_period_control",
            control.companyId(),
            action,
            summary,
            actor.id,
            actor.nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("actorName", actor.nickname);
        payload.put("entityType", "accounting_period_control");
        payload.put("action", action);
        payload.put("closedThrough", control.closedThrough() == null ? null : control.closedThrough().toString());
        payload.put("version", control.version());
        outboxEventService.publish(
            "accounting.period." + action,
            control.companyId(),
            "accounting_period_control",
            control.companyId(),
            actor.id,
            payload
        );
    }
}
