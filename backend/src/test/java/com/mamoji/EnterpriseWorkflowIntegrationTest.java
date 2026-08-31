package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.notification.infrastructure.OutboxEventStatusRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    void globalSearchReturnsCompanyScopedBusinessRecords() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Search Flow " + System.nanoTime());
        String needle = "Needle" + System.nanoTime();
        createAccount(token, companyId, needle, "1234");

        ApiResponse response = request("GET", "/api/v1/search?companyId=" + companyId + "&keyword=" + needle, null, token);
        assertEquals(200, response.status(), response.body());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) parseMap(response.body()).get("results");
        assertTrue(results.stream().anyMatch(result -> "account".equals(result.get("type")) && needle.equals(result.get("title"))));
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
        assertEquals(LocalDate.now().toString(), state.get("last_executed"));
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
}
