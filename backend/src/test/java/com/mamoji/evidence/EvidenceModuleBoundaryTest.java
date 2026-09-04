package com.mamoji.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.evidence.api.ReceiptController;
import com.mamoji.evidence.api.ReceiptCreateRequest;
import com.mamoji.evidence.api.ReceiptUpdateRequest;
import com.mamoji.evidence.api.ReceiptUploadRequest;
import com.mamoji.evidence.application.ReceiptApplicationService;
import com.mamoji.evidence.application.ReceiptApprovalStatusService;
import com.mamoji.evidence.application.ReceiptBatchUploadResult;
import com.mamoji.evidence.application.ReceiptCreateCommand;
import com.mamoji.evidence.application.ReceiptFileHashRepository;
import com.mamoji.evidence.application.ReceiptUpdateCommand;
import com.mamoji.evidence.application.ReceiptUploadCommand;
import com.mamoji.evidence.application.ReceiptVoucherRepository;
import com.mamoji.evidence.domain.ReceiptVoucher;
import com.mamoji.evidence.infrastructure.JdbcReceiptFileHashRepository;
import com.mamoji.evidence.infrastructure.JdbcReceiptVoucherRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class EvidenceModuleBoundaryTest {
    @Test
    void receiptUseCaseLivesBehindEvidenceApplicationPorts() {
        assertEquals("com.mamoji.evidence.api", ReceiptController.class.getPackageName());
        assertEquals("com.mamoji.evidence.application", ReceiptApplicationService.class.getPackageName());
        assertTrue(ReceiptApprovalStatusService.class.isAssignableFrom(ReceiptApplicationService.class));

        Set<String> dependencyTypes = Arrays.stream(ReceiptApplicationService.class.getDeclaredFields())
            .map(field -> field.getType().getName())
            .collect(Collectors.toSet());
        assertTrue(dependencyTypes.contains(ReceiptVoucherRepository.class.getName()));
        assertTrue(dependencyTypes.contains(ReceiptFileHashRepository.class.getName()));
        assertFalse(dependencyTypes.contains("org.springframework.jdbc.core.JdbcTemplate"));
        assertFalse(dependencyTypes.contains(JdbcReceiptVoucherRepository.class.getName()));
        assertFalse(dependencyTypes.contains(JdbcReceiptFileHashRepository.class.getName()));
    }

    @Test
    void oldHorizontalReceiptEntrypointsCannotReturn() {
        assertFalse(Files.exists(Path.of("src/main/java/com/mamoji/service/ReceiptService.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/mamoji/controller/ReceiptController.java")));
        assertFalse(Files.exists(Path.of(
            "src/main/java/com/mamoji/evidence/infrastructure/ReceiptVoucherRepository.java"
        )));
        assertEquals("com.mamoji.evidence.application", ReceiptVoucherRepository.class.getPackageName());
        assertEquals("com.mamoji.evidence.infrastructure", JdbcReceiptVoucherRepository.class.getPackageName());
    }

    @Test
    void receiptDomainAndWriteContractsCannotReturnToSharedModelsOrMaps() throws Exception {
        assertEquals("com.mamoji.evidence.domain", ReceiptVoucher.class.getPackageName());
        assertEquals(
            ReceiptVoucher.class,
            ReceiptApplicationService.class
                .getDeclaredMethod("create", String.class, ReceiptCreateCommand.class)
                .getReturnType()
        );
        assertEquals(
            ReceiptVoucher.class,
            ReceiptApplicationService.class
                .getDeclaredMethod("update", String.class, long.class, ReceiptUpdateCommand.class)
                .getReturnType()
        );
        assertFalse(Arrays.stream(ReceiptApplicationService.class.getDeclaredMethods())
            .filter(method -> Set.of("create", "update", "upload", "batchUpload").contains(method.getName()))
            .flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .anyMatch(Map.class::equals));
        assertFalse(Arrays.stream(com.mamoji.domain.Models.class.getDeclaredClasses())
            .anyMatch(type -> type.getSimpleName().equals("ReceiptVoucher")));
        assertEquals(ReceiptCreateCommand.class, ReceiptCreateRequest.class.getDeclaredMethod("toCommand").getReturnType());
        assertEquals(ReceiptUpdateCommand.class, ReceiptUpdateRequest.class.getDeclaredMethod("toCommand").getReturnType());
        assertEquals(ReceiptUploadCommand.class, ReceiptUploadRequest.class.getDeclaredMethod("toCommand").getReturnType());
        assertEquals(
            ReceiptVoucher.class,
            ReceiptApplicationService.class
                .getDeclaredMethod("upload", String.class, MultipartFile.class, ReceiptUploadCommand.class)
                .getReturnType()
        );
        assertEquals(
            ReceiptBatchUploadResult.class,
            ReceiptApplicationService.class
                .getDeclaredMethod("batchUpload", String.class, List.class, ReceiptUploadCommand.class)
                .getReturnType()
        );
    }
}
