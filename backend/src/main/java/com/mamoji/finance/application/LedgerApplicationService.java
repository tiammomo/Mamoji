package com.mamoji.finance.application;

import com.mamoji.platform.tenant.Company;
import com.mamoji.finance.api.LedgerCreateRequest;
import com.mamoji.finance.api.LedgerMemberCreateRequest;
import com.mamoji.finance.application.FinanceRepository.MemberProfile;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.finance.domain.LedgerMember;
import com.mamoji.platform.access.AccessContextService;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.tenant.CompanyMembershipRepository;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.OutboxEventService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Finance application boundary for company-scoped ledgers and membership. */
@Service
public class LedgerApplicationService {
    private final FinanceRepository repository;
    private final AccessContextService accessContext;
    private final CompanyMembershipRepository companyMemberships;
    private final EnterpriseStore auditStore;
    private final OutboxEventService outbox;

    public LedgerApplicationService(
        FinanceRepository repository,
        AccessContextService accessContext,
        CompanyMembershipRepository companyMemberships,
        EnterpriseStore auditStore,
        OutboxEventService outbox
    ) {
        this.repository = repository;
        this.accessContext = accessContext;
        this.companyMemberships = companyMemberships;
        this.auditStore = auditStore;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Ledger> listLedgers(ActorContext actor, Long companyId) {
        Company company = readableCompany(actor, companyId);
        return repository.findAccessibleLedgers(actor.userId(), company.id);
    }

    @Transactional(readOnly = true)
    public Ledger defaultLedger(ActorContext actor, Long companyId) {
        Company company = readableCompany(actor, companyId);
        return defaultLedgerId(actor.userId(), company.id)
            .flatMap(repository::findLedger)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default ledger not found"));
    }

    @Transactional(readOnly = true)
    public Ledger getLedger(ActorContext actor, long id, Long companyId) {
        Ledger ledger = repository.findLedger(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        Company company = readableCompany(actor, companyId == null ? ledger.companyId : companyId);
        if (ledger.companyId != company.id) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ledger is outside the selected company");
        }
        if (ledger.ownerId != actor.userId() && !repository.ledgerMemberExists(ledger.id, actor.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to ledger");
        }
        return ledger;
    }

    @Transactional
    public Ledger createLedger(ActorContext actor, LedgerCreateRequest request) {
        Company company = writableCompany(actor, request.companyId());
        String now = OffsetDateTime.now().toString();
        Ledger ledger = new Ledger();
        ledger.ownerId = actor.userId();
        ledger.companyId = company.id;
        ledger.name = request.name().trim();
        ledger.description = request.description() == null ? "" : request.description().trim();
        ledger.currency = normalizedCurrency(request.currency(), company.currency);
        ledger.isDefault = false;
        ledger.status = 1;
        ledger.createdAt = now;
        ledger.updatedAt = now;
        repository.insertLedger(ledger);
        repository.insertLedgerMember(member(company.id, ledger.id, profile(actor.userId()), "owner"));
        audit(company.id, ledger.id, "create", "创建经营账本: " + ledger.name, actor, Map.of(
            "ledgerName", ledger.name,
            "currency", ledger.currency
        ));
        return ledger;
    }

    @Transactional(readOnly = true)
    public List<LedgerMember> ledgerMembers(ActorContext actor, long ledgerId) {
        getLedger(actor, ledgerId, null);
        return repository.findLedgerMembers(ledgerId);
    }

    @Transactional
    public Map<String, Object> addLedgerMember(
        ActorContext actor,
        long ledgerId,
        LedgerMemberCreateRequest request
    ) {
        Ledger readable = getLedger(actor, ledgerId, null);
        writableCompany(actor, readable.companyId);
        Ledger ledger = repository.findLedgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        if (ledger.ownerId != actor.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can add members");
        }
        long userId = request.userId();
        companyMemberships.find(userId, ledger.companyId)
            .filter(membership -> membership.active())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Target user is not an active company member"
            ));
        MemberProfile profile = profile(userId);
        if (!repository.ledgerMemberExists(ledgerId, userId)) {
            String role = request.role() == null ? "viewer" : request.role().trim().toLowerCase(Locale.ROOT);
            repository.insertLedgerMember(member(ledger.companyId, ledgerId, profile, role));
            audit(ledger.companyId, ledger.id, "member_added", "添加账本成员: " + userId, actor, Map.of(
                "memberUserId", userId,
                "role", role
            ));
        }
        return Map.of("success", true);
    }

    @Transactional
    public void removeLedgerMember(ActorContext actor, long ledgerId, long userId) {
        Ledger readable = getLedger(actor, ledgerId, null);
        writableCompany(actor, readable.companyId);
        Ledger ledger = repository.findLedgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        if (ledger.ownerId != actor.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can remove members");
        }
        if (ledger.ownerId == userId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ledger owner membership cannot be removed");
        }
        if (repository.deleteLedgerMember(ledgerId, userId)) {
            audit(ledger.companyId, ledger.id, "member_removed", "移除账本成员: " + userId, actor, Map.of(
                "memberUserId", userId
            ));
        }
    }

    private Company readableCompany(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        accessContext.requirePermission(actor, company.id, "finance.read");
        return company;
    }

    private Company writableCompany(ActorContext actor, Long companyId) {
        Company company = accessContext.requireCompany(actor, companyId);
        accessContext.requirePermission(actor, company.id, "finance.write");
        return company;
    }

    private Optional<Long> defaultLedgerId(long userId, long companyId) {
        List<Ledger> ledgers = repository.findAccessibleLedgers(userId, companyId);
        return ledgers.stream()
            .filter(ledger -> ledger.isDefault)
            .map(ledger -> ledger.id)
            .min(Long::compareTo)
            .or(() -> ledgers.stream().map(ledger -> ledger.id).min(Long::compareTo));
    }

    private MemberProfile profile(long userId) {
        return repository.findMemberProfile(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private LedgerMember member(
        long companyId,
        long ledgerId,
        MemberProfile profile,
        String role
    ) {
        LedgerMember member = new LedgerMember();
        member.companyId = companyId;
        member.ledgerId = ledgerId;
        member.userId = profile.userId();
        member.nickname = profile.nickname();
        member.avatar = profile.avatar();
        member.role = role;
        member.joinedAt = OffsetDateTime.now().toString();
        return member;
    }

    private String normalizedCurrency(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested;
        return (value == null || value.isBlank() ? "CNY" : value).trim().toUpperCase(Locale.ROOT);
    }

    private void audit(
        long companyId,
        long ledgerId,
        String action,
        String summary,
        ActorContext actor,
        Map<String, Object> attributes
    ) {
        auditStore.auditLog(
            companyId,
            "ledger",
            ledgerId,
            action,
            summary,
            actor.userId(),
            actor.user().nickname
        );
        Map<String, Object> payload = new LinkedHashMap<>(attributes);
        payload.put("summary", summary);
        payload.put("action", action);
        outbox.publish("finance.ledger." + action, companyId, "ledger", ledgerId, actor.userId(), payload);
    }
}
