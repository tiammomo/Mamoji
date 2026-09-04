package com.mamoji.people.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Partial update payload; an explicit null manager removes the assignment. */
public class DepartmentUpdateRequest {
    @Positive
    public Long companyId;

    @Size(max = 120)
    @Pattern(regexp = "(?s).*\\S.*")
    public String name;

    @Size(max = 64)
    @Pattern(regexp = "(?s).*\\S.*")
    public String costCenter;

    @DecimalMin("0")
    @Digits(integer = 18, fraction = 2)
    public BigDecimal budget;

    @Min(0)
    @Max(1)
    public Integer status;

    @Positive
    private Long managerEmployeeId;
    private boolean managerEmployeeIdPresent;

    @JsonSetter("managerEmployeeId")
    public void setManagerEmployeeId(Long managerEmployeeId) {
        this.managerEmployeeIdPresent = true;
        this.managerEmployeeId = managerEmployeeId;
    }

    public Long managerEmployeeId() {
        return managerEmployeeId;
    }

    @JsonIgnore
    public boolean hasManagerEmployeeId() {
        return managerEmployeeIdPresent;
    }
}
