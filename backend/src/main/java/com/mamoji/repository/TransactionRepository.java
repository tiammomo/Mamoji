package com.mamoji.repository;

import com.mamoji.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findByUserIdOrderByDateDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    long countByUserIdAndTypeAndDate(Long userId, Integer type, LocalDate date);

    long countByUserIdAndTypeAndDateBetween(Long userId, Integer type, LocalDate startDate, LocalDate endDate);

    boolean existsByOriginalTransactionId(Long originalTransactionId);

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = :type AND t.date BETWEEN :startDate AND :endDate")
    BigDecimal sumByUserIdAndTypeAndDateBetween(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByUserIdAndDateBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT t
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.date BETWEEN :startDate AND :endDate
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:type IS NULL OR t.type = :type)
        ORDER BY t.date DESC
        """)
    List<Transaction> findByUserIdAndDateBetweenWithFilters(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("categoryId") Long categoryId,
        @Param("type") Integer type
    );

    @Query("SELECT t.categoryId, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = :type AND t.date BETWEEN :startDate AND :endDate GROUP BY t.categoryId")
    List<Object[]> sumByCategoryAndType(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT
            t.categoryId AS categoryId,
            COALESCE(c.name, 'Unknown') AS categoryName,
            SUM(t.amount) AS amount
        FROM Transaction t
        LEFT JOIN Category c ON c.id = t.categoryId
        WHERE t.userId = :userId
          AND t.type = :type
          AND t.date BETWEEN :startDate AND :endDate
        GROUP BY t.categoryId, c.name
        """)
    List<CategoryStatsProjection> sumByCategoryAndTypeWithCategoryName(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT SUM(t.amount)
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = :type
          AND t.categoryId = :categoryId
          AND t.date BETWEEN :startDate AND :endDate
        """)
    BigDecimal sumByUserIdAndTypeAndCategoryIdAndDateBetween(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("categoryId") Long categoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT SUM(t.amount - COALESCE(t.refundedAmount, 0))
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = 2
          AND t.date BETWEEN :startDate AND :endDate
        """)
    BigDecimal sumEffectiveExpenseByUserIdAndDateBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT SUM(t.amount - COALESCE(t.refundedAmount, 0))
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = 2
          AND t.categoryId = :categoryId
          AND t.date BETWEEN :startDate AND :endDate
        """)
    BigDecimal sumEffectiveExpenseByUserIdAndCategoryIdAndDateBetween(
        @Param("userId") Long userId,
        @Param("categoryId") Long categoryId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    @Query("""
        SELECT COUNT(t)
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = :type
          AND t.categoryId = :categoryId
          AND t.amount = :amount
          AND t.date = :date
          AND (:excludeId IS NULL OR t.id <> :excludeId)
        """)
    long countDuplicateTransactions(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("categoryId") Long categoryId,
        @Param("amount") BigDecimal amount,
        @Param("date") LocalDate date,
        @Param("excludeId") Long excludeId
    );

    Page<Transaction> findByUserIdAndTypeOrderByDateDesc(Long userId, Integer type, Pageable pageable);
    
    Page<Transaction> findByUserIdAndTypeInOrderByDateDesc(Long userId, List<Integer> types, Pageable pageable);

    Page<Transaction> findByUserIdAndDateBetweenOrderByDateDesc(Long userId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    Page<Transaction> findByUserIdAndTypeAndDateBetweenOrderByDateDesc(Long userId, Integer type, LocalDate startDate, LocalDate endDate, Pageable pageable);

    Page<Transaction> findByUserIdAndTypeInAndDateBetweenOrderByDateDesc(
        Long userId,
        List<Integer> types,
        LocalDate startDate,
        LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT t
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = :type
          AND t.date BETWEEN :startDate AND :endDate
        ORDER BY t.amount DESC, t.date DESC
        """)
    List<Transaction> findTopByUserIdAndTypeAndDateBetweenOrderByAmountDesc(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    @Query("""
        SELECT t
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.type = :type
          AND t.date BETWEEN :startDate AND :endDate
        ORDER BY t.date DESC, t.amount DESC
        """)
    List<Transaction> findTopByUserIdAndTypeAndDateBetweenOrderByDateDesc(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        Pageable pageable
    );

    interface CategoryStatsProjection {
        Long getCategoryId();

        String getCategoryName();

        BigDecimal getAmount();
    }
}
