package com.mamoji.finance.api;

import com.mamoji.finance.application.LedgerApplicationService;
import com.mamoji.finance.domain.Ledger;
import com.mamoji.finance.domain.LedgerMember;
import com.mamoji.platform.identity.ActorContext;
import com.mamoji.platform.identity.CurrentActor;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledgers")
public class LedgerController {
    private final LedgerApplicationService service;

    public LedgerController(LedgerApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Ledger> list(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.listLedgers(actor, companyId);
    }

    @GetMapping("/default")
    public Ledger defaultLedger(
        @CurrentActor ActorContext actor,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.defaultLedger(actor, companyId);
    }

    @GetMapping("/{id}")
    public Ledger get(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @RequestParam(value = "companyId", required = false) Long companyId
    ) {
        return service.getLedger(actor, id, companyId);
    }

    @PostMapping
    public Ledger create(
        @CurrentActor ActorContext actor,
        @Valid @RequestBody LedgerCreateRequest request
    ) {
        return service.createLedger(actor, request);
    }

    @GetMapping("/{id}/members")
    public List<LedgerMember> members(@CurrentActor ActorContext actor, @PathVariable long id) {
        return service.ledgerMembers(actor, id);
    }

    @PostMapping("/{id}/members")
    public Map<String, Object> addMember(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @Valid @RequestBody LedgerMemberCreateRequest request
    ) {
        return service.addLedgerMember(actor, id, request);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public void removeMember(
        @CurrentActor ActorContext actor,
        @PathVariable long id,
        @PathVariable long userId
    ) {
        service.removeLedgerMember(actor, id, userId);
    }
}
