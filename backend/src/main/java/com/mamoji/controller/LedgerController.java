package com.mamoji.controller;

import com.mamoji.domain.Models.Ledger;
import com.mamoji.domain.Models.LedgerMember;
import com.mamoji.service.MamojiService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledgers")
public class LedgerController {
    private final MamojiService service;

    public LedgerController(MamojiService service) {
        this.service = service;
    }

    @GetMapping
    public List<Ledger> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.listLedgers(authorization);
    }

    @GetMapping("/default")
    public Ledger defaultLedger(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.defaultLedger(authorization);
    }

    @GetMapping("/{id}")
    public Ledger get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        return service.getLedger(authorization, id);
    }

    @PostMapping
    public Ledger create(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody Map<String, Object> body
    ) {
        return service.createLedger(authorization, body);
    }

    @GetMapping("/{id}/members")
    public List<LedgerMember> members(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable long id) {
        return service.ledgerMembers(authorization, id);
    }

    @PostMapping("/{id}/members")
    public Map<String, Object> addMember(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @RequestBody Map<String, Object> body
    ) {
        return service.addLedgerMember(authorization, id, body);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public void removeMember(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @PathVariable long id,
        @PathVariable long userId
    ) {
        service.removeLedgerMember(authorization, id, userId);
    }
}
