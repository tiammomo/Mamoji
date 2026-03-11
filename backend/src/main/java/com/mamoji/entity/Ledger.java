package com.mamoji.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Ledger entity representing a bookkeeping scope.
 */
@Data
@Entity
@Table(name = "ledger")
public class Ledger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String currency = "CNY";

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

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
