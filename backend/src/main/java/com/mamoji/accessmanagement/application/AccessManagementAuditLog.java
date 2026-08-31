package com.mamoji.accessmanagement.application;

/** Audit port for administrator-driven user access changes. */
public interface AccessManagementAuditLog {
    void record(long targetUserId, String action, String description, AdministratorActor actor);
}
