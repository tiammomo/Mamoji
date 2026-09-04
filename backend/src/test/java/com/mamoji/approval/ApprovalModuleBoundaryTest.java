package com.mamoji.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.approval.application.ApprovalApplicationService;
import com.mamoji.approval.application.ApprovalEntityGateway;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ApprovalModuleBoundaryTest {
    @Test
    void approvalApplicationServiceCannotReturnToHorizontalServicePackage() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.mamoji.service.ApprovalService"));
        assertEquals(
            "com.mamoji.approval.application",
            ApprovalApplicationService.class.getPackageName()
        );

        Set<String> dependencyTypes = Arrays.stream(ApprovalApplicationService.class.getDeclaredFields())
            .map(field -> field.getType().getName())
            .collect(Collectors.toSet());
        assertTrue(dependencyTypes.contains(ApprovalEntityGateway.class.getName()));
        assertFalse(dependencyTypes.contains("com.mamoji.service.ReceiptService"));
        assertFalse(dependencyTypes.contains("com.mamoji.evidence.infrastructure.ReceiptVoucherRepository"));
    }
}
