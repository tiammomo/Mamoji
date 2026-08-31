package com.mamoji.operations.api;

import com.mamoji.common.PageRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** Typed query-string contract shared by transaction list and summary endpoints. */
public record TransactionQueryRequest(
    @Positive Long companyId,
    @Min(1) @Max(3) Integer type,
    @Positive Long categoryId,
    @Positive Long accountId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
    @Size(max = 200) String keyword,
    @DecimalMin("0.00") BigDecimal minAmount,
    @DecimalMin("0.00") BigDecimal maxAmount,
    Integer page,
    Integer size
) {
    public int resolvedPage() {
        return page == null ? PageRequest.DEFAULT_PAGE : page;
    }

    public int resolvedSize() {
        return size == null ? PageRequest.DEFAULT_SIZE : size;
    }
}
