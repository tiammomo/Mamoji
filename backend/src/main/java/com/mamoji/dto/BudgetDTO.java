package com.mamoji.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Budget transfer object including computed risk/usage fields.
 */
@Data
public class BudgetDTO {
    private Long id;
    private String name;
    private BigDecimal amount;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer warningThreshold;
    private Integer status;
    private BigDecimal spent;
    private Long userId;
    private Long ledgerId;
    private Long categoryId;
    private BigDecimal usageRate;
    private BigDecimal remainingAmount;
    private Boolean warningReached;
    private String riskLevel;
    private String riskMessage;
}
