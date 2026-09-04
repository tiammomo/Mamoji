package com.mamoji.approval.application;

import com.mamoji.platform.identity.User;

/** Approval boundary for validating and synchronizing polymorphic business entities. */
public interface ApprovalEntityGateway {
    void validateReference(User applicant, long companyId, String entityType, Long entityId);

    void synchronizeStatus(String authorization, String entityType, Long entityId, String status);
}
