package com.mamoji.controller;

import com.mamoji.common.api.ApiResponses;
import com.mamoji.dto.LedgerDTO;
import com.mamoji.dto.LedgerMemberDTO;
import com.mamoji.entity.User;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ledger and ledger-member management endpoints.
 */
@RestController
@RequestMapping("/api/v1/ledgers")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    /**
     * Lists ledgers visible to current user.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getLedgers(@AuthenticationUser User user) {
        return ApiResponses.ok(ledgerService.getLedgers(user.getId()));
    }

    /**
     * Returns one ledger detail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getLedger(@PathVariable Long id, @AuthenticationUser User user) {
        return ApiResponses.ok(ledgerService.getLedger(id, user.getId()));
    }

    /**
     * Creates a new ledger.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createLedger(@RequestBody LedgerDTO dto, @AuthenticationUser User user) {
        return ApiResponses.ok(ledgerService.createLedger(dto, user.getId()));
    }

    /**
     * Updates ledger metadata.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateLedger(
        @PathVariable Long id,
        @RequestBody LedgerDTO dto,
        @AuthenticationUser User user
    ) {
        return ApiResponses.ok(ledgerService.updateLedger(id, dto, user.getId()));
    }

    /**
     * Deletes one ledger.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteLedger(@PathVariable Long id, @AuthenticationUser User user) {
        ledgerService.deleteLedger(id, user.getId());
        return ApiResponses.ok(null);
    }

    /**
     * Lists members of one ledger.
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> getMembers(@PathVariable Long id, @AuthenticationUser User user) {
        return ApiResponses.ok(ledgerService.getMembers(id, user.getId()));
    }

    /**
     * Adds one member into ledger.
     */
    @PostMapping("/{id}/members")
    public ResponseEntity<Map<String, Object>> addMember(
        @PathVariable Long id,
        @RequestBody Map<String, Object> body,
        @AuthenticationUser User user
    ) {
        Long memberUserId = Long.valueOf(body.get("userId").toString());
        String role = body.get("role") != null ? body.get("role").toString() : "viewer";
        LedgerMemberDTO member = ledgerService.addMember(id, memberUserId, role, user.getId());
        return ApiResponses.ok(member);
    }

    /**
     * Removes one member from ledger.
     */
    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Map<String, Object>> removeMember(
        @PathVariable Long id,
        @PathVariable Long memberId,
        @AuthenticationUser User user
    ) {
        ledgerService.removeMember(id, memberId, user.getId());
        return ApiResponses.ok(null);
    }

    /**
     * Returns default ledger id for current user.
     */
    @GetMapping("/default")
    public ResponseEntity<Map<String, Object>> getDefaultLedger(@AuthenticationUser User user) {
        return ApiResponses.ok(Map.of("ledgerId", ledgerService.getDefaultLedgerId(user.getId())));
    }
}
