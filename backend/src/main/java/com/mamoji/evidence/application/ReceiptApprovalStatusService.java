package com.mamoji.evidence.application;

/** Narrow Evidence write contract exposed to the approval workflow. */
public interface ReceiptApprovalStatusService {
    void updateApprovalStatus(String authorization, long id, String approvalStatus);
}
