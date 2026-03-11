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

/**
 * Repository for transaction persistence, pagination, and analytical aggregates.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Returns paged transactions sorted by date descending.
     */
    Page<Transaction> findByUserIdOrderByDateDesc(Long userId, Pageable pageable);

    /**
     * Counts all transactions of user.
     */
    long countByUserId(Long userId);

    /**
     * Counts transactions by user/type/date.
     */
    long countByUserIdAndTypeAndDate(Long userId, Integer type, LocalDate date);

    /**
     * Counts transactions by user/type/date range.
     */
    long countByUserIdAndTypeAndDateBetween(Long userId, Integer type, LocalDate startDate, LocalDate endDate);

    /**
     * Checks whether a transaction has refund child records.
     */
    boolean existsByOriginalTransactionId(Long originalTransactionId);

    /**
     * Sums transaction amount by user, type, and date range.
     */
    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = :type AND t.date BETWEEN :startDate AND :endDate")
    BigDecimal sumByUserIdAndTypeAndDateBetween(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Returns transactions in date range sorted by newest first.
     */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.date BETWEEN :startDate AND :endDate ORDER BY t.date DESC")
    List<Transaction> findByUserIdAndDateBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Returns transactions in date range with optional category/type filters.
     */
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

    /**
     * Aggregates amount by category for user/type/date range.
     */
    @Query("SELECT t.categoryId, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = :type AND t.date BETWEEN :startDate AND :endDate GROUP BY t.categoryId")
    List<Object[]> sumByCategoryAndType(
        @Param("userId") Long userId,
        @Param("type") Integer type,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Aggregates amount by category with category name projection.
     */
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

    /**
     * Sums amount by user/type/category/date range.
     */
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

    /**
     * Sums effective expense after subtracting refunded amount.
     */
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

    /**
     * Sums effective expense of one category after refunds.
     */
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

    /**
     * Counts likely duplicate transactions for risk checks.
     */
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

    /**
     * Returns paged transactions filtered by single type.
     */
    Page<Transaction> findByUserIdAndTypeOrderByDateDesc(Long userId, Integer type, Pageable pageable);

    /**
     * Returns paged transactions filtered by multiple types.
     */
    Page<Transaction> findByUserIdAndTypeInOrderByDateDesc(Long userId, List<Integer> types, Pageable pageable);

    /**
     * Returns paged transactions by date range.
     */
    Page<Transaction> findByUserIdAndDateBetweenOrderByDateDesc(Long userId, LocalDate startDate, LocalDate endDate, Pageable pageable);

    /**
     * Returns paged transactions by type and date range.
     */
    Page<Transaction> findByUserIdAndTypeAndDateBetweenOrderByDateDesc(Long userId, Integer type, LocalDate startDate, LocalDate endDate, Pageable pageable);

    /**
     * Returns paged transactions by type-set and date range.
     */
    Page<Transaction> findByUserIdAndTypeInAndDateBetweenOrderByDateDesc(
        Long userId,
        List<Integer> types,
        LocalDate startDate,
        LocalDate endDate,
        Pageable pageable
    );

    /**
     * Returns top transactions by amount in date range.
     */
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

    /**
     * Returns latest transactions in date range (date-desc then amount-desc).
     */
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

    /**
     * Closed projection for category aggregate result.
     */
    interface CategoryStatsProjection {
        /**
         * Category id.
         */
        Long getCategoryId();

        /**
         * Category display name.
         */
        String getCategoryName();

        /**
         * Aggregated amount.
         */
        BigDecimal getAmount();
    }
}
