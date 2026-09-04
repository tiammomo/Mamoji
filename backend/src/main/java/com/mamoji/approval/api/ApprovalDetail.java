package com.mamoji.approval.api;

import com.mamoji.approval.domain.ApprovalAction;
import com.mamoji.approval.domain.ApprovalRequest;
import java.util.List;

/** Public approval response with its append-only action trail. */
public record ApprovalDetail(ApprovalRequest request, List<ApprovalAction> actions) {
}
