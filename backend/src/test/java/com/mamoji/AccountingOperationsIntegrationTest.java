package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.operations.domain.TransactionRecord;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountingOperationsIntegrationTest extends AbstractPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void crossOwnerTransactionIsRejectedWithoutChangingBalanceOrCreatingData() throws Exception {
        String memberToken = registerInvitedUser(uniqueEmail("account-owner"));
        long companyId = createCompany(memberToken, "Owner Scope " + System.nanoTime());
        Map<String, Object> account = createAccount(memberToken, companyId, "Member account", "1000");
        Map<String, Object> category = createCategory(memberToken, companyId, "Member expense", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();

        createTransaction(memberToken, companyId, account, category, "5");
        long beforeCount = transactionCount(memberToken, companyId);
        Map<String, Object> beforeAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            memberToken
        ).body());
        BigDecimal beforeBalance = decimal(beforeAccount.get("balance"));
        String adminToken = text(login("test@mamoji.com", "123456").get("token"));
        assertEquals(0, transactionCount(adminToken, companyId), "Accounting data is intentionally scoped by both company and user");
        ApiResponse rejected = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 120,
            "accountId", accountId,
            "categoryId", categoryId,
            "date", "2026-07-14",
            "note", "must be rejected"
        ), adminToken);

        assertEquals(403, rejected.status(), rejected.body());
        assertEquals(beforeCount, transactionCount(memberToken, companyId));
        Map<String, Object> afterAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            memberToken
        ).body());
        assertEquals(0, beforeBalance.compareTo(decimal(afterAccount.get("balance"))));
    }

    @Test
    void transactionCreateValidationReturnsProblemDetailBeforeWriting() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));

        ApiResponse response = request("POST", "/api/v1/transactions", Map.of(
            "type", 3,
            "amount", -1,
            "categoryId", 0
        ), token);

        assertEquals(400, response.status(), response.body());
        Map<String, Object> problem = parseMap(response.body());
        assertEquals("validation_failed", problem.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(Set.of("type", "amount", "categoryId", "accountId")));
    }

    @Test
    void transactionIdempotencyHeaderReplaysWithoutDoubleDeduction() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Idempotent transaction " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Idempotent account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Idempotent expense", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        Map<String, Object> command = Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 25,
            "accountId", accountId,
            "categoryId", category.get("id"),
            "date", "2026-07-14",
            "note", "idempotent transaction"
        );
        Map<String, String> headers = Map.of("Idempotency-Key", "tx-create-" + System.nanoTime());

        ApiResponse first = request("POST", "/api/v1/transactions", command, token, headers);
        ApiResponse second = request("POST", "/api/v1/transactions", command, token, headers);
        Map<String, Object> conflictingCommand = new java.util.LinkedHashMap<>(command);
        conflictingCommand.put("amount", 50);
        ApiResponse conflict = request("POST", "/api/v1/transactions", conflictingCommand, token, headers);

        assertEquals(200, first.status(), first.body());
        assertEquals(200, second.status(), second.body());
        assertEquals(409, conflict.status(), conflict.body());
        assertEquals(Boolean.TRUE, parseMap(second.body()).get("replayed"));
        assertEquals(1, transactionCount(token, companyId));
        Map<String, Object> updatedAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            token
        ).body());
        assertEquals(0, new BigDecimal("975").compareTo(decimal(updatedAccount.get("balance"))));
    }

    @Test
    void transactionCsvImportUsesTheOperationsWriteBoundary() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "CSV operations boundary " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "CSV account", "1000");
        createCategory(token, companyId, "CSV expense", "expense");
        String csv = "date,type,amount,category,account,note\n"
            + LocalDate.now().minusDays(1) + ",expense,12.34,CSV expense,CSV account,office supplies\n";
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "transactions.csv",
            "text/csv",
            csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        Map<String, Object> result = transactionImportService.commit(
            "Bearer " + token,
            file,
            companyId,
            true
        );

        assertEquals(1, result.get("importedRows"));
        assertEquals(1, transactionCount(token, companyId));
        Map<String, Object> updatedAccount = parseMap(request(
            "GET",
            "/api/v1/accounts/" + account.get("id") + "?companyId=" + companyId,
            null,
            token
        ).body());
        assertEquals(0, new BigDecimal("987.66").compareTo(decimal(updatedAccount.get("balance"))));
    }

    @Test
    void accountingQueriesAndStatisticsAreIsolatedByCompany() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyA = createCompany(token, "Scope A " + System.nanoTime());
        long companyB = createCompany(token, "Scope B " + System.nanoTime());
        Map<String, Object> accountA = createAccount(token, companyA, "A account", "1000");
        Map<String, Object> accountB = createAccount(token, companyB, "B account", "2000");
        Map<String, Object> categoryA = createCategory(token, companyA, "A expense", "expense");
        Map<String, Object> categoryB = createCategory(token, companyB, "B expense", "expense");

        Map<String, Object> transactionA = createTransaction(token, companyA, accountA, categoryA, "11");
        createTransaction(token, companyB, accountB, categoryB, "29");

        @SuppressWarnings("unchecked")
        Map<String, Object> riskA = (Map<String, Object>) transactionA.get("risk");
        assertEquals(0, new BigDecimal("11").compareTo(decimal(riskA.get("monthlyExpense"))),
            "Risk assessment must include the transaction being created before its cache entry is committed");

        Map<String, Object> statsA = parseMap(request("GET", "/api/v1/stats/overview?month=2026-07&companyId=" + companyA, null, token).body());
        Map<String, Object> statsB = parseMap(request("GET", "/api/v1/stats/overview?month=2026-07&companyId=" + companyB, null, token).body());
        assertEquals(0, new BigDecimal("11").compareTo(decimal(statsA.get("monthlyExpense"))));
        assertEquals(0, new BigDecimal("29").compareTo(decimal(statsB.get("monthlyExpense"))));
        assertEquals(1, transactionCount(token, companyA));
        assertEquals(1, transactionCount(token, companyB));

        List<Map<String, Object>> accountsA = parseList(request("GET", "/api/v1/accounts?companyId=" + companyA, null, token).body());
        assertTrue(accountsA.stream().allMatch(item -> ((Number) item.get("companyId")).longValue() == companyA));
        assertFalse(accountsA.stream().anyMatch(item -> ((Number) item.get("id")).longValue() == ((Number) accountB.get("id")).longValue()));
    }

    @Test
    void accountMetadataUpdateUsesLockedDatabaseBalanceInsteadOfStaleCache() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Balance Lock " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Lock account", "1000");
        long accountId = ((Number) account.get("id")).longValue();
        jdbc.update("UPDATE accounts SET balance = '875', available_balance = '875' WHERE id = ?", accountId);

        ApiResponse updated = request(
            "PUT",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            Map.of("name", "Renamed without balance loss"),
            token
        );
        assertEquals(200, updated.status(), updated.body());
        Map<String, Object> updatedAccount = parseMap(updated.body());
        assertEquals(0, new BigDecimal("875").compareTo(decimal(updatedAccount.get("balance"))));
        assertEquals("875", jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", String.class, accountId));
    }

    @Test
    void financeWritesSynchronizeAccountLedgerAndMembershipCompatibilityViews() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long userId = ((Number) parseMap(request("GET", "/api/v1/auth/me", null, token).body()).get("id"))
            .longValue();
        long companyId = createCompany(token, "Finance boundary " + System.nanoTime());

        ApiResponse ledgerResponse = request("POST", "/api/v1/ledgers", Map.of(
            "companyId", companyId,
            "name", "Finance-owned ledger",
            "currency", "CNY"
        ), token);
        assertEquals(200, ledgerResponse.status(), ledgerResponse.body());
        long ledgerId = ((Number) parseMap(ledgerResponse.body()).get("id")).longValue();
        assertEquals("Finance-owned ledger", coreStore.ledgers.get(ledgerId).name);
        assertTrue(coreStore.ledgerMembers.values().stream()
            .anyMatch(member -> member.ledgerId == ledgerId && member.userId == userId));

        Map<String, Object> created = createAccount(token, companyId, "Finance-owned account", "1000");
        long accountId = ((Number) created.get("id")).longValue();
        assertEquals(1, coreStore.accounts.get(accountId).version);

        ApiResponse updated = request(
            "PUT",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            Map.of("name", "Finance-owned account updated"),
            token
        );
        assertEquals(200, updated.status(), updated.body());
        assertEquals(2, coreStore.accounts.get(accountId).version);
        assertEquals("Finance-owned account updated", coreStore.accounts.get(accountId).name);

        ApiResponse deleted = request(
            "DELETE",
            "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
            null,
            token
        );
        assertEquals(200, deleted.status(), deleted.body());
        assertFalse(coreStore.accounts.containsKey(accountId));

        ApiResponse memberDeleted = request(
            "DELETE",
            "/api/v1/ledgers/" + ledgerId + "/members/" + userId,
            null,
            token
        );
        assertEquals(200, memberDeleted.status(), memberDeleted.body());
        assertFalse(coreStore.ledgerMembers.values().stream()
            .anyMatch(member -> member.ledgerId == ledgerId && member.userId == userId));
    }

    @Test
    void accountDeletionChecksCommittedDatabaseReferencesEvenWhenCacheEntryIsMissing() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Reference Lock " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Referenced account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Referenced expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, account, category, "10");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");
        long transactionId = ((Number) transaction.get("id")).longValue();
        long accountId = ((Number) account.get("id")).longValue();
        TransactionRecord cached = coreStore.transactions.remove(transactionId);
        try {
            ApiResponse deleted = request(
                "DELETE",
                "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
                null,
                token
            );
            assertEquals(409, deleted.status(), deleted.body());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId));
        } finally {
            if (cached != null) {
                coreStore.transactions.put(transactionId, cached);
            }
        }
    }

    @Test
    void concurrentTransactionWinsBeforeAccountDeletionAndPreventsOrphan() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Concurrent Account Delete " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Concurrent account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Concurrent category", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();

        try (Connection blocker = lockRow("SELECT id FROM categories WHERE id = ? FOR UPDATE", categoryId)) {
            CompletableFuture<ApiResponse> create = requestAsync("POST", "/api/v1/transactions", Map.of(
                "companyId", companyId,
                "type", 2,
                "amount", 10,
                "accountId", accountId,
                "categoryId", categoryId,
                "date", "2026-07-14",
                "note", "concurrent account delete"
            ), token);
            awaitBlockedQuery("categories");
            CompletableFuture<ApiResponse> delete = requestAsync(
                "DELETE",
                "/api/v1/accounts/" + accountId + "?companyId=" + companyId,
                null,
                token
            );
            blocker.commit();

            ApiResponse created = create.get(10, TimeUnit.SECONDS);
            ApiResponse deleted = delete.get(10, TimeUnit.SECONDS);
            assertEquals(200, created.status(), created.body());
            assertEquals(409, deleted.status(), deleted.body());
        }
        assertEquals(1, transactionCount(token, companyId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE id = ?", Integer.class, accountId));
    }

    @Test
    void concurrentCategoryDeletionWinsBeforeTransactionAndPreventsOrphan() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Concurrent Category Delete " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Blocked account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Deleted category", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();
        assertEquals("Deleted category", coreStore.categories.get(categoryId).name);

        CompletableFuture<ApiResponse> create;
        try (Connection blocker = lockRow("SELECT id FROM accounts WHERE id = ? FOR UPDATE", accountId)) {
            create = requestAsync("POST", "/api/v1/transactions", Map.of(
                "companyId", companyId,
                "type", 2,
                "amount", 10,
                "accountId", accountId,
                "categoryId", categoryId,
                "date", "2026-07-14",
                "note", "concurrent category delete"
            ), token);
            awaitBlockedQuery("accounts");
            ApiResponse deleted = request(
                "DELETE",
                "/api/v1/categories/" + categoryId + "?companyId=" + companyId,
                null,
                token
            );
            assertEquals(200, deleted.status(), deleted.body());
            blocker.commit();
        }

        ApiResponse created = create.get(10, TimeUnit.SECONDS);
        assertEquals(400, created.status(), created.body());
        assertEquals(0, transactionCount(token, companyId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id = ?", Integer.class, categoryId));
        assertFalse(coreStore.categories.containsKey(categoryId));
    }

    @Test
    void refundedTransactionCannotMoveToAnotherAccount() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Refund Edit " + System.nanoTime());
        Map<String, Object> accountA = createAccount(token, companyId, "Refund source", "1000");
        Map<String, Object> accountB = createAccount(token, companyId, "Refund target", "500");
        Map<String, Object> category = createCategory(token, companyId, "Refund edit expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, accountA, category, "100");
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) created.get("transaction");
        long originalId = ((Number) original.get("id")).longValue();
        assertEquals(200, request("POST", "/api/v1/transactions/" + originalId + "/refund", Map.of(
            "companyId", companyId,
            "amount", 30,
            "date", "2026-07-14"
        ), token).status());

        ApiResponse moved = request(
            "PUT",
            "/api/v1/transactions/" + originalId + "?companyId=" + companyId,
            Map.of("accountId", accountB.get("id")),
            token
        );
        assertEquals(409, moved.status(), moved.body());
        assertAccountBalances(token, companyId, ((Number) accountA.get("id")).longValue(), "930", "930");
        assertAccountBalances(token, companyId, ((Number) accountB.get("id")).longValue(), "500", "500");
    }

    @Test
    void transactionUpdateValidatesTypedFieldsBeforeChangingAccountingData() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Typed Transaction Update " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Typed update account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Typed update expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, account, category, "25");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");
        long transactionId = ((Number) transaction.get("id")).longValue();

        ApiResponse invalid = request(
            "PUT",
            "/api/v1/transactions/" + transactionId + "?companyId=" + companyId,
            Map.of(
                "amount", 0,
                "categoryId", 0,
                "accountId", 0,
                "note", "x".repeat(2001)
            ),
            token
        );

        assertEquals(400, invalid.status(), invalid.body());
        Map<String, Object> problem = parseMap(invalid.body());
        assertEquals("validation_failed", problem.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(Set.of("amount", "categoryId", "accountId", "note")));
        assertEquals("25", jdbc.queryForObject(
            "SELECT amount FROM transactions WHERE id = ?", String.class, transactionId
        ));
    }

    @Test
    void transactionRefundValidatesTypedFieldsBeforeChangingAccountingData() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Typed Transaction Refund " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Typed refund account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Typed refund expense", "expense");
        Map<String, Object> created = createTransaction(token, companyId, account, category, "25");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");
        long transactionId = ((Number) transaction.get("id")).longValue();

        ApiResponse invalid = request(
            "POST",
            "/api/v1/transactions/" + transactionId + "/refund",
            Map.of(
                "companyId", 0,
                "amount", 0,
                "note", "x".repeat(2001)
            ),
            token
        );

        assertEquals(400, invalid.status(), invalid.body());
        Map<String, Object> problem = parseMap(invalid.body());
        assertEquals("validation_failed", problem.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(Set.of("companyId", "amount", "note")));

        ApiResponse invalidDate = request(
            "POST",
            "/api/v1/transactions/" + transactionId + "/refund?companyId=" + companyId,
            Map.of("amount", 10, "date", "2026-07-13"),
            token
        );
        assertEquals(409, invalidDate.status(), invalidDate.body());
        assertEquals("0", jdbc.queryForObject(
            "SELECT refunded_amount FROM transactions WHERE id = ?", String.class, transactionId
        ));
        assertAccountBalances(
            token,
            companyId,
            ((Number) account.get("id")).longValue(),
            "975",
            "975"
        );
    }

    @Test
    void transactionQueriesValidateFiltersAndAggregateInPostgres() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Typed Transaction Query " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Query projection account", "20000");
        Map<String, Object> incomeCategory = createCategory(token, companyId, "Query income", "income");
        Map<String, Object> refundCategory = createCategory(token, companyId, "客户退款", "expense");
        Map<String, Object> severanceCategory = createCategory(token, companyId, "离职补偿", "expense");

        ApiResponse incomeResponse = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 1,
            "amount", 500,
            "accountId", account.get("id"),
            "categoryId", incomeCategory.get("id"),
            "date", "2026-07-12",
            "note", "pending-project 尾款待回款"
        ), token);
        assertEquals(200, incomeResponse.status(), incomeResponse.body());
        ApiResponse customerRefundResponse = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 50,
            "accountId", account.get("id"),
            "categoryId", refundCategory.get("id"),
            "date", "2026-07-13",
            "note", "订单退款给客户"
        ), token);
        assertEquals(200, customerRefundResponse.status(), customerRefundResponse.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> customerRefundTransaction = (Map<String, Object>) parseMap(
            customerRefundResponse.body()
        ).get("transaction");
        ApiResponse severanceResponse = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 12000,
            "accountId", account.get("id"),
            "categoryId", severanceCategory.get("id"),
            "date", "2026-07-14",
            "note", "离职补偿 N+1"
        ), token);
        assertEquals(200, severanceResponse.status(), severanceResponse.body());
        ApiResponse refundResponse = request(
            "POST",
            "/api/v1/transactions/" + customerRefundTransaction.get("id") + "/refund",
            Map.of(
                "companyId", companyId,
                "amount", 20,
                "date", "2026-07-14",
                "note", "部分退款到账"
            ),
            token
        );
        assertEquals(200, refundResponse.status(), refundResponse.body());

        ApiResponse summaryResponse = request(
            "GET", "/api/v1/transactions/summary?companyId=" + companyId, null, token
        );
        assertEquals(200, summaryResponse.status(), summaryResponse.body());
        Map<String, Object> summary = parseMap(summaryResponse.body());
        assertEquals(0, new BigDecimal("500").compareTo(decimal(summary.get("income"))));
        assertEquals(0, new BigDecimal("12050").compareTo(decimal(summary.get("expense"))));
        assertEquals(0, new BigDecimal("20").compareTo(decimal(summary.get("refund"))));
        assertEquals(0, new BigDecimal("500").compareTo(decimal(summary.get("pendingCollection"))));
        assertEquals(0, new BigDecimal("50").compareTo(decimal(summary.get("customerRefund"))));
        assertEquals(0, new BigDecimal("12000").compareTo(decimal(summary.get("severance"))));
        assertEquals(0, new BigDecimal("450").compareTo(decimal(summary.get("netCollectedIncome"))));
        assertEquals(0, new BigDecimal("-11530").compareTo(decimal(summary.get("net"))));
        assertEquals(4, ((Number) summary.get("rows")).intValue());
        assertEquals(1, ((Number) summary.get("largeCount")).intValue());
        assertEquals(3, ((Number) summary.get("reviewCount")).intValue());

        String filteredPath = "/api/v1/transactions?companyId=" + companyId
            + "&type=1&startDate=2026-07-11&endDate=2026-07-13"
            + "&minAmount=40&maxAmount=600&keyword=pending&page=0&size=10";
        ApiResponse filteredResponse = request("GET", filteredPath, null, token);
        assertEquals(200, filteredResponse.status(), filteredResponse.body());
        Map<String, Object> filtered = parseMap(filteredResponse.body());
        assertEquals(1, ((Number) filtered.get("totalElements")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) filtered.get("content");
        assertEquals("pending-project 尾款待回款", content.getFirst().get("note"));

        Map<String, Object> clampedPage = parseMap(request(
            "GET", "/api/v1/transactions?companyId=" + companyId + "&size=1000", null, token
        ).body());
        assertEquals(200, ((Number) clampedPage.get("size")).intValue());

        ApiResponse invalid = request(
            "GET",
            "/api/v1/transactions?companyId=" + companyId
                + "&type=4&categoryId=0&minAmount=-1&keyword=" + "x".repeat(201),
            null,
            token
        );
        assertEquals(400, invalid.status(), invalid.body());
        Map<String, Object> problem = parseMap(invalid.body());
        assertEquals("validation_failed", problem.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) problem.get("fields");
        assertTrue(fields.keySet().containsAll(Set.of("type", "categoryId", "minAmount", "keyword")));

        ApiResponse reversedRange = request(
            "GET",
            "/api/v1/transactions/summary?companyId=" + companyId
                + "&startDate=2026-07-15&endDate=2026-07-01",
            null,
            token
        );
        assertEquals(400, reversedRange.status(), reversedRange.body());
    }

    @Test
    void accountReconciliationCreatesImmutableSnapshotAndUpdatesRiskState() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Reconciliation Flow " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Statement account", "1000");
        long accountId = ((Number) account.get("id")).longValue();

        ApiResponse reconciled = request("POST", "/api/v1/accounts/" + accountId + "/reconciliations?companyId=" + companyId, Map.of(
            "statementDate", "2026-07-14",
            "statementBalance", 990,
            "note", "Bank statement"
        ), token);
        assertEquals(200, reconciled.status(), reconciled.body());
        Map<String, Object> snapshot = parseMap(reconciled.body());
        assertEquals("exception", snapshot.get("status"));
        assertEquals(0, new BigDecimal("-10.00").compareTo(decimal(snapshot.get("difference"))));

        ApiResponse current = request("GET", "/api/v1/accounts/" + accountId + "?companyId=" + companyId, null, token);
        assertEquals("exception", parseMap(current.body()).get("reconciliationStatus"));
        ApiResponse bypass = request("PUT", "/api/v1/accounts/" + accountId + "?companyId=" + companyId, Map.of(
            "reconciliationStatus", "reconciled"
        ), token);
        assertEquals(400, bypass.status(), bypass.body());
    }

    @Test
    void refundAndRefundDeletionKeepBalancesBudgetsAndOriginalTransactionConsistent() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Refund Scope " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Refund account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Refund expense", "expense");
        long accountId = ((Number) account.get("id")).longValue();
        long categoryId = ((Number) category.get("id")).longValue();
        ApiResponse budgetResponse = request("POST", "/api/v1/budgets", Map.of(
            "companyId", companyId,
            "name", "Refund budget",
            "amount", 100,
            "categoryId", categoryId,
            "startDate", "2026-07-01",
            "endDate", "2026-07-31",
            "warningThreshold", 85
        ), token);
        assertEquals(200, budgetResponse.status(), budgetResponse.body());

        Map<String, Object> created = createTransaction(token, companyId, account, category, "80");
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) created.get("transaction");
        long originalId = ((Number) original.get("id")).longValue();
        assertAccountBalances(token, companyId, accountId, "920", "920");
        assertEquals(0, new BigDecimal("80").compareTo(budgetSpent(token, companyId)));
        List<Map<String, Object>> initiallyRefundable = parseList(request(
            "GET", "/api/v1/transactions/refundable?companyId=" + companyId, null, token
        ).body());
        assertEquals(List.of(originalId), initiallyRefundable.stream()
            .map(transaction -> ((Number) transaction.get("id")).longValue())
            .toList());

        ApiResponse refundResponse = request("POST", "/api/v1/transactions/" + originalId + "/refund", Map.of(
            "companyId", companyId,
            "amount", 30,
            "date", "2026-07-15"
        ), token);
        assertEquals(200, refundResponse.status(), refundResponse.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> refund = (Map<String, Object>) parseMap(refundResponse.body()).get("transaction");
        long refundId = ((Number) refund.get("id")).longValue();
        assertAccountBalances(token, companyId, accountId, "950", "950");
        assertEquals(0, new BigDecimal("50").compareTo(budgetSpent(token, companyId)));
        Map<String, Object> afterRefundOriginal = parseMap(request(
            "GET", "/api/v1/transactions/" + originalId + "?companyId=" + companyId, null, token
        ).body());
        assertEquals(0, new BigDecimal("30").compareTo(decimal(afterRefundOriginal.get("refundedAmount"))));
        assertEquals(1, parseList(request(
            "GET", "/api/v1/transactions/refundable?companyId=" + companyId, null, token
        ).body()).size());

        ApiResponse deleted = request("DELETE", "/api/v1/transactions/" + refundId + "?companyId=" + companyId, null, token);
        assertEquals(200, deleted.status(), deleted.body());
        assertAccountBalances(token, companyId, accountId, "920", "920");
        assertEquals(0, new BigDecimal("80").compareTo(budgetSpent(token, companyId)));
        Map<String, Object> afterDeleteOriginal = parseMap(request(
            "GET", "/api/v1/transactions/" + originalId + "?companyId=" + companyId, null, token
        ).body());
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(afterDeleteOriginal.get("refundedAmount"))));
        assertTrue(Boolean.TRUE.equals(afterDeleteOriginal.get("isRefundable")));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE entity_type = 'transaction' AND entity_id = ? AND action IN ('refund', 'delete')
            """, Integer.class, refundId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type = 'transaction' AND aggregate_id = ?
              AND event_type IN ('accounting.transaction.refund', 'accounting.transaction.delete')
            """, Integer.class, refundId));
    }

    @Test
    void transactionUpdateAndDeletionReallocateConfirmedBudgetCapacity() throws Exception {
        String token = text(login("test@mamoji.com", "123456").get("token"));
        long companyId = createCompany(token, "Budget Reallocation " + System.nanoTime());
        Map<String, Object> account = createAccount(token, companyId, "Reallocation account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Reallocation expense", "expense");
        ApiResponse budgetResponse = request("POST", "/api/v1/budgets", Map.of(
            "companyId", companyId,
            "name", "Reallocation budget",
            "amount", 100,
            "categoryId", category.get("id"),
            "startDate", "2026-07-01",
            "endDate", "2026-07-31",
            "warningThreshold", 85
        ), token);
        assertEquals(200, budgetResponse.status(), budgetResponse.body());
        long budgetId = ((Number) parseMap(budgetResponse.body()).get("id")).longValue();

        Map<String, Object> created = createTransaction(token, companyId, account, category, "70");
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) created.get("transaction");
        long transactionId = ((Number) transaction.get("id")).longValue();
        assertEquals(1, coreStore.transactions.get(transactionId).version);
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE transaction_id = ? AND status = 'confirmed'",
            Integer.class,
            transactionId
        ));

        ApiResponse overCapacity = request(
            "PUT",
            "/api/v1/transactions/" + transactionId + "?companyId=" + companyId,
            Map.of("amount", 110),
            token
        );
        assertEquals(409, overCapacity.status(), overCapacity.body());
        assertEquals("70", jdbc.queryForObject(
            "SELECT amount FROM transactions WHERE id = ?",
            String.class,
            transactionId
        ));

        ApiResponse updated = request(
            "PUT",
            "/api/v1/transactions/" + transactionId + "?companyId=" + companyId,
            Map.of("amount", 80),
            token
        );
        assertEquals(200, updated.status(), updated.body());
        assertEquals(2, coreStore.transactions.get(transactionId).version);
        assertEquals(0, new BigDecimal("80").compareTo(coreStore.transactions.get(transactionId).amount));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE budget_id = ? AND status = 'released'",
            Integer.class,
            budgetId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE transaction_id = ? AND status = 'confirmed'",
            Integer.class,
            transactionId
        ));

        ApiResponse deleted = request(
            "DELETE",
            "/api/v1/transactions/" + transactionId + "?companyId=" + companyId,
            null,
            token
        );
        assertEquals(200, deleted.status(), deleted.body());
        assertFalse(coreStore.transactions.containsKey(transactionId));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE transaction_id = ?",
            Integer.class,
            transactionId
        ));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE budget_id = ? AND status = 'released'",
            Integer.class,
            budgetId
        ));
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE source_transaction_id = ?",
            Integer.class,
            transactionId
        ));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_logs
            WHERE entity_type = 'transaction' AND entity_id = ? AND action IN ('update', 'delete')
            """, Integer.class, transactionId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE aggregate_type = 'transaction' AND aggregate_id = ?
              AND event_type IN ('accounting.transaction.update', 'accounting.transaction.delete')
            """, Integer.class, transactionId));
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetSpent(token, companyId)));
    }

    @Test
    void reconciliationUsesLockedDatabaseBalanceInsteadOfStaleReadModel() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Reconciliation consistency");
        Map<String, Object> account = createAccount(token, companyId, "Authoritative balance", "1000");
        long accountId = id(account);
        jdbc.update("UPDATE accounts SET balance = '875', available_balance = '875' WHERE id = ?", accountId);

        ApiResponse response = request("POST", "/api/v1/accounts/" + accountId
            + "/reconciliations?companyId=" + companyId, Map.of(
            "statementDate", LocalDate.now().toString(),
            "statementBalance", 875
        ), token);

        assertEquals(200, response.status(), response.body());
        Map<String, Object> record = parseMap(response.body());
        assertEquals("reconciled", record.get("status"));
        assertEquals(0, new BigDecimal("875.00").compareTo(decimal(record.get("systemBalance"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(decimal(record.get("difference"))));
    }

    @Test
    void transactionSummaryReadsDatabaseWhenCompatibilityCacheEntryIsMissing() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Database summary");
        Map<String, Object> account = createAccount(token, companyId, "Summary account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Summary expense", "expense");
        ApiResponse created = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 42,
            "accountId", id(account),
            "categoryId", id(category),
            "date", LocalDate.now().toString(),
            "note", "database-summary-row"
        ), token);
        assertEquals(200, created.status(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> transaction = (Map<String, Object>) parseMap(created.body()).get("transaction");
        long transactionId = id(transaction);
        TransactionRecord cached = coreStore.transactions.remove(transactionId);
        try {
            ApiResponse summary = request(
                "GET",
                "/api/v1/transactions/summary?companyId=" + companyId + "&keyword=database-summary-row",
                null,
                token
            );
            assertEquals(200, summary.status(), summary.body());
            Map<String, Object> totals = parseMap(summary.body());
            assertEquals(1, ((Number) totals.get("rows")).intValue());
            assertEquals(0, new BigDecimal("42").compareTo(decimal(totals.get("expense"))));
            assertEquals(1, ((Number) totals.get("reviewCount")).intValue());
        } finally {
            if (cached != null) {
                coreStore.transactions.put(transactionId, cached);
            }
        }
    }

    @Test
    void concurrentExpensesCannotConsumeMoreThanOneSharedBudget() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent budget capacity");
        Map<String, Object> firstAccount = createAccount(token, companyId, "Budget account A", "1000");
        Map<String, Object> secondAccount = createAccount(token, companyId, "Budget account B", "1000");
        Map<String, Object> firstCategory = createCategory(token, companyId, "Budget category A", "expense");
        Map<String, Object> secondCategory = createCategory(token, companyId, "Budget category B", "expense");
        ApiResponse budget = request("POST", "/api/v1/budgets", Map.of(
            "companyId", companyId,
            "name", "Shared operating budget",
            "amount", 100,
            "startDate", LocalDate.now().minusDays(1).toString(),
            "endDate", LocalDate.now().plusDays(1).toString(),
            "warningThreshold", 80
        ), token);
        assertEquals(200, budget.status(), budget.body());
        long budgetId = id(parseMap(budget.body()));

        CompletableFuture<ApiResponse> first = requestAsync("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 70,
            "accountId", id(firstAccount),
            "categoryId", id(firstCategory),
            "date", LocalDate.now().toString(),
            "note", "concurrent-budget-a"
        ), token);
        CompletableFuture<ApiResponse> second = requestAsync("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 70,
            "accountId", id(secondAccount),
            "categoryId", id(secondCategory),
            "date", LocalDate.now().toString(),
            "note", "concurrent-budget-b"
        ), token);

        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(
            List.of(200, 409),
            List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body()
        );
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE company_id = ? AND note LIKE 'concurrent-budget-%'",
            Integer.class,
            companyId
        ));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE budget_id = ? AND status = 'confirmed'",
            Integer.class,
            budgetId
        ));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM budget_reservations WHERE budget_id = ? AND status = 'reserved'",
            Integer.class,
            budgetId
        ));
        assertEquals(0, new BigDecimal("70").compareTo(jdbc.queryForObject("""
            SELECT COALESCE(SUM(CAST(amount AS NUMERIC)), 0)
            FROM transactions
            WHERE company_id = ? AND note LIKE 'concurrent-budget-%'
            """, BigDecimal.class, companyId)));
    }

    @Test
    void concurrentRefundsCannotExceedRemainingRefundableAmount() throws Exception {
        String token = adminToken();
        long companyId = createCompany(token, "Concurrent refund capacity");
        Map<String, Object> account = createAccount(token, companyId, "Refund concurrency account", "1000");
        Map<String, Object> category = createCategory(token, companyId, "Refund concurrency expense", "expense");
        ApiResponse created = request("POST", "/api/v1/transactions", Map.of(
            "companyId", companyId,
            "type", 2,
            "amount", 100,
            "accountId", id(account),
            "categoryId", id(category),
            "date", LocalDate.now().toString(),
            "note", "concurrent-refund-original"
        ), token);
        assertEquals(200, created.status(), created.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) parseMap(created.body()).get("transaction");
        long originalId = id(original);

        CompletableFuture<ApiResponse> first;
        CompletableFuture<ApiResponse> second;
        try (Connection blocker = lockRow("SELECT id FROM transactions WHERE id = ? FOR UPDATE", originalId)) {
            String path = "/api/v1/transactions/" + originalId + "/refund";
            Map<String, Object> body = Map.of(
                "companyId", companyId,
                "amount", 70,
                "date", LocalDate.now().toString(),
                "note", "concurrent-refund"
            );
            first = requestAsync("POST", path, body, token);
            second = requestAsync("POST", path, body, token);
            awaitBlockedQueries("FROM transactions WHERE id", 2);
            blocker.commit();
        }
        ApiResponse firstResponse = first.get(10, TimeUnit.SECONDS);
        ApiResponse secondResponse = second.get(10, TimeUnit.SECONDS);

        assertEquals(
            List.of(200, 409),
            List.of(firstResponse.status(), secondResponse.status()).stream().sorted().toList(),
            firstResponse.body() + " / " + secondResponse.body()
        );
        assertEquals(0, new BigDecimal("70").compareTo(jdbc.queryForObject(
            "SELECT CAST(refunded_amount AS NUMERIC) FROM transactions WHERE id = ?",
            BigDecimal.class,
            originalId
        )));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM transactions WHERE original_transaction_id = ?",
            Integer.class,
            originalId
        ));
        assertEquals("970", jdbc.queryForObject(
            "SELECT balance FROM accounts WHERE id = ?",
            String.class,
            id(account)
        ));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events
            WHERE event_type = 'accounting.transaction.refund' AND company_id = ?
            """, Integer.class, companyId));
    }
}
