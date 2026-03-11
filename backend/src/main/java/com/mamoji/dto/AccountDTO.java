package com.mamoji.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Account transfer object for API/service boundaries.
 */
@Data
public class AccountDTO {
    private Long id;
    private String name;
    private String type;
    private String subType;
    private String bank;
    private BigDecimal balance;
    private Boolean includeInNetWorth;
    private Long userId;
    private Long ledgerId;
    private Integer status;
}
