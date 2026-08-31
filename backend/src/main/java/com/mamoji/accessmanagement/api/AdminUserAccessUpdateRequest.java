package com.mamoji.accessmanagement.api;

import com.mamoji.common.Permissions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AdminUserAccessUpdateRequest(
    @Min(1) @Max(2) Integer role,
    @Min(0) @Max(Permissions.ALL) Integer permissions
) {
}
