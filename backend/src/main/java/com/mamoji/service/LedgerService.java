package com.mamoji.service;

import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.longValue;
import static com.mamoji.common.PayloadReader.optionalLong;
import static com.mamoji.common.PayloadReader.textOr;
import static com.mamoji.service.support.DomainSupport.require;

@Service
public class LedgerService {
    private final InMemoryStore store;
    private final AccessControlService accessControl;

    public LedgerService(InMemoryStore store, AccessControlService accessControl) {
        this.store = store;
        this.accessControl = accessControl;
    }

    public List<Ledger> listLedgers(String authorization) {
        return listLedgers(authorization, null);
    }

    public List<Ledger> listLedgers(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        long userId = user.id;
        return store.ledgers.values().stream()
            .filter(ledger -> Objects.equals(ledger.companyId, company.id))
            .filter(ledger -> ledger.ownerId == userId || isLedgerMember(ledger.id, userId))
            .sorted(Comparator.comparing(ledger -> ledger.id))
            .toList();
    }

    public Ledger defaultLedger(String authorization) {
        return defaultLedger(authorization, null);
    }

    public Ledger defaultLedger(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        return defaultLedgerId(user.id, company.id)
            .map(id -> store.ledgers.get(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default ledger not found"));
    }

    public Ledger getLedger(String authorization, long id) {
        return getLedger(authorization, id, null);
    }

    public Ledger getLedger(String authorization, long id, Long companyId) {
        User user = accessControl.requireUser(authorization);
        long userId = user.id;
        Ledger ledger = require(store.ledgers.get(id), "Ledger not found");
        Company company = accessControl.resolveCompany(user, companyId == null ? ledger.companyId : companyId);
        if (!Objects.equals(ledger.companyId, company.id)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ledger is outside the selected company");
        }
        if (ledger.ownerId != userId && !isLedgerMember(ledger.id, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to ledger");
        }
        return ledger;
    }

    @Transactional
    public Ledger createLedger(String authorization, Map<String, Object> body) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, optionalLong(body.get("companyId")).orElse(null));
        long userId = user.id;
        Ledger ledger = store.ledger(
            userId,
            company.id,
            textOr(body.get("name"), "新账本"),
            textOr(body.get("description"), ""),
            textOr(body.get("currency"), "CNY"),
            false
        );
        store.member(ledger.id, userId, "owner");
        return ledger;
    }

    public List<LedgerMember> ledgerMembers(String authorization, long ledgerId) {
        getLedger(authorization, ledgerId);
        return store.ledgerMembers.values().stream()
            .filter(member -> member.ledgerId == ledgerId)
            .sorted(Comparator.comparing(member -> member.id))
            .toList();
    }

    @Transactional
    public Map<String, Object> addLedgerMember(String authorization, long ledgerId, Map<String, Object> body) {
        getLedger(authorization, ledgerId);
        Ledger ledger = store.ledgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can add members");
        }
        long userId = longValue(body.get("userId"), 0);
        require(store.users.get(userId), "User not found");
        if (!store.ledgerMemberExists(ledgerId, userId)) {
            store.member(ledgerId, userId, textOr(body.get("role"), "viewer"));
        }
        return Map.of("success", true);
    }

    @Transactional
    public void removeLedgerMember(String authorization, long ledgerId, long userId) {
        getLedger(authorization, ledgerId);
        Ledger ledger = store.ledgerForUpdate(ledgerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ledger not found"));
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can remove members");
        }
        store.deleteLedgerMember(ledgerId, userId);
    }

    private Optional<Long> defaultLedgerId(long userId, long companyId) {
        return store.ledgers.values().stream()
            .filter(ledger -> Objects.equals(ledger.companyId, companyId))
            .filter(ledger -> ledger.ownerId == userId && ledger.isDefault)
            .map(ledger -> ledger.id)
            .min(Long::compareTo)
            .or(() -> store.ledgers.values().stream()
                .filter(ledger -> ledger.ownerId == userId && Objects.equals(ledger.companyId, companyId))
                .map(ledger -> ledger.id)
                .min(Long::compareTo));
    }

    private boolean isLedgerMember(long ledgerId, long userId) {
        return store.ledgerMembers.values().stream().anyMatch(member -> member.ledgerId == ledgerId && member.userId == userId);
    }
}
