package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.evidence.application.ReceiptApplicationService;
import com.mamoji.evidence.application.ReceiptUploadCommand;
import com.mamoji.notification.infrastructure.NotificationDeliveryStatusRepository;
import com.mamoji.notification.infrastructure.OutboxEventStatusRepository;
import com.mamoji.platform.scheduling.infrastructure.ScheduledJobLeaseRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EnterpriseWorkflowIntegrationTest extends AbstractPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    OutboxEventStatusRepository outboxStatusRepository;

    @Autowired
    NotificationDeliveryStatusRepository notificationDeliveryStatusRepository;

    @Autowired
    ScheduledJobLeaseRepository scheduledJobLeases;

    @Autowired
    ReceiptApplicationService receipts;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentReceiptUploadCreatesOneVoucherAndReturnsOneConflict() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Receipt duplicate lock " + System.nanoTime());
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00
        };
        ReceiptUploadCommand command = new ReceiptUploadCommand(
            companyId,
            null,
            null,
            null,
            "purchase_invoice",
            null,
            null,
            new BigDecimal("99.00"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        java.util.function.Supplier<HttpStatus> upload = () -> {
            try {
                receipts.upload(
                    "Bearer " + token,
                    new MockMultipartFile("file", "same.png", "image/png", png),
                    command
                );
                return HttpStatus.OK;
            } catch (org.springframework.web.server.ResponseStatusException ex) {
                return HttpStatus.valueOf(ex.getStatusCode().value());
            }
        };
        CompletableFuture<HttpStatus> first = CompletableFuture.supplyAsync(upload);
        CompletableFuture<HttpStatus> second = CompletableFuture.supplyAsync(upload);
        List<HttpStatus> statuses = List.of(
            first.get(10, TimeUnit.SECONDS),
            second.get(10, TimeUnit.SECONDS)
        );

        assertEquals(1, statuses.stream().filter(HttpStatus.OK::equals).count());
        assertEquals(1, statuses.stream().filter(HttpStatus.CONFLICT::equals).count());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_file_hashes WHERE company_id = ?", Integer.class, companyId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_vouchers WHERE company_id = ?", Integer.class, companyId
        ));
    }

    @Test
    void receiptCannotLinkTransactionFromAnotherCompany() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyA = createCompany(token, "Receipt Scope A " + System.nanoTime());
        long companyB = createCompany(token, "Receipt Scope B " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyA, "Receipt A account", "1000");
        Map<String, Object> category = createCategory(token, companyA, "Receipt A expense", "expense");
        Map<String, Object> created = createTransaction(token, companyA, account, category, "20");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");

        ApiResponse response = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyB,
            "transactionId", transaction.get("id"),
            "title", "Cross-company voucher must fail",
            "amount", 20,
            "issueDate", "2026-07-14"
        ), token);
        assertEquals(403, response.status(), response.body());
    }

    @Test
    void receiptJsonContractsValidateWritesAndPreserveExplicitNullUpdates() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Receipt contract " + System.nanoTime());
        int before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_vouchers WHERE company_id = ?", Integer.class, companyId
        );

        ApiResponse invalid = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "",
            "voucherType", "unsupported",
            "direction", "sideways",
            "amount", -1,
            "taxRate", 101,
            "taxPeriod", "2026-13",
            "approvalStatus", "approved",
            "fileSize", -1
        ), token);
        assertValidationFields(invalid, Set.of(
            "title", "voucherType", "direction", "amount", "taxRate", "taxPeriod", "approvalStatus", "fileSize"
        ));
        assertEquals(before, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_vouchers WHERE company_id = ?", Integer.class, companyId
        ));

        ApiResponse created = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Typed receipt contract",
            "voucherType", "purchase_invoice",
            "direction", "expense",
            "counterparty", "Supplier",
            "amount", 120,
            "taxPeriod", "2026-08",
            "businessPurpose", "Project materials",
            "dueDate", "2026-09-15",
            "note", "Keep until cleared"
        ), token);
        assertEquals(200, created.status(), created.body());
        long voucherId = ((Number) parseMap(created.body()).get("id")).longValue();

        Map<String, Object> clearFields = new LinkedHashMap<>();
        clearFields.put("taxPeriod", null);
        clearFields.put("dueDate", null);
        clearFields.put("note", null);
        ApiResponse updated = request("PUT", "/api/v1/receipts/" + voucherId, clearFields, token);
        assertEquals(200, updated.status(), updated.body());
        Map<String, Object> voucher = parseMap(updated.body());
        assertNull(voucher.get("taxPeriod"));
        assertNull(voucher.get("dueDate"));
        assertNull(voucher.get("note"));
        assertEquals("Project materials", voucher.get("businessPurpose"));
    }

    @Test
    void receiptQueryContractsValidateFiltersAndUseStableDatabasePagingAndSummary() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Receipt query contract " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Receipt query account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Receipt query expense", "expense");
        Map<String, Object> transactionResult = createTransaction(token, companyId, account, category, "20");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) transactionResult.get("transaction");

        ApiResponse purchase = request("POST", "/api/v1/receipts", Map.ofEntries(
            Map.entry("companyId", companyId),
            Map.entry("transactionId", transaction.get("id")),
            Map.entry("title", "Literal 100% supplies"),
            Map.entry("voucherType", "purchase_invoice"),
            Map.entry("direction", "expense"),
            Map.entry("counterparty", "Supplier"),
            Map.entry("amount", 120),
            Map.entry("taxAmount", 20),
            Map.entry("taxPeriod", "2026-08"),
            Map.entry("invoiceCheckStatus", "verified"),
            Map.entry("deductionStatus", "deductible"),
            Map.entry("issueDate", "2026-08-10"),
            Map.entry("status", "linked")
        ), token);
        assertEquals(200, purchase.status(), purchase.body());
        long purchaseId = ((Number) parseMap(purchase.body()).get("id")).longValue();

        ApiResponse sales = request("POST", "/api/v1/receipts", Map.ofEntries(
            Map.entry("companyId", companyId),
            Map.entry("title", "Literal 100x sale"),
            Map.entry("voucherType", "sales_invoice"),
            Map.entry("direction", "income"),
            Map.entry("counterparty", "Customer"),
            Map.entry("amount", 300),
            Map.entry("taxAmount", 30),
            Map.entry("taxPeriod", "2026-09"),
            Map.entry("invoiceCheckStatus", "failed"),
            Map.entry("issueDate", "2026-09-12"),
            Map.entry("status", "verified")
        ), token);
        assertEquals(200, sales.status(), sales.body());

        ApiResponse reimbursement = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Travel reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 50,
            "reimbursementStatus", "submitted",
            "issueDate", "2026-07-01",
            "status", "pending_review"
        ), token);
        assertEquals(200, reimbursement.status(), reimbursement.body());

        ApiResponse filtered = request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId
                + "&keyword=100%25&voucherType=purchase_invoice&direction=expense&status=linked"
                + "&invoiceCheckStatus=verified&deductionStatus=deductible&taxPeriod=2026-08"
                + "&linkState=linked&startDate=2026-08-01&endDate=2026-08-31"
                + "&minAmount=100&maxAmount=150&page=0&size=1",
            null,
            token
        );
        assertEquals(200, filtered.status(), filtered.body());
        Map<String, Object> filteredPage = parseMap(filtered.body());
        assertEquals(1, filteredPage.get("totalElements"));
        assertEquals(1, filteredPage.get("totalPages"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filteredContent = (List<Map<String, Object>>) filteredPage.get("content");
        assertEquals(purchaseId, ((Number) filteredContent.getFirst().get("id")).longValue());

        ApiResponse literalKeyword = request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId + "&keyword=100%25&size=10",
            null,
            token
        );
        Map<String, Object> literalPage = parseMap(literalKeyword.body());
        assertEquals(1, literalPage.get("totalElements"), literalKeyword.body());

        ApiResponse secondPage = request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId + "&page=1&size=1",
            null,
            token
        );
        assertEquals(200, secondPage.status(), secondPage.body());
        Map<String, Object> page = parseMap(secondPage.body());
        assertEquals(3, page.get("totalElements"));
        assertEquals(3, page.get("totalPages"));
        assertEquals(1, page.get("number"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertEquals(purchaseId, ((Number) content.getFirst().get("id")).longValue());

        ApiResponse invalid = request(
            "GET",
            "/api/v1/receipts?companyId=-1&voucherType=unsupported&direction=sideways&status=unknown"
                + "&invoiceCheckStatus=unknown&deductionStatus=unknown&reimbursementStatus=unknown"
                + "&taxPeriod=2026-13&linkState=unknown&minAmount=-1&page=-1&size=201",
            null,
            token
        );
        assertValidationFields(invalid, Set.of(
            "companyId",
            "voucherType",
            "direction",
            "status",
            "invoiceCheckStatus",
            "deductionStatus",
            "reimbursementStatus",
            "taxPeriod",
            "linkState",
            "minAmount",
            "page",
            "size"
        ));

        ApiResponse invalidDate = request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId + "&startDate=2026-02-30",
            null,
            token
        );
        assertValidationFields(invalidDate, Set.of("startDate"));
        assertEquals(400, request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId + "&startDate=2026-09-01&endDate=2026-08-01",
            null,
            token
        ).status());
        assertEquals(400, request(
            "GET",
            "/api/v1/receipts?companyId=" + companyId + "&minAmount=200&maxAmount=100",
            null,
            token
        ).status());

        ApiResponse summaryResponse = request(
            "GET",
            "/api/v1/receipts/summary?companyId=" + companyId,
            null,
            token
        );
        assertEquals(200, summaryResponse.status(), summaryResponse.body());
        Map<String, Object> summary = parseMap(summaryResponse.body());
        assertEquals(3, summary.get("totalCount"));
        assertEquals(0, new BigDecimal("470").compareTo(decimal(summary.get("totalAmount"))));
        assertEquals(0, new BigDecimal("300").compareTo(decimal(summary.get("salesInvoiceAmount"))));
        assertEquals(0, new BigDecimal("120").compareTo(decimal(summary.get("purchaseInvoiceAmount"))));
        assertEquals(0, new BigDecimal("30").compareTo(decimal(summary.get("outputTaxAmount"))));
        assertEquals(0, new BigDecimal("20").compareTo(decimal(summary.get("deductibleTaxAmount"))));
        assertEquals(0, new BigDecimal("50").compareTo(decimal(summary.get("reimbursementAmount"))));
        assertEquals(0, new BigDecimal("50").compareTo(decimal(summary.get("reimbursementPendingAmount"))));
        assertEquals(0, new BigDecimal("50").compareTo(decimal(summary.get("pendingAmount"))));
        assertEquals(1, summary.get("pendingReviewCount"));
        assertEquals(3, summary.get("missingAttachmentCount"));
        assertEquals(2, summary.get("missingTransactionCount"));
        assertEquals(1, summary.get("highRiskCount"));
        assertEquals(1, summary.get("uncheckedInvoiceCount"));
        assertEquals(1, summary.get("pendingDeductionCount"));
        assertEquals(1, summary.get("pendingReimbursementCount"));
        assertEquals(0, summary.get("missingTaxPeriodCount"));
        assertEquals(0, summary.get("pendingApprovalCount"));
        assertEquals(2, summary.get("pendingAccountingCount"));
        assertEquals(1, summary.get("postedAccountingCount"));

        long emptyCompanyId = createCompany(token, "Empty receipt summary " + System.nanoTime());
        ApiResponse emptySummaryResponse = request(
            "GET",
            "/api/v1/receipts/summary?companyId=" + emptyCompanyId,
            null,
            token
        );
        assertEquals(200, emptySummaryResponse.status(), emptySummaryResponse.body());
        Map<String, Object> emptySummary = parseMap(emptySummaryResponse.body());
        assertEquals(0, emptySummary.get("totalCount"));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(emptySummary.get("totalAmount"))));

        ApiResponse invalidSummary = request(
            "GET",
            "/api/v1/receipts/summary?companyId=-1",
            null,
            token
        );
        assertValidationFields(invalidSummary, Set.of("companyId"));
    }

    @Test
    void receiptMultipartContractsValidateSingleAndBatchUploadMetadata() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Receipt multipart contract " + System.nanoTime());
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01
        };
        int before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_vouchers WHERE company_id = ?", Integer.class, companyId
        );

        ApiResponse invalid = multipartRequest(
            "/api/v1/receipts/upload",
            Map.of(
                "companyId", Long.toString(companyId),
                "voucherType", "unsupported",
                "direction", "sideways",
                "amount", "-1",
                "taxRate", "101",
                "taxPeriod", "2026-13"
            ),
            List.of(new MultipartPart("file", "invalid.png", "image/png", png)),
            token
        );
        assertValidationFields(invalid, Set.of("voucherType", "direction", "amount", "taxRate", "taxPeriod"));

        ApiResponse invalidDate = multipartRequest(
            "/api/v1/receipts/upload",
            Map.of("companyId", Long.toString(companyId), "issueDate", "2026-02-30"),
            List.of(new MultipartPart("file", "invalid-date.png", "image/png", png)),
            token
        );
        assertValidationFields(invalidDate, Set.of("issueDate"));
        assertEquals(before, jdbc.queryForObject(
            "SELECT COUNT(*) FROM receipt_vouchers WHERE company_id = ?", Integer.class, companyId
        ));

        ApiResponse batchInvalid = multipartRequest(
            "/api/v1/receipts/batch-upload",
            Map.of("companyId", Long.toString(companyId), "invoiceCheckStatus", "unknown"),
            List.of(new MultipartPart("files", "batch-invalid.png", "image/png", png)),
            token
        );
        assertValidationFields(batchInvalid, Set.of("invoiceCheckStatus"));

        ApiResponse uploaded = multipartRequest(
            "/api/v1/receipts/upload",
            Map.ofEntries(
                Map.entry("companyId", Long.toString(companyId)),
                Map.entry("title", "Typed multipart receipt"),
                Map.entry("voucherType", "purchase_invoice"),
                Map.entry("direction", "expense"),
                Map.entry("counterparty", "Supplier"),
                Map.entry("amount", "128.50"),
                Map.entry("taxAmount", "8.50"),
                Map.entry("taxRate", "7.0833"),
                Map.entry("taxPeriod", "2026-09"),
                Map.entry("issueDate", "2026-09-05"),
                Map.entry("dueDate", "2026-09-30"),
                Map.entry("note", "Multipart metadata")
            ),
            List.of(new MultipartPart("file", "typed.png", "image/png", new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x02
            })),
            token
        );
        assertEquals(200, uploaded.status(), uploaded.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> voucher = (Map<String, Object>) parseMap(uploaded.body()).get("voucher");
        assertEquals("Typed multipart receipt", voucher.get("title"));
        assertEquals("2026-09", voucher.get("taxPeriod"));
        assertEquals("2026-09-30", voucher.get("dueDate"));
        assertEquals(0, new BigDecimal("128.50").compareTo(decimal(voucher.get("amount"))));

        ApiResponse batchUploaded = multipartRequest(
            "/api/v1/receipts/batch-upload",
            Map.of(
                "companyId", Long.toString(companyId),
                "voucherType", "purchase_invoice",
                "amount", "32.00",
                "issueDate", "2026-09-05"
            ),
            List.of(
                new MultipartPart("files", "batch-duplicate.png", "image/png", new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x02
                }),
                new MultipartPart("files", "batch-new.png", "image/png", new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x03
                })
            ),
            token
        );
        assertEquals(200, batchUploaded.status(), batchUploaded.body());
        Map<String, Object> batch = parseMap(batchUploaded.body());
        assertEquals(1, batch.get("successCount"));
        assertEquals(1, batch.get("failureCount"));
        assertTrue(batch.get("vouchers") instanceof List<?>);
        assertTrue(batch.get("failures") instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) batch.get("failures");
        assertEquals("batch-duplicate.png", failures.getFirst().get("fileName"));
        assertEquals(409, failures.getFirst().get("status"));
    }

    @Test
    void recurringItemCannotPostTwiceOnTheSameDay() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Recurring Lock " + System.nanoTime());
        createAccount(token, companyId, "Recurring account", "1000");
        createCategory(token, companyId, "Recurring expense", "expense");
        ApiResponse created = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "Once per day",
            "type", 2,
            "amount", 15,
            "frequency", "daily",
            "interval", 1,
            "startDate", "2026-07-14"
        ), token);
        assertEquals(200, created.status(), created.body());
        String recurringId = text(parseMap(created.body()).get("id"));

        assertEquals(200, request(
            "POST", "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId, null, token
        ).status());
        ApiResponse duplicate = request(
            "POST", "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId, null, token
        );
        assertEquals(409, duplicate.status(), duplicate.body());
        assertEquals(1, transactionCount(token, companyId));
    }

    @Test
    void recurringCommandsValidateBeforeWriteAndPersistCalendarSchedule() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Recurring Contract " + System.nanoTime());
        int before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM recurring_items WHERE company_id = ?", Integer.class, companyId
        );

        ApiResponse invalid = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "",
            "type", 3,
            "amount", 0,
            "frequency", "quarterly",
            "interval", 0,
            "dayOfWeek", 8,
            "dayOfMonth", 32,
            "monthOfYear", 13,
            "startDate", "2026-01-31"
        ), token);
        assertValidationFields(invalid, Set.of(
            "name", "type", "amount", "frequency", "interval", "dayOfWeek", "dayOfMonth", "monthOfYear"
        ));
        assertEquals(before, jdbc.queryForObject(
            "SELECT COUNT(*) FROM recurring_items WHERE company_id = ?", Integer.class, companyId
        ));

        ApiResponse created = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "Month-end settlement",
            "type", 2,
            "amount", 100,
            "frequency", "monthly",
            "interval", 1,
            "dayOfMonth", 31,
            "startDate", "2026-01-31"
        ), token);
        assertEquals(200, created.status(), created.body());
        Map<String, Object> item = parseMap(created.body());
        assertEquals("2026-02-28", item.get("nextExecution"));
        assertEquals("2026-02-28", jdbc.queryForObject(
            "SELECT next_execution FROM recurring_items WHERE id = ?", String.class, item.get("id")
        ));
    }

    @Test
    void companyMemberCanSubmitReimbursementButCannotManageFinanceVouchers() throws Exception {
        String memberToken = registerInvitedUser(uniqueEmail("reimbursement-member"));
        Map<String, Object> member = parseMap(request("GET", "/api/v1/auth/me", null, memberToken).body());
        long memberUserId = ((Number) member.get("id")).longValue();
        String memberEmail = text(member.get("email"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Reimbursement Scope " + System.nanoTime());
        createEmployee(adminToken, companyId, memberUserId, memberEmail, "viewer");

        ApiResponse reimbursement = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Member travel reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", memberEmail,
            "amount", 88
        ), memberToken);
        assertEquals(200, reimbursement.status(), reimbursement.body());
        long reimbursementId = ((Number) parseMap(reimbursement.body()).get("id")).longValue();

        ApiResponse approvalSubmission = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", reimbursementId,
            "title", "Member travel reimbursement approval",
            "amount", 88
        ), memberToken);
        assertEquals(200, approvalSubmission.status(), approvalSubmission.body());
        assertEquals("pending", jdbc.queryForObject(
            "SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, reimbursementId
        ));

        ApiResponse financeVoucher = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Member must not create purchase invoice",
            "voucherType", "purchase_invoice",
            "direction", "expense",
            "counterparty", "Supplier",
            "amount", 88
        ), memberToken);
        assertEquals(403, financeVoucher.status(), financeVoucher.body());

        ApiResponse workflowUpdate = request("PUT", "/api/v1/receipts/" + reimbursementId, Map.of(
            "status", "verified"
        ), memberToken);
        assertEquals(403, workflowUpdate.status(), workflowUpdate.body());
    }

    @Test
    void receiptApprovalCannotBeBypassedAndPostingWaitsForDecision() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Approval Flow " + System.nanoTime());
        ApiResponse created = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Approval required reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 6800,
            "issueDate", "2026-07-14"
        ), token);
        assertEquals(200, created.status(), created.body());
        Map<String, Object> voucher = parseMap(created.body());
        long voucherId = ((Number) voucher.get("id")).longValue();
        assertEquals("not_submitted", voucher.get("approvalStatus"));

        ApiResponse bypass = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "approvalStatus", "approved"
        ), token);
        assertEquals(400, bypass.status(), bypass.body());

        ApiResponse earlyPosting = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "accountingStatus", "posted"
        ), token);
        assertEquals(409, earlyPosting.status(), earlyPosting.body());

        ApiResponse submitted = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", voucherId,
            "title", "Reimbursement approval",
            "amount", 6800
        ), token);
        assertEquals(200, submitted.status(), submitted.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> approvalRequest = (Map<String, Object>) parseMap(submitted.body()).get("request");
        long approvalId = ((Number) approvalRequest.get("id")).longValue();
        assertEquals("pending", jdbc.queryForObject("SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, voucherId));

        ApiResponse approved = request("POST", "/api/v1/approvals/" + approvalId + "/approve", Map.of(
            "comment", "Evidence checked"
        ), token);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("approved", jdbc.queryForObject("SELECT approval_status FROM receipt_vouchers WHERE id = ?", String.class, voucherId));

        ApiResponse posted = request("PUT", "/api/v1/receipts/" + voucherId, Map.of(
            "accountingStatus", "posted"
        ), token);
        assertEquals(200, posted.status(), posted.body());
        assertEquals("posted", parseMap(posted.body()).get("accountingStatus"));
    }

    @Test
    void approvalTerminalStatesCannotBeReplayedAndWithdrawRestoresReceiptState() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Approval State Machine " + System.nanoTime());
        ApiResponse submitted = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "other",
            "entityType", "other",
            "title", "Terminal approval transition"
        ), token);
        assertEquals(200, submitted.status(), submitted.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) parseMap(submitted.body()).get("request");
        long approvalId = ((Number) request.get("id")).longValue();

        ApiResponse missingComment = request(
            "POST",
            "/api/v1/approvals/" + approvalId + "/reject",
            Map.of(),
            token
        );
        assertEquals(400, missingComment.status(), missingComment.body());
        assertEquals("pending", jdbc.queryForObject(
            "SELECT status FROM approval_requests WHERE id = ?",
            String.class,
            approvalId
        ));

        assertEquals(200, request(
            "POST",
            "/api/v1/approvals/" + approvalId + "/reject",
            Map.of("comment", "Evidence is incomplete"),
            token
        ).status());
        ApiResponse replay = request(
            "POST",
            "/api/v1/approvals/" + approvalId + "/approve",
            Map.of("comment", "Must remain rejected"),
            token
        );
        assertEquals(409, replay.status(), replay.body());

        ApiResponse receipt = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Withdrawn reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 320
        ), token);
        long voucherId = ((Number) parseMap(receipt.body()).get("id")).longValue();
        ApiResponse receiptApproval = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", voucherId,
            "title", "Withdraw reimbursement"
        ), token);
        @SuppressWarnings("unchecked")
        Map<String, Object> receiptRequest = (Map<String, Object>) parseMap(receiptApproval.body()).get("request");
        long receiptApprovalId = ((Number) receiptRequest.get("id")).longValue();

        assertEquals(200, request(
            "POST",
            "/api/v1/approvals/" + receiptApprovalId + "/withdraw",
            Map.of("comment", "Submitted by mistake"),
            token
        ).status());
        assertEquals("withdrawn", jdbc.queryForObject(
            "SELECT status FROM approval_requests WHERE id = ?",
            String.class,
            receiptApprovalId
        ));
        assertEquals("not_submitted", jdbc.queryForObject(
            "SELECT approval_status FROM receipt_vouchers WHERE id = ?",
            String.class,
            voucherId
        ));
    }

    @Test
    void approvalCommandValidationRejectsInvalidWritesAndPreservesIdempotency() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Approval Contract " + System.nanoTime());
        int requestsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests WHERE company_id = ?", Integer.class, companyId
        );

        ApiResponse invalid = request("POST", "/api/v1/approvals", Map.of(
            "companyId", companyId,
            "requestType", "unsupported",
            "entityId", -1,
            "title", "x".repeat(161),
            "amount", "-0.01",
            "assigneeUserId", -1,
            "description", "x".repeat(1001),
            "comment", "x".repeat(501)
        ), token);

        assertValidationFields(invalid, Set.of(
            "requestType", "entityId", "title", "amount", "assigneeUserId", "description", "comment"
        ));
        assertEquals(requestsBefore, jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests WHERE company_id = ?", Integer.class, companyId
        ));

        Map<String, Object> command = Map.of(
            "companyId", companyId,
            "requestType", "other",
            "entityType", "other",
            "title", "Typed approval command"
        );
        Map<String, String> headers = Map.of("Idempotency-Key", "approval-contract-" + System.nanoTime());
        ApiResponse created = request("POST", "/api/v1/approvals", command, token, headers);
        ApiResponse replayed = request("POST", "/api/v1/approvals", command, token, headers);
        assertEquals(200, created.status(), created.body());
        assertEquals(200, replayed.status(), replayed.body());
        long approvalId = approvalId(created);
        assertEquals(approvalId, approvalId(replayed));
        assertEquals(requestsBefore + 1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests WHERE company_id = ?", Integer.class, companyId
        ));

        ApiResponse pageResponse = request(
            "GET",
            "/api/v1/approvals?companyId=" + companyId
                + "&status=pending&requestType=other&keyword=Typed&page=0&size=10",
            null,
            token
        );
        assertEquals(200, pageResponse.status(), pageResponse.body());
        Map<String, Object> page = parseMap(pageResponse.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvals = (List<Map<String, Object>>) page.get("content");
        assertEquals(1, approvals.size());
        assertEquals(approvalId, ((Number) approvals.getFirst().get("id")).longValue());
        assertEquals(1, ((Number) page.get("totalElements")).intValue());

        ApiResponse summaryResponse = request(
            "GET",
            "/api/v1/approvals/summary?companyId=" + companyId,
            null,
            token
        );
        assertEquals(200, summaryResponse.status(), summaryResponse.body());
        Map<String, Object> summary = parseMap(summaryResponse.body());
        assertEquals(requestsBefore + 1, ((Number) summary.get("total")).intValue());
        assertEquals(requestsBefore + 1, ((Number) summary.get("pending")).intValue());
        assertEquals(requestsBefore + 1, ((Number) summary.get("minePending")).intValue());

        ApiResponse invalidDecision = request(
            "POST",
            "/api/v1/approvals/" + approvalId + "/reject",
            Map.of("comment", "x".repeat(501)),
            token
        );
        assertValidationFields(invalidDecision, Set.of("comment"));
        assertEquals("pending", jdbc.queryForObject(
            "SELECT status FROM approval_requests WHERE id = ?", String.class, approvalId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_actions WHERE request_id = ?", Integer.class, approvalId
        ));
    }

    @Test
    void approvalEntityReferencesMustBelongToTheSelectedCompany() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long sourceCompanyId = createCompany(token, "Approval Entity Source " + System.nanoTime());
        long selectedCompanyId = createCompany(token, "Approval Entity Target " + System.nanoTime());
        String employeeToken = registerInvitedUser(uniqueEmail("approval-entity-employee"));
        Map<String, Object> employeeUser = parseMap(request("GET", "/api/v1/auth/me", null, employeeToken).body());
        Map<String, Object> employee = createEmployee(
            token,
            sourceCompanyId,
            ((Number) employeeUser.get("id")).longValue(),
            text(employeeUser.get("email")),
            "employee"
        );
        Map<String, Object> account = createAccount(token, sourceCompanyId, "Approval entity account", "1000");
        Map<String, Object> category = createCategory(token, sourceCompanyId, "Approval entity expense", "expense");
        Map<String, Object> createdTransaction = createTransaction(token, sourceCompanyId, account, category, "20");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) createdTransaction.get("transaction");

        ApiResponse budgetResponse = request("POST", "/api/v1/budgets", Map.of(
            "companyId", sourceCompanyId,
            "name", "Approval entity budget",
            "amount", 100,
            "categoryId", category.get("id"),
            "startDate", "2026-07-01",
            "endDate", "2026-07-31",
            "warningThreshold", 85
        ), token);
        assertEquals(200, budgetResponse.status(), budgetResponse.body());

        ApiResponse payrollRunResponse = request("POST", "/api/v1/payroll-runs", Map.of(
            "companyId", sourceCompanyId,
            "period", "2026-07"
        ), token);
        assertEquals(200, payrollRunResponse.status(), payrollRunResponse.body());

        Map<String, Long> foreignReferences = new LinkedHashMap<>();
        foreignReferences.put("transaction", ((Number) transaction.get("id")).longValue());
        foreignReferences.put("budget", ((Number) parseMap(budgetResponse.body()).get("id")).longValue());
        foreignReferences.put("employee", ((Number) employee.get("id")).longValue());
        foreignReferences.put("payroll_run", ((Number) parseMap(payrollRunResponse.body()).get("id")).longValue());

        int requestsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests WHERE company_id = ?",
            Integer.class,
            selectedCompanyId
        );
        for (Map.Entry<String, Long> reference : foreignReferences.entrySet()) {
            ApiResponse response = request("POST", "/api/v1/approvals", Map.of(
                "companyId", selectedCompanyId,
                "requestType", "other",
                "entityType", reference.getKey(),
                "entityId", reference.getValue(),
                "title", "Reject cross-company " + reference.getKey()
            ), token);
            assertEquals(400, response.status(), reference.getKey() + ": " + response.body());
        }

        ApiResponse missing = request("POST", "/api/v1/approvals", Map.of(
            "companyId", selectedCompanyId,
            "requestType", "other",
            "entityType", "transaction",
            "entityId", Long.MAX_VALUE,
            "title", "Reject missing transaction"
        ), token);
        assertEquals(400, missing.status(), missing.body());
        assertEquals(requestsBefore, jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_requests WHERE company_id = ?",
            Integer.class,
            selectedCompanyId
        ));
    }

    @Test
    void globalSearchReturnsCompanyScopedBusinessRecords() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Search Flow " + System.nanoTime());
        String needle = "Needle" + System.nanoTime();
        createAccount(token, companyId, needle, "1234");

        ApiResponse response = request("GET", "/api/v1/search?companyId=" + companyId + "&keyword=" + needle, null, token);
        assertEquals(200, response.status(), response.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) parseMap(response.body()).get("results");
        Map<String, Object> accountResult = results.stream()
            .filter(result -> "account".equals(result.get("type")) && needle.equals(result.get("title")))
            .findFirst()
            .orElseThrow();
        assertTrue(text(accountResult.get("subtitle")).endsWith("¥1234"));
    }

    @Test
    void workforceCostUsesPayrollSnapshotAfterMonthlyRunIsCreated() throws Exception {
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Workforce Snapshot " + System.nanoTime());
        long departmentId = createDepartment(adminToken, companyId, "Product");
        String employeeToken = registerInvitedUser(uniqueEmail("workforce-employee"));
        Map<String, Object> employeeUser = parseMap(request("GET", "/api/v1/auth/me", null, employeeToken).body());
        Map<String, Object> employee = createEmployee(
            adminToken,
            companyId,
            ((Number) employeeUser.get("id")).longValue(),
            text(employeeUser.get("email")),
            "employee",
            "self",
            departmentId
        );
        long employeeId = ((Number) employee.get("id")).longValue();
        ApiResponse salaryUpdate = request(
            "PUT", "/api/v1/enterprise/employees/" + employeeId, Map.of("salary", 10000), adminToken
        );
        assertEquals(200, salaryUpdate.status(), salaryUpdate.body());

        ApiResponse estimateResponse = request(
            "GET", "/api/v1/workforce-cost?companyId=" + companyId + "&period=2026-07", null, adminToken
        );
        assertEquals(200, estimateResponse.status(), estimateResponse.body());
        Map<String, Object> estimate = parseMap(estimateResponse.body());
        assertEquals("employee_estimate", estimate.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> estimateCosts = (Map<String, Object>) estimate.get("costs");
        assertEquals(0, new BigDecimal("10000").compareTo(decimal(estimateCosts.get("salary"))));
        assertTrue(decimal(estimateCosts.get("total")).compareTo(new BigDecimal("10000")) > 0);

        ApiResponse createRunResponse = request("POST", "/api/v1/payroll-runs", Map.of(
            "companyId", companyId,
            "period", "2026-07"
        ), adminToken);
        assertEquals(200, createRunResponse.status(), createRunResponse.body());
        long runId = ((Number) parseMap(createRunResponse.body()).get("id")).longValue();

        ApiResponse secondSalaryUpdate = request(
            "PUT", "/api/v1/enterprise/employees/" + employeeId, Map.of("salary", 20000), adminToken
        );
        assertEquals(200, secondSalaryUpdate.status(), secondSalaryUpdate.body());

        ApiResponse snapshotResponse = request(
            "GET", "/api/v1/workforce-cost?companyId=" + companyId + "&period=2026-07", null, adminToken
        );
        assertEquals(200, snapshotResponse.status(), snapshotResponse.body());
        Map<String, Object> snapshot = parseMap(snapshotResponse.body());
        assertEquals("payroll_run", snapshot.get("source"));
        assertEquals("draft", snapshot.get("payrollRunStatus"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotCosts = (Map<String, Object>) snapshot.get("costs");
        assertEquals(0, new BigDecimal("10000").compareTo(decimal(snapshotCosts.get("salary"))));

        ApiResponse closeResponse = request("POST", "/api/v1/payroll-runs/" + runId + "/close", null, adminToken);
        assertEquals(200, closeResponse.status(), closeResponse.body());
        ApiResponse closedResponse = request(
            "GET", "/api/v1/workforce-cost?companyId=" + companyId + "&period=2026-07", null, adminToken
        );
        assertEquals(200, closedResponse.status(), closedResponse.body());
        assertEquals("closed", parseMap(closedResponse.body()).get("payrollRunStatus"));
    }

    @Test
    void employmentHistoryPersistsTypedCompanyScopedLifecycleEvents() throws Exception {
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(adminToken, "Employment History " + System.nanoTime());
        String employeeToken = registerInvitedUser(uniqueEmail("employment-history"));
        Map<String, Object> employeeUser = parseMap(request("GET", "/api/v1/auth/me", null, employeeToken).body());
        Map<String, Object> employee = createEmployee(
            adminToken,
            companyId,
            ((Number) employeeUser.get("id")).longValue(),
            text(employeeUser.get("email")),
            "employee"
        );
        long employeeId = id(employee);

        ApiResponse departure = request(
            "PUT",
            "/api/v1/enterprise/employees/" + employeeId,
            Map.of("status", "departed", "leaveDate", "2026-07-31"),
            adminToken
        );
        assertEquals(200, departure.status(), departure.body());

        ApiResponse history = request(
            "GET",
            "/api/v1/enterprise/employment-events?companyId=" + companyId,
            null,
            adminToken
        );
        assertEquals(200, history.status(), history.body());
        List<Map<String, Object>> employeeEvents = parseList(history.body()).stream()
            .filter(event -> ((Number) event.get("employeeId")).longValue() == employeeId)
            .toList();
        assertEquals(2, employeeEvents.size());
        assertEquals(
            List.of("offboard", "onboard"),
            employeeEvents.stream().map(event -> event.get("type")).toList()
        );
        assertEquals(
            List.of("2026-07-31", "2026-07-01"),
            employeeEvents.stream().map(event -> event.get("effectiveDate")).toList()
        );
    }

    @Test
    void companyProfileUsesNormalizedTypedOptimisticPersistence() throws Exception {
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        String creditCode = "tenant-" + System.nanoTime();
        ApiResponse createdResponse = request(
            "POST",
            "/api/v1/enterprise/companies",
            Map.of(
                "name", "  Durable Tenant  ",
                "entityType", " COMPANY ",
                "creditCode", creditCode,
                "currency", " cny ",
                "industry", " Software ",
                "taxpayerType", " General "
            ),
            adminToken
        );
        assertEquals(200, createdResponse.status(), createdResponse.body());
        Map<String, Object> created = parseMap(createdResponse.body());
        long companyId = id(created);
        assertEquals(0, ((Number) created.get("version")).intValue());
        assertEquals("Durable Tenant", created.get("name"));
        assertEquals("company", created.get("entityType"));
        assertEquals(creditCode.toUpperCase(), created.get("creditCode"));
        assertEquals("CNY", created.get("currency"));

        ApiResponse updatedResponse = request(
            "PUT",
            "/api/v1/enterprise/company?companyId=" + companyId,
            Map.of(
                "name", "  Durable Tenant Updated  ",
                "currency", " usd ",
                "country", " China ",
                "province", " Guangdong ",
                "city", " Shenzhen ",
                "district", " Nanshan ",
                "fiscalYearStartMonth", 4
            ),
            adminToken
        );
        assertEquals(200, updatedResponse.status(), updatedResponse.body());
        Map<String, Object> updated = parseMap(updatedResponse.body());
        assertEquals(1, ((Number) updated.get("version")).intValue());
        assertEquals("Durable Tenant Updated", updated.get("name"));
        assertEquals("USD", updated.get("currency"));
        assertEquals("China/Guangdong/Shenzhen/Nanshan", updated.get("operatingRegion"));

        ApiResponse profileResponse = request(
            "GET",
            "/api/v1/enterprise/company?companyId=" + companyId,
            null,
            adminToken
        );
        assertEquals(200, profileResponse.status(), profileResponse.body());
        Map<String, Object> profile = parseMap(profileResponse.body());
        assertEquals(updated.get("version"), profile.get("version"));
        assertEquals(updated.get("name"), profile.get("name"));
        assertEquals("timestamp with time zone", jdbc.queryForObject("""
            SELECT pg_typeof(created_at)::TEXT FROM companies WHERE id = ?
            """, String.class, companyId));

        ApiResponse invalid = request(
            "PUT",
            "/api/v1/enterprise/company?companyId=" + companyId,
            Map.of("fiscalYearStartMonth", 13),
            adminToken
        );
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals(4, jdbc.queryForObject(
            "SELECT fiscal_year_start_month FROM companies WHERE id = ?",
            Integer.class,
            companyId
        ));
    }

    @Test
    void entityTransfersUseTypedAppendOnlyTenantPersistence() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        String sourceName = "Transfer Source " + System.nanoTime();
        String targetName = "Transfer Target " + System.nanoTime();
        long sourceId = createCompany(token, sourceName);
        long targetId = createCompany(token, targetName);

        ApiResponse createdResponse = request(
            "POST",
            "/api/v1/enterprise/entity-transfers",
            Map.of(
                "fromEntityId", sourceId,
                "toEntityId", targetId,
                "transferType", " SHAREHOLDER_ADVANCE ",
                "amount", "125.5000",
                "currency", " cny ",
                "transferDate", "2026-09-04",
                "note", " working capital ",
                "status", " RECORDED "
            ),
            token
        );

        assertEquals(200, createdResponse.status(), createdResponse.body());
        Map<String, Object> created = parseMap(createdResponse.body());
        long transferId = id(created);
        assertEquals(sourceId, ((Number) created.get("fromEntityId")).longValue());
        assertEquals(targetId, ((Number) created.get("toEntityId")).longValue());
        assertEquals(sourceName, created.get("fromEntityName"));
        assertEquals(targetName, created.get("toEntityName"));
        assertEquals("shareholder_advance", created.get("transferType"));
        assertEquals("CNY", created.get("currency"));
        assertEquals("working capital", created.get("note"));
        assertEquals("recorded", created.get("status"));

        List<Map<String, Object>> scoped = parseList(request(
            "GET",
            "/api/v1/enterprise/entity-transfers?entityId=" + sourceId,
            null,
            token
        ).body());
        assertEquals(1, scoped.size());
        assertEquals(transferId, id(scoped.getFirst()));
        assertEquals("numeric", jdbc.queryForObject(
            "SELECT pg_typeof(amount)::TEXT FROM entity_transfers WHERE id = ?",
            String.class,
            transferId
        ));
        assertEquals("date", jdbc.queryForObject(
            "SELECT pg_typeof(transfer_date)::TEXT FROM entity_transfers WHERE id = ?",
            String.class,
            transferId
        ));
        assertEquals("timestamp with time zone", jdbc.queryForObject(
            "SELECT pg_typeof(created_at)::TEXT FROM entity_transfers WHERE id = ?",
            String.class,
            transferId
        ));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE company_id = ? AND entity_type = 'entity_transfer' AND entity_id = ? AND action = 'create'
            """, Integer.class, sourceId, transferId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE company_id = ? AND aggregate_type = 'entity_transfer' AND aggregate_id = ?
              AND event_type = 'enterprise.entity_transfer.create'
            """, Integer.class, sourceId, transferId));

        ApiResponse invalid = request(
            "POST",
            "/api/v1/enterprise/entity-transfers",
            Map.of(
                "fromEntityId", sourceId,
                "toEntityId", targetId,
                "transferType", "cash_move",
                "amount", "1.00001"
            ),
            token
        );
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM entity_transfers WHERE from_entity_id = ? AND to_entity_id = ?",
            Integer.class,
            sourceId,
            targetId
        ));
    }

    @Test
    void concurrentApprovalSubmissionCreatesOnlyOnePendingRequest() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent approval");
        ApiResponse voucherResponse = request("POST", "/api/v1/receipts", Map.of(
            "companyId", companyId,
            "title", "Concurrent reimbursement",
            "voucherType", "reimbursement",
            "direction", "expense",
            "counterparty", "Employee",
            "amount", 100
        ), token);
        assertEquals(200, voucherResponse.status(), voucherResponse.body());
        long voucherId = id(parseMap(voucherResponse.body()));
        Map<String, Object> body = Map.of(
            "companyId", companyId,
            "requestType", "reimbursement",
            "entityType", "receipt_voucher",
            "entityId", voucherId,
            "title", "Only one pending approval",
            "amount", 100
        );
        String leaseKey = "approval:" + companyId + ":receipt_voucher:" + voucherId;

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = holdApprovalLease(leaseKey)) {
            first = requestAsync("POST", "/api/v1/approvals", body, token);
            second = requestAsync("POST", "/api/v1/approvals", body, token);
            awaitBlockedQueries("pg_advisory_xact_lock", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM approval_requests
            WHERE company_id = ? AND entity_type = 'receipt_voucher' AND entity_id = ? AND status = 'pending'
            """, Integer.class, companyId, voucherId));
    }

    @Test
    void concurrentPayrollClosePublishesSideEffectsOnce() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent payroll");
        createEmployee(token, companyId);
        ApiResponse created = request("POST", "/api/v1/payroll-runs", Map.of(
            "companyId", companyId,
            "period", "2026-07"
        ), token);
        assertEquals(200, created.status(), created.body());
        long runId = id(parseMap(created.body()));

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = lockRow("SELECT id FROM payroll_runs WHERE id = ? FOR UPDATE", runId)) {
            first = requestAsync("POST", "/api/v1/payroll-runs/" + runId + "/close", null, token);
            second = requestAsync("POST", "/api/v1/payroll-runs/" + runId + "/close", null, token);
            awaitBlockedQueries("FROM payroll_runs WHERE id", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(200, firstResponse.status(), firstResponse.body());
        assertEquals(200, secondResponse.status(), secondResponse.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'payroll.run.closed' AND aggregate_id = ?",
            Integer.class,
            runId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE entity_type = 'payroll_run' AND entity_id = ? AND action = 'close'",
            Integer.class,
            runId
        ));
    }

    @Test
    void concurrentRecurringExecutionPostsOneTransactionAndIncrementsOnce() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent recurring");
        Map<String, Object> account = createAccount(token, companyId, "Recurring account", "1000");
        createCategory(token, companyId, "Recurring expense", "expense");
        String note = "recurring-" + System.nanoTime();
        ApiResponse created = request("POST", "/api/v1/recurring", Map.of(
            "companyId", companyId,
            "name", "Concurrent recurring item",
            "type", 2,
            "amount", 25,
            "frequency", "monthly",
            "interval", 1,
            "startDate", LocalDate.now().toString(),
            "note", note
        ), token);
        assertEquals(200, created.status(), created.body());
        String recurringId = String.valueOf(parseMap(created.body()).get("id"));

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = lockRow("SELECT id FROM recurring_items WHERE id = ? FOR UPDATE", recurringId)) {
            String path = "/api/v1/recurring/" + recurringId + "/execute?companyId=" + companyId;
            first = requestAsync("POST", path, null, token);
            second = requestAsync("POST", path, null, token);
            awaitBlockedQueries("FROM recurring_items WHERE id", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE company_id = ? AND account_id = ? AND note = ?",
            Integer.class,
            companyId,
            id(account),
            note
        ));
        Map<String, Object> state = jdbc.queryForMap(
            "SELECT execution_count, last_executed FROM recurring_items WHERE id = ?",
            recurringId
        );
        assertEquals(1, ((Number) state.get("execution_count")).intValue());
        assertEquals(LocalDate.now(), ((java.sql.Date) state.get("last_executed")).toLocalDate());
    }

    @Test
    void taxItemCommandsValidateDeriveAuditAndReadPostgresTruth() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Tax persistence " + System.nanoTime());
        int before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tax_items WHERE company_id = ?", Integer.class, companyId
        );
        Map<String, Object> invalidBody = new LinkedHashMap<>();
        invalidBody.put("companyId", 0);
        invalidBody.put("name", "   ");
        invalidBody.put("period", "2026-13");
        invalidBody.put("taxType", "sales_tax");
        invalidBody.put("taxableAmount", -1);
        invalidBody.put("taxAmount", -1);
        invalidBody.put("paidAmount", -1);
        invalidBody.put("deductibleAmount", -1);
        invalidBody.put("taxRate", "100.00001");
        invalidBody.put("status", "closed");
        invalidBody.put("filingStatus", "filed");
        invalidBody.put("paymentStatus", "settled");
        invalidBody.put("frequency", "weekly");
        invalidBody.put("responsiblePerson", " ");
        invalidBody.put("riskLevel", "critical");
        invalidBody.put("policyBasis", " ");
        invalidBody.put("sourceType", "spreadsheet");
        invalidBody.put("note", "x".repeat(2001));

        ApiResponse invalid = request("POST", "/api/v1/enterprise/tax-items", invalidBody, token);
        assertValidationFields(invalid, Set.of(
            "companyId", "name", "period", "taxType", "taxableAmount", "taxAmount", "paidAmount",
            "deductibleAmount", "taxRate", "status", "filingStatus", "paymentStatus", "frequency",
            "responsiblePerson", "riskLevel", "policyBasis", "sourceType", "note"
        ));
        assertEquals(before, jdbc.queryForObject(
            "SELECT COUNT(*) FROM tax_items WHERE company_id = ?", Integer.class, companyId
        ));

        LocalDate today = LocalDate.now();
        String period = today.toString().substring(0, 7);
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("companyId", companyId);
        createBody.put("name", "  Current VAT filing  ");
        createBody.put("period", " " + period + " ");
        createBody.put("taxType", " VAT ");
        createBody.put("taxableAmount", "1000.0000");
        createBody.put("taxAmount", "100.0000");
        createBody.put("paidAmount", "25.0000");
        createBody.put("deductibleAmount", "5.0000");
        createBody.put("dueDate", today.plusDays(30).toString());
        createBody.put("declarationDate", today.toString());
        createBody.put("responsiblePerson", " Finance team ");
        createBody.put("policyBasis", " CN-VAT-TEST ");
        createBody.put("note", " quarter close ");
        ApiResponse created = request("POST", "/api/v1/enterprise/tax-items", createBody, token);
        assertEquals(200, created.status(), created.body());
        Map<String, Object> item = parseMap(created.body());
        long itemId = id(item);
        assertEquals("Current VAT filing", item.get("name"));
        assertEquals(period, item.get("period"));
        assertEquals("vat", item.get("taxType"));
        assertEquals(0, new BigDecimal("10.0000").compareTo(decimal(item.get("taxRate"))));
        assertEquals("partial", item.get("paymentStatus"));
        assertEquals("prepared", item.get("filingStatus"));
        assertEquals("medium", item.get("riskLevel"));
        assertEquals("Finance team", item.get("responsiblePerson"));
        assertEquals("CN-VAT-TEST", item.get("policyBasis"));
        assertEquals("quarter close", item.get("note"));

        Map<String, Object> resetDefaults = new LinkedHashMap<>();
        resetDefaults.put("note", null);
        resetDefaults.put("responsiblePerson", null);
        resetDefaults.put("policyBasis", null);
        ApiResponse updated = request(
            "PUT", "/api/v1/enterprise/tax-items/" + itemId, resetDefaults, token
        );
        assertEquals(200, updated.status(), updated.body());
        Map<String, Object> updatedItem = parseMap(updated.body());
        assertNull(updatedItem.get("note"));
        assertEquals("财务负责人", updatedItem.get("responsiblePerson"));
        assertFalse(text(updatedItem.get("policyBasis")).isBlank());

        jdbc.update("UPDATE tax_items SET name = 'PostgreSQL truth' WHERE id = ?", itemId);
        ApiResponse listed = request(
            "GET", "/api/v1/enterprise/tax-items?companyId=" + companyId, null, token
        );
        assertEquals(200, listed.status(), listed.body());
        assertEquals("PostgreSQL truth", parseList(listed.body()).stream()
            .filter(value -> id(value) == itemId)
            .findFirst()
            .orElseThrow()
            .get("name"));

        ApiResponse deleted = request("DELETE", "/api/v1/enterprise/tax-items/" + itemId, null, token);
        assertEquals(200, deleted.status(), deleted.body());
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM tax_items WHERE id = ?", Integer.class, itemId
        ));
        assertEquals(Set.of("create", "update", "delete"), Set.copyOf(jdbc.queryForList("""
            SELECT action FROM audit_logs WHERE entity_type = 'tax_item' AND entity_id = ?
            """, String.class, itemId)));
        assertEquals(Set.of(
            "enterprise.tax_item.create",
            "enterprise.tax_item.update",
            "enterprise.tax_item.delete"
        ), Set.copyOf(jdbc.queryForList("""
            SELECT event_type FROM outbox_events WHERE aggregate_type = 'tax_item' AND aggregate_id = ?
            """, String.class, itemId)));
    }

    @Test
    void concurrentTaxItemCreationKeepsOneCompanyPeriod() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent tax " + System.nanoTime());
        String period = String.valueOf(LocalDate.now().getYear());
        Map<String, Object> body = Map.of(
            "companyId", companyId,
            "name", "Annual stamp duty",
            "period", period,
            "taxType", "stamp_duty",
            "taxableAmount", 1000,
            "taxAmount", 5,
            "paidAmount", 0,
            "frequency", "annual",
            "dueDate", LocalDate.now().plusDays(30).toString()
        );

        CompletableFuture<ApiResponse> first = requestAsync(
            "POST", "/api/v1/enterprise/tax-items", body, token
        );
        CompletableFuture<ApiResponse> second = requestAsync(
            "POST", "/api/v1/enterprise/tax-items", body, token
        );
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status())
            .stream().sorted().toList(), firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM tax_items WHERE company_id = ? AND tax_type = 'stamp_duty' AND period = ?
            """, Integer.class, companyId, period));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE company_id = ? AND entity_type = 'tax_item' AND action = 'create'
            """, Integer.class, companyId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE company_id = ? AND event_type = 'enterprise.tax_item.create'
            """, Integer.class, companyId));
    }

    @Test
    void departmentCommandsValidateScopeAndPersistAuditOutbox() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Department Contract " + System.nanoTime());
        long otherCompanyId = createCompany(token, "Department Other " + System.nanoTime());
        int departmentsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM departments WHERE company_id = ?", Integer.class, companyId
        );

        ApiResponse invalid = request("POST", "/api/v1/enterprise/departments", Map.of(
            "companyId", companyId,
            "name", " ",
            "costCenter", " ",
            "budget", "-0.01",
            "managerEmployeeId", -1,
            "status", 2
        ), token);
        assertValidationFields(invalid, Set.of("name", "costCenter", "budget", "managerEmployeeId", "status"));
        assertEquals(departmentsBefore, jdbc.queryForObject(
            "SELECT COUNT(*) FROM departments WHERE company_id = ?", Integer.class, companyId
        ));

        ApiResponse created = request("POST", "/api/v1/enterprise/departments", Map.of(
            "companyId", companyId,
            "name", "  Product  ",
            "costCenter", "  RND  ",
            "budget", "1234.50",
            "status", 1
        ), token);
        assertEquals(200, created.status(), created.body());
        Map<String, Object> department = parseMap(created.body());
        long departmentId = id(department);
        assertEquals("Product", department.get("name"));
        assertEquals("RND", department.get("costCenter"));
        assertEquals(0, new BigDecimal("1234.50").compareTo(decimal(department.get("budget"))));

        ApiResponse duplicate = request("POST", "/api/v1/enterprise/departments", Map.of(
            "companyId", companyId,
            "name", "product",
            "costCenter", "RND-2",
            "budget", 0
        ), token);
        assertEquals(409, duplicate.status(), duplicate.body());
        assertEquals("duplicate_record", parseMap(duplicate.body()).get("code"));

        ApiResponse outsiderResponse = request("POST", "/api/v1/enterprise/employees", Map.of(
            "companyId", otherCompanyId,
            "name", "Other company manager",
            "email", uniqueEmail("other-company-manager"),
            "position", "Manager",
            "employmentType", "full_time",
            "status", "active",
            "hireDate", "2026-09-01"
        ), token);
        assertEquals(200, outsiderResponse.status(), outsiderResponse.body());
        long outsiderId = id(parseMap(outsiderResponse.body()));
        ApiResponse crossCompanyManager = request(
            "PUT",
            "/api/v1/enterprise/departments/" + departmentId,
            Map.of("companyId", companyId, "managerEmployeeId", outsiderId),
            token
        );
        assertEquals(400, crossCompanyManager.status(), crossCompanyManager.body());

        ApiResponse wrongScope = request(
            "PUT",
            "/api/v1/enterprise/departments/" + departmentId,
            Map.of("companyId", otherCompanyId, "name", "Must not move"),
            token
        );
        assertEquals(403, wrongScope.status(), wrongScope.body());

        ApiResponse managerResponse = request("POST", "/api/v1/enterprise/employees", Map.of(
            "companyId", companyId,
            "departmentId", departmentId,
            "name", "Product manager",
            "email", uniqueEmail("product-manager"),
            "position", "Manager",
            "employmentType", "full_time",
            "status", "active",
            "hireDate", "2026-09-01"
        ), token);
        assertEquals(200, managerResponse.status(), managerResponse.body());
        long managerId = id(parseMap(managerResponse.body()));
        ApiResponse updated = request(
            "PUT",
            "/api/v1/enterprise/departments/" + departmentId,
            Map.of(
                "companyId", companyId,
                "managerEmployeeId", managerId,
                "budget", "2500.25",
                "status", 0
            ),
            token
        );
        assertEquals(200, updated.status(), updated.body());
        assertEquals(managerId, ((Number) parseMap(updated.body()).get("managerEmployeeId")).longValue());

        jdbc.update("UPDATE departments SET name = 'Database Product' WHERE id = ?", departmentId);
        ApiResponse listed = request(
            "GET", "/api/v1/enterprise/departments?companyId=" + companyId, null, token
        );
        assertEquals(200, listed.status(), listed.body());
        assertTrue(parseList(listed.body()).stream()
            .anyMatch(row -> departmentId == id(row) && "Database Product".equals(row.get("name"))));

        Map<String, Object> clearManager = new LinkedHashMap<>();
        clearManager.put("companyId", companyId);
        clearManager.put("managerEmployeeId", null);
        ApiResponse cleared = request(
            "PUT", "/api/v1/enterprise/departments/" + departmentId, clearManager, token
        );
        assertEquals(200, cleared.status(), cleared.body());
        assertNull(parseMap(cleared.body()).get("managerEmployeeId"));
        assertEquals(3, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE company_id = ? AND entity_type = 'department' AND entity_id = ?
            """, Integer.class, companyId, departmentId));
        assertEquals(3, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE company_id = ? AND aggregate_type = 'department' AND aggregate_id = ?
              AND event_type IN ('people.department.create', 'people.department.update')
            """, Integer.class, companyId, departmentId));
    }

    @Test
    void concurrentDepartmentCreationKeepsOneNormalizedCompanyName() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Department Race " + System.nanoTime());
        String name = "Concurrent Product " + System.nanoTime();
        Map<String, Object> firstBody = Map.of(
            "companyId", companyId,
            "name", name,
            "costCenter", "RACE-A",
            "budget", 0
        );
        Map<String, Object> secondBody = Map.of(
            "companyId", companyId,
            "name", name.toUpperCase(),
            "costCenter", "RACE-B",
            "budget", 0
        );

        CompletableFuture<ApiResponse> first = requestAsync(
            "POST", "/api/v1/enterprise/departments", firstBody, token
        );
        CompletableFuture<ApiResponse> second = requestAsync(
            "POST", "/api/v1/enterprise/departments", secondBody, token
        );
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(200, 409), List.of(firstResponse.status(), secondResponse.status())
            .stream().sorted().toList(), firstResponse.body() + " / " + secondResponse.body());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM departments
            WHERE company_id = ? AND LOWER(name) = LOWER(?)
            """, Integer.class, companyId, name));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE company_id = ? AND event_type = 'people.department.create'
            """, Integer.class, companyId));
    }

    @Test
    void outboxTerminalTransitionsRequireTheCurrentDeliveryLease() {
        String processedLease = "processed-owner-" + UUID.randomUUID();
        long processedId = processingOutboxEvent(processedLease);
        String completedAt = OffsetDateTime.now().toString();

        assertFalse(outboxStatusRepository.markProcessed(processedId, "stale-worker", completedAt));
        assertEquals("processing", outboxStatus(processedId));
        assertEquals(processedLease, outboxLockToken(processedId));

        assertTrue(outboxStatusRepository.markProcessed(processedId, processedLease, completedAt));
        assertEquals("processed", outboxStatus(processedId));
        assertNull(outboxLockToken(processedId));
        assertNotNull(jdbc.queryForObject(
            "SELECT processed_at FROM outbox_events WHERE id = ?",
            String.class,
            processedId
        ));

        String failedLease = "failed-owner-" + UUID.randomUUID();
        long failedId = processingOutboxEvent(failedLease);
        String nextAttemptAt = OffsetDateTime.now().plusMinutes(1).toString();

        assertThrows(IllegalArgumentException.class, () -> outboxStatusRepository.markFailed(
            failedId, failedLease, "processed", nextAttemptAt, "invalid transition", completedAt
        ));
        assertEquals("processing", outboxStatus(failedId));
        assertEquals(failedLease, outboxLockToken(failedId));

        assertFalse(outboxStatusRepository.markFailed(
            failedId, "stale-worker", "failed", nextAttemptAt, "stale failure", completedAt
        ));
        assertEquals("processing", outboxStatus(failedId));
        assertEquals(failedLease, outboxLockToken(failedId));

        assertTrue(outboxStatusRepository.markFailed(
            failedId, failedLease, "failed", nextAttemptAt, "delivery failed", completedAt
        ));
        assertEquals("failed", outboxStatus(failedId));
        assertNull(outboxLockToken(failedId));
        assertEquals("delivery failed", jdbc.queryForObject(
            "SELECT last_error FROM outbox_events WHERE id = ?",
            String.class,
            failedId
        ));
    }

    @Test
    void notificationTerminalTransitionsRequireTheCurrentDeliveryLease() {
        String deliveredLease = "delivered-owner-" + UUID.randomUUID();
        long deliveredId = processingNotificationDelivery(deliveredLease);
        String completedAt = OffsetDateTime.now().toString();

        assertFalse(notificationDeliveryStatusRepository.markDelivered(
            deliveredId,
            "stale-worker",
            completedAt
        ));
        assertEquals("processing", notificationDeliveryStatus(deliveredId));
        assertEquals(deliveredLease, notificationDeliveryLockToken(deliveredId));

        assertTrue(notificationDeliveryStatusRepository.markDelivered(deliveredId, deliveredLease, completedAt));
        assertEquals("delivered", notificationDeliveryStatus(deliveredId));
        assertNull(notificationDeliveryLockToken(deliveredId));
        assertNotNull(jdbc.queryForObject(
            "SELECT delivered_at FROM notification_deliveries WHERE id = ?",
            String.class,
            deliveredId
        ));

        String failedLease = "failed-owner-" + UUID.randomUUID();
        long failedId = processingNotificationDelivery(failedLease);
        String nextAttemptAt = OffsetDateTime.now().plusMinutes(1).toString();

        assertThrows(IllegalArgumentException.class, () -> notificationDeliveryStatusRepository.markFailed(
            failedId,
            failedLease,
            "delivered",
            nextAttemptAt,
            "invalid transition",
            completedAt
        ));
        assertEquals("processing", notificationDeliveryStatus(failedId));
        assertEquals(failedLease, notificationDeliveryLockToken(failedId));

        assertFalse(notificationDeliveryStatusRepository.markFailed(
            failedId,
            "stale-worker",
            "failed",
            nextAttemptAt,
            "stale failure",
            completedAt
        ));
        assertEquals("processing", notificationDeliveryStatus(failedId));
        assertEquals(failedLease, notificationDeliveryLockToken(failedId));

        assertTrue(notificationDeliveryStatusRepository.markFailed(
            failedId,
            failedLease,
            "failed",
            nextAttemptAt,
            "webhook failed",
            completedAt
        ));
        assertEquals("failed", notificationDeliveryStatus(failedId));
        assertNull(notificationDeliveryLockToken(failedId));
        assertEquals("webhook failed", jdbc.queryForObject(
            "SELECT last_error FROM notification_deliveries WHERE id = ?",
            String.class,
            failedId
        ));
    }

    @Test
    void scheduledJobLeaseAllowsOneContenderAndFencesAnExpiredOwner() throws Exception {
        String jobName = "integration-reminders-" + UUID.randomUUID();
        String initialLease = scheduledJobLeases.tryAcquire(jobName, 600_000).orElseThrow();

        assertTrue(scheduledJobLeases.tryAcquire(jobName, 600_000).isEmpty());
        assertFalse(scheduledJobLeases.markCompleted(jobName, "stale-worker", 60_000));
        assertTrue(scheduledJobLeases.markCompleted(jobName, initialLease, 60_000));
        assertTrue(scheduledJobLeases.tryAcquire(jobName, 600_000).isEmpty());

        jdbc.update("""
            UPDATE scheduled_job_leases
            SET next_run_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds'
            WHERE job_name = ?
            """, jobName);
        CompletableFuture<Optional<String>> first = CompletableFuture.supplyAsync(
            () -> scheduledJobLeases.tryAcquire(jobName, 600_000)
        );
        CompletableFuture<Optional<String>> second = CompletableFuture.supplyAsync(
            () -> scheduledJobLeases.tryAcquire(jobName, 600_000)
        );
        List<String> contenderLeases = List.of(
            first.get(10, TimeUnit.SECONDS),
            second.get(10, TimeUnit.SECONDS)
        ).stream().flatMap(Optional::stream).toList();
        assertEquals(1, contenderLeases.size());
        String expiredLease = contenderLeases.get(0);

        jdbc.update("""
            UPDATE scheduled_job_leases
            SET last_started_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second',
                next_run_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds'
            WHERE job_name = ?
            """, jobName);
        String replacementLease = scheduledJobLeases.tryAcquire(jobName, 600_000).orElseThrow();
        assertNotEquals(expiredLease, replacementLease);
        assertFalse(scheduledJobLeases.markCompleted(jobName, expiredLease, 60_000));
        assertTrue(scheduledJobLeases.markFailed(jobName, replacementLease, 60_000, "scan failed"));

        Map<String, Object> failedLease = jdbc.queryForMap("""
            SELECT lock_token, locked_until, last_error
            FROM scheduled_job_leases
            WHERE job_name = ?
            """, jobName);
        assertNull(failedLease.get("lock_token"));
        assertNull(failedLease.get("locked_until"));
        assertEquals("scan failed", failedLease.get("last_error"));
        assertNotNull(jdbc.queryForObject("""
            SELECT last_failed_at FROM scheduled_job_leases WHERE job_name = ?
            """, java.time.OffsetDateTime.class, jobName));
    }

    private long approvalId(ApiResponse response) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> approval = (Map<String, Object>) parseMap(response.body()).get("request");
        return ((Number) approval.get("id")).longValue();
    }

    private void assertValidationFields(ApiResponse response, Set<String> expectedFields) throws Exception {
        assertEquals(400, response.status(), response.body());
        Map<String, Object> problem = parseMap(response.body());
        assertEquals("validation_failed", problem.get("code"));
        assertTrue(problem.get("fields") instanceof Map<?, ?>, response.body());
        Map<?, ?> fields = (Map<?, ?>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(expectedFields), response.body());
    }

    private Connection holdApprovalLease(String leaseKey) throws Exception {
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(1, leaseKey);
                statement.executeQuery().close();
            }
            return connection;
        } catch (Exception ex) {
            connection.close();
            throw ex;
        }
    }

    private void createEmployee(String token, long companyId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyId", companyId);
        body.put("name", "Payroll employee");
        body.put("email", "payroll-" + System.nanoTime() + "@example.invalid");
        body.put("position", "Engineer");
        body.put("employmentType", "full_time");
        body.put("status", "active");
        body.put("accessRole", "employee");
        body.put("accessScope", "self");
        body.put("hireDate", "2026-07-01");
        body.put("salary", 10000);
        ApiResponse response = request("POST", "/api/v1/enterprise/employees", body, token);
        assertEquals(200, response.status(), response.body());
    }

    private long processingOutboxEvent(String lockToken) {
        String now = OffsetDateTime.now().toString();
        return jdbc.queryForObject("""
            INSERT INTO outbox_events (
                event_id, event_type, aggregate_type, aggregate_id, company_id, actor_user_id,
                payload_json, status, attempts, next_attempt_at, locked_at, lock_token,
                processed_at, last_error, created_at, updated_at
            ) VALUES (?, 'test.delivery.lease', 'test', 1, 0, 0, '{}', 'processing', 1, NULL, ?, ?, NULL, NULL, ?, ?)
            RETURNING id
            """, Long.class, UUID.randomUUID().toString(), now, lockToken, now, now);
    }

    private String outboxStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }

    private String outboxLockToken(long id) {
        return jdbc.queryForObject("SELECT lock_token FROM outbox_events WHERE id = ?", String.class, id);
    }

    private long processingNotificationDelivery(String lockToken) {
        String now = OffsetDateTime.now().toString();
        long notificationId = jdbc.queryForObject(
            "SELECT nextval(pg_get_serial_sequence('notifications', 'id'))",
            Long.class
        );
        return jdbc.queryForObject("""
            INSERT INTO notification_deliveries (
                notification_id, user_id, channel, provider, status, attempts,
                next_attempt_at, locked_at, lock_token, delivered_at, last_error,
                response_status, created_at, updated_at
            ) VALUES (
                ?, 1, 'webhook', 'generic', 'processing', 1,
                NULL, ?, ?, NULL, NULL, NULL, ?, ?
            ) RETURNING id
            """, Long.class, notificationId, now, lockToken, now, now);
    }

    private String notificationDeliveryStatus(long id) {
        return jdbc.queryForObject(
            "SELECT status FROM notification_deliveries WHERE id = ?",
            String.class,
            id
        );
    }

    private String notificationDeliveryLockToken(long id) {
        return jdbc.queryForObject(
            "SELECT lock_token FROM notification_deliveries WHERE id = ?",
            String.class,
            id
        );
    }
}
