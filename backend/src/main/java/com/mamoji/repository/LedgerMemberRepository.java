package com.mamoji.repository;

import com.mamoji.entity.LedgerMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ledger-member relationship records.
 */
@Repository
public interface LedgerMemberRepository extends JpaRepository<LedgerMember, Long> {
    /**
     * Finds active/inactive members of one ledger.
     */
    List<LedgerMember> findByLedgerIdAndStatus(Long ledgerId, Integer status);

    /**
     * Finds one member record by ledger + user.
     */
    Optional<LedgerMember> findByLedgerIdAndUserId(Long ledgerId, Long userId);

    /**
     * Checks whether user is an active member of ledger.
     */
    boolean existsByLedgerIdAndUserIdAndStatus(Long ledgerId, Long userId, Integer status);

    /**
     * Finds ledger memberships of a user by status.
     */
    List<LedgerMember> findByUserIdAndStatus(Long userId, Integer status);
}
