package com.mamoji.repository;

import com.mamoji.entity.Ledger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ledger persistence and default-ledger utilities.
 */
@Repository
public interface LedgerRepository extends JpaRepository<Ledger, Long> {
    /**
     * Finds ledgers by owner and status.
     */
    List<Ledger> findByOwnerIdAndStatus(Long ownerId, Integer status);

    /**
     * Counts ledgers by owner and status.
     */
    long countByOwnerIdAndStatus(Long ownerId, Integer status);

    /**
     * Finds ledgers where user is an active member.
     */
    @Query("SELECT l FROM Ledger l WHERE l.id IN (SELECT lm.ledgerId FROM LedgerMember lm WHERE lm.userId = :userId AND lm.status = 1) AND l.status = 1")
    List<Ledger> findByMemberId(Long userId);

    /**
     * Finds owner-specific ledger by id.
     */
    Optional<Ledger> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * Finds default active ledger for owner.
     */
    @Query("SELECT l FROM Ledger l WHERE l.ownerId = :userId AND l.isDefault = true AND l.status = 1")
    Optional<Ledger> findDefaultLedger(Long userId);

    /**
     * Clears owner's default marker before setting a new default ledger.
     */
    @Modifying
    @Query("UPDATE Ledger l SET l.isDefault = false WHERE l.ownerId = :userId")
    void clearDefaultLedger(Long userId);
}
