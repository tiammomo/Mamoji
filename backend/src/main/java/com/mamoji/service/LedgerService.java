package com.mamoji.service;

import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.repository.InMemoryStore;
import com.mamoji.service.support.AccessControlService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static com.mamoji.common.PayloadReader.longValue;
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
        long userId = accessControl.requireUser(authorization).id;
        return store.ledgers.values().stream()
            .filter(ledger -> ledger.ownerId == userId || isLedgerMember(ledger.id, userId))
            .sorted(Comparator.comparing(ledger -> ledger.id))
            .toList();
    }

    public Ledger defaultLedger(String authorization) {
        long userId = accessControl.requireUser(authorization).id;
        return defaultLedgerId(userId)
            .map(id -> store.ledgers.get(id))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Default ledger not found"));
    }

    public Ledger getLedger(String authorization, long id) {
        long userId = accessControl.requireUser(authorization).id;
        Ledger ledger = require(store.ledgers.get(id), "Ledger not found");
        if (ledger.ownerId != userId && !isLedgerMember(ledger.id, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No access to ledger");
        }
        return ledger;
    }

    public Ledger createLedger(String authorization, Map<String, Object> body) {
        long userId = accessControl.requireUser(authorization).id;
        Ledger ledger = store.ledger(
            userId,
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

    public Map<String, Object> addLedgerMember(String authorization, long ledgerId, Map<String, Object> body) {
        Ledger ledger = getLedger(authorization, ledgerId);
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can add members");
        }
        long userId = longValue(body.get("userId"), 0);
        require(store.users.get(userId), "User not found");
        store.member(ledgerId, userId, textOr(body.get("role"), "viewer"));
        return Map.of("success", true);
    }

    public void removeLedgerMember(String authorization, long ledgerId, long userId) {
        Ledger ledger = getLedger(authorization, ledgerId);
        long currentUserId = accessControl.requireUser(authorization).id;
        if (ledger.ownerId != currentUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner can remove members");
        }
        store.deleteLedgerMember(ledgerId, userId);
    }

    private Optional<Long> defaultLedgerId(long userId) {
        return store.ledgers.values().stream()
            .filter(ledger -> ledger.ownerId == userId && ledger.isDefault)
            .map(ledger -> ledger.id)
            .findFirst()
            .or(() -> store.ledgers.values().stream().filter(ledger -> ledger.ownerId == userId).map(ledger -> ledger.id).findFirst());
    }

    private boolean isLedgerMember(long ledgerId, long userId) {
        return store.ledgerMembers.values().stream().anyMatch(member -> member.ledgerId == ledgerId && member.userId == userId);
    }
}
