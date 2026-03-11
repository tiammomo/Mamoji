package com.mamoji.dto;

import lombok.Data;
import java.util.List;

/**
 * Ledger transfer object with member snapshot.
 */
@Data
public class LedgerDTO {
    private Long id;
    private String name;
    private String description;
    private String currency;
    private Long ownerId;
    private Boolean isDefault;
    private Integer status;
    private List<LedgerMemberDTO> members;
}
