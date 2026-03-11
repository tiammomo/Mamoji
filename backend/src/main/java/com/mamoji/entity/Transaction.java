package com.mamoji.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction entity for income/expense and refund tracking.
 */
@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id")
    private Long familyId;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private Integer type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false)
    private LocalDate date;

    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // 退款相关字段
    @Column(name = "original_transaction_id")
    private Long originalTransactionId;

    @Column(name = "refunded_amount")
    private BigDecimal refundedAmount;

    @Column(name = "is_refundable")
    private Boolean isRefundable;

    @Column(name = "budget_id")
    private Long budgetId;

    /**
     * Initializes creation/update timestamps.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * Updates modification timestamp before persistence update.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
