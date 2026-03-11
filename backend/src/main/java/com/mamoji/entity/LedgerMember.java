package com.mamoji.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Ledger membership entity describing user role within a ledger.
 */
@Data
@Entity
@Table(name = "ledger_member")
public class LedgerMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String role; // owner, admin, editor, viewer

    @Column(nullable = false)
    private Integer status = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * Initializes creation timestamp.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
