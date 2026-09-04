package com.mamoji.people.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record DepartmentCreateRequest(
    @Positive Long companyId,
    @NotBlank @Size(max = 120) String name,
    @Size(max = 64) @Pattern(regexp = "(?s).*\\S.*") String costCenter,
    @DecimalMin("0") @Digits(integer = 18, fraction = 2) BigDecimal budget,
    @Positive Long managerEmployeeId,
    @Min(0) @Max(1) Integer status
) {
}
