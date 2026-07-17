package com.mamoji.platform.tenant;

public record CompanyMembership(
    long companyId,
    long userId,
    Long departmentId,
    String role,
    String scope,
    String status
) {
    public boolean active() {
        return "active".equals(status);
    }
}
