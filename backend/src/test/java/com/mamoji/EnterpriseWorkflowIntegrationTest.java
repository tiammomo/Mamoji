package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EnterpriseWorkflowIntegrationTest extends AbstractPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

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
}
