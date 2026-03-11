package com.mamoji.service;

import com.mamoji.common.exception.ForbiddenOperationException;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.common.status.EntityStatus;
import com.mamoji.dto.LedgerDTO;
import com.mamoji.dto.LedgerMemberDTO;
import com.mamoji.entity.Ledger;
import com.mamoji.entity.LedgerMember;
import com.mamoji.repository.LedgerMemberRepository;
import com.mamoji.repository.LedgerRepository;
import com.mamoji.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ledger domain service.
 *
 * <p>Manages ledger CRUD, member management, access control, and DTO projection.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository ledgerMemberRepository;
    private final UserRepository userRepository;

    /**
     * Returns active ledgers owned by user.
     */
    public List<LedgerDTO> getLedgers(Long userId) {
        return ledgerRepository.findByOwnerIdAndStatus(userId, EntityStatus.ACTIVE)
            .stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * Returns one accessible ledger.
     */
    public LedgerDTO getLedger(Long id, Long userId) {
        Ledger ledger = findAccessibleLedger(id, userId);
        return toDto(ledger);
    }

    /**
     * Creates a ledger and automatically adds owner membership.
     */
    @Transactional
    public LedgerDTO createLedger(LedgerDTO dto, Long userId) {
        Ledger ledger = new Ledger();
        ledger.setName(dto.getName());
        ledger.setDescription(dto.getDescription());
        ledger.setCurrency(dto.getCurrency() != null ? dto.getCurrency() : "CNY");
        ledger.setOwnerId(userId);
        ledger.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false);
        ledger.setStatus(EntityStatus.ACTIVE);

        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            ledgerRepository.clearDefaultLedger(userId);
            ledger.setIsDefault(true);
        }

        Ledger savedLedger = ledgerRepository.save(ledger);

        LedgerMember ownerMember = new LedgerMember();
        ownerMember.setLedgerId(savedLedger.getId());
        ownerMember.setUserId(userId);
        ownerMember.setRole("owner");
        ownerMember.setStatus(EntityStatus.ACTIVE);
        ledgerMemberRepository.save(ownerMember);

        return toDto(savedLedger);
    }

    /**
     * Updates ledger metadata. Only owner can update.
     */
    @Transactional
    public LedgerDTO updateLedger(Long id, LedgerDTO dto, Long userId) {
        Ledger ledger = ledgerRepository.findByIdAndOwnerId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Ledger not found."));

        if (dto.getName() != null) {
            ledger.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            ledger.setDescription(dto.getDescription());
        }
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            ledgerRepository.clearDefaultLedger(userId);
            ledger.setIsDefault(true);
        }

        return toDto(ledgerRepository.save(ledger));
    }

    /**
     * Soft deletes ledger by setting status inactive. Only owner can delete.
     */
    @Transactional
    public void deleteLedger(Long id, Long userId) {
        Ledger ledger = ledgerRepository.findByIdAndOwnerId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Ledger not found."));
        ledger.setStatus(EntityStatus.INACTIVE);
        ledgerRepository.save(ledger);
    }

    /**
     * Lists active members of an accessible ledger.
     */
    public List<LedgerMemberDTO> getMembers(Long ledgerId, Long userId) {
        findAccessibleLedger(ledgerId, userId);
        return ledgerMemberRepository.findByLedgerIdAndStatus(ledgerId, EntityStatus.ACTIVE)
            .stream()
            .map(this::toMemberDto)
            .toList();
    }

    /**
     * Adds a member to ledger when requester is owner or admin member.
     */
    @Transactional
    public LedgerMemberDTO addMember(Long ledgerId, Long memberUserId, String role, Long userId) {
        Ledger ledger = findRequiredLedger(ledgerId);
        LedgerMember requesterMember = ledgerMemberRepository.findByLedgerIdAndUserId(ledgerId, userId)
            .orElseThrow(() -> new ForbiddenOperationException("You are not a member of this ledger."));

        if (!ledger.getOwnerId().equals(userId) && !"admin".equals(requesterMember.getRole())) {
            throw new ForbiddenOperationException("You do not have permission to add ledger members.");
        }

        if (ledgerMemberRepository.existsByLedgerIdAndUserIdAndStatus(ledgerId, memberUserId, EntityStatus.ACTIVE)) {
            throw new ForbiddenOperationException("The user is already a ledger member.");
        }

        LedgerMember member = new LedgerMember();
        member.setLedgerId(ledgerId);
        member.setUserId(memberUserId);
        member.setRole(role != null ? role : "viewer");
        member.setStatus(EntityStatus.ACTIVE);

        return toMemberDto(ledgerMemberRepository.save(member));
    }

    /**
     * Removes a member from ledger.
     *
     * <p>Only owner can remove, and owner role itself cannot be removed.
     */
    @Transactional
    public void removeMember(Long ledgerId, Long memberId, Long userId) {
        Ledger ledger = findRequiredLedger(ledgerId);
        LedgerMember member = ledgerMemberRepository.findById(memberId)
            .orElseThrow(() -> new ResourceNotFoundException("Ledger member not found."));

        if (!ledger.getOwnerId().equals(userId)) {
            throw new ForbiddenOperationException("Only the ledger owner can remove members.");
        }
        if ("owner".equals(member.getRole())) {
            throw new ForbiddenOperationException("The ledger owner cannot be removed.");
        }

        member.setStatus(EntityStatus.INACTIVE);
        ledgerMemberRepository.save(member);
    }

    /**
     * Returns user's default ledger id when configured.
     */
    public Long getDefaultLedgerId(Long userId) {
        return ledgerRepository.findDefaultLedger(userId)
            .map(Ledger::getId)
            .orElse(null);
    }

    /**
     * Loads ledger by id or throws not-found.
     */
    private Ledger findRequiredLedger(Long id) {
        return ledgerRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Ledger not found."));
    }

    /**
     * Validates requester can access ledger as owner or active member.
     */
    private Ledger findAccessibleLedger(Long id, Long userId) {
        Ledger ledger = findRequiredLedger(id);
        boolean isMember = ledgerMemberRepository.existsByLedgerIdAndUserIdAndStatus(id, userId, EntityStatus.ACTIVE);
        if (!ledger.getOwnerId().equals(userId) && !isMember) {
            throw new ForbiddenOperationException("You do not have permission to access this ledger.");
        }
        return ledger;
    }

    /**
     * Maps ledger entity to DTO and expands active member list.
     */
    private LedgerDTO toDto(Ledger ledger) {
        LedgerDTO dto = new LedgerDTO();
        dto.setId(ledger.getId());
        dto.setName(ledger.getName());
        dto.setDescription(ledger.getDescription());
        dto.setCurrency(ledger.getCurrency());
        dto.setOwnerId(ledger.getOwnerId());
        dto.setIsDefault(ledger.getIsDefault());
        dto.setStatus(ledger.getStatus());
        dto.setMembers(
            ledgerMemberRepository.findByLedgerIdAndStatus(ledger.getId(), EntityStatus.ACTIVE)
                .stream()
                .map(this::toMemberDto)
                .toList()
        );
        return dto;
    }

    /**
     * Maps ledger member entity to DTO and enriches basic user profile fields.
     */
    private LedgerMemberDTO toMemberDto(LedgerMember member) {
        LedgerMemberDTO dto = new LedgerMemberDTO();
        dto.setId(member.getId());
        dto.setLedgerId(member.getLedgerId());
        dto.setUserId(member.getUserId());
        dto.setRole(member.getRole());
        dto.setStatus(member.getStatus());
        userRepository.findById(member.getUserId()).ifPresent(user -> {
            dto.setNickname(user.getNickname());
            dto.setEmail(user.getEmail());
        });
        return dto;
    }
}
