package com.mamoji.finance.application;

import com.mamoji.common.PayloadReader;
import com.mamoji.domain.Models.Company;
import com.mamoji.finance.application.FinanceRepository.MemberProfile;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.finance.domain.LedgerMember;
import com.mamoji.platform.identity.User;
import com.mamoji.service.support.AccessControlService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Finance application boundary for ledgers and ledger membership. */
@Service
public class LedgerApplicationService {
    private final FinanceRepository repository;
    private final AccessControlService accessControl;

    public LedgerApplicationService(FinanceRepository repository, AccessControlService accessControl) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public List<Ledger> listLedgers(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return repository.findAccessibleLedgers(user.id, company.id);
    }

    @Transactional(readOnly = true)
    public Ledger defaultLedger(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return defaultLedgerId(user.id, company.id)
            .flatMap(repository::findLedger)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default ledger not found"));
    }

    @Transactional(readOnly = true)
    public Ledger getLedger(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Ledger ledger = repository.findLedger(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        Company company = accessControl.resolveCompany(user, companyId == null ? ledger.companyId : companyId);
        if (!Objects.equals(ledger.companyId, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ledger is outside the selected company");
        }
        if (ledger.ownerId != user.id && !repository.ledgerMemberExists(ledger.id, user.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to ledger");
        }
        return ledger;
    }

    @Transactional
    public Ledger createLedger(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(
            user,
            PayloadReader.optionalLong(body.get("companyId")).orElse(null)
        );
        Ledger ledger = new Ledger();
        ledger.ownerId = user.id;
        ledger.companyId = company.id;
        ledger.name = PayloadReader.textOr(body.get("name"), "新账本");
        ledger.description = PayloadReader.textOr(body.get("description"), "");
        ledger.currency = PayloadReader.textOr(body.get("currency"), "CNY");
        ledger.isDefault = false;
        ledger.status = 1;
        ledger.createdAt = OffsetDateTime.now().toString();
        ledger.updatedAt = ledger.createdAt;
        repository.insertLedger(ledger);
        repository.insertLedgerMember(member(ledger.id, profile(user.id), "owner"));
        return ledger;
    }

    @Transactional(readOnly = true)
    public List<LedgerMember> ledgerMembers(String authorization, long ledgerId) {
        getLedger(authorization, ledgerId, null);
        return repository.findLedgerMembers(ledgerId);
    }

    @Transactional
    public Map<String, Object> addLedgerMember(
        String authorization,
        long ledgerId,
        Map<String, Object> body
    ) {
        getLedger(authorization, ledgerId, null);
        Ledger ledger = repository.findLedgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can add members");
        }
        long userId = PayloadReader.longValue(body.get("userId"), 0);
        MemberProfile profile = repository.findMemberProfile(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!repository.ledgerMemberExists(ledgerId, userId)) {
            repository.insertLedgerMember(member(
                ledgerId,
                profile,
                PayloadReader.textOr(body.get("role"), "viewer")
            ));
        }
        return Map.of("success", true);
    }

    @Transactional
    public void removeLedgerMember(String authorization, long ledgerId, long userId) {
        getLedger(authorization, ledgerId, null);
        Ledger ledger = repository.findLedgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can remove members");
        }
        repository.deleteLedgerMember(ledgerId, userId);
    }

    private Optional<Long> defaultLedgerId(long userId, long companyId) {
        List<Ledger> ledgers = repository.findOwnedLedgers(userId, companyId);
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

    private LedgerMember member(long ledgerId, MemberProfile profile, String role) {
        LedgerMember member = new LedgerMember();
        member.ledgerId = ledgerId;
        member.userId = profile.userId();
        member.nickname = profile.nickname();
        member.avatar = profile.avatar();
        member.role = role;
        member.joinedAt = OffsetDateTime.now().toString();
        return member;
    }
}
