package com.mamoji.repository;

import com.mamoji.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {
    List<Budget> findByUserIdAndStatus(Long userId, Integer status);
    long countByUserIdAndStatus(Long userId, Integer status);

    List<Budget> findByLedgerIdAndStatus(Long ledgerId, Integer status);

    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.startDate <= :date AND b.endDate >= :date AND b.status = 1")
    List<Budget> findActiveBudgets(Long userId, LocalDate date);

    Optional<Budget> findByIdAndUserId(Long id, Long userId);

    @Query("""
        SELECT b
        FROM Budget b
        WHERE b.userId = :userId
          AND b.startDate <= :date
          AND b.endDate >= :date
          AND b.status = 1
          AND b.categoryId = :categoryId
        """)
    Optional<Budget> findActiveBudgetByCategory(Long userId, Long categoryId, LocalDate date);

    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.startDate <= :date AND b.endDate >= :date AND b.status = 1 AND b.categoryId IS NULL")
    Optional<Budget> findActiveBudgetWithoutCategory(Long userId, LocalDate date);

    @Query("""
        SELECT COUNT(b)
        FROM Budget b
        WHERE b.userId = :userId
          AND b.status = 1
          AND (:excludeId IS NULL OR b.id <> :excludeId)
          AND (
            (:categoryId IS NULL AND b.categoryId IS NULL)
            OR b.categoryId = :categoryId
          )
          AND b.startDate <= :endDate
          AND b.endDate >= :startDate
        """)
    long countOverlappingActiveBudgets(
        @Param("userId") Long userId,
        @Param("categoryId") Long categoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("excludeId") Long excludeId
    );
}
