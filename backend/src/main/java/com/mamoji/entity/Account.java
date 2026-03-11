package com.mamoji.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Account entity representing cash/bank/digital/investment/debt balances.
 */
@Data
@Entity
@Table(name = "account")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type; // bank, credit, digital, cash, investment, debt

    private String subType;

    private String bank;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "include_in_net_worth", nullable = false)
    private Boolean includeInNetWorth = true;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ledger_id")
    private Long ledgerId;

    @Column(nullable = false)
    private Integer status = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
