package com.mamoji.approval.api;

import jakarta.validation.constraints.Size;

/** Optional operator note attached to an approval decision or withdrawal. */
public record ApprovalActionRequest(@Size(max = 500) String comment) {
    public static ApprovalActionRequest empty() {
        return new ApprovalActionRequest(null);
    }
}
