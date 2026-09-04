package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.mamoji.bootstrap.EnterpriseDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "mamoji.runtime.environment=local",
    "mamoji.bootstrap.mode=bootstrap",
    "mamoji.bootstrap.admin-email=bootstrap@mamoji.test",
    "mamoji.bootstrap.admin-password=Strong-pass-123!",
    "mamoji.bootstrap.admin-nickname=Bootstrap Owner",
    "mamoji.bootstrap.company-name=Bootstrap Company",
    "mamoji.security.password.min-length=12",
    "mamoji.security.password.require-complexity=true",
    "mamoji.object-storage.enabled=false",
    "mamoji.outbox.consumer.enabled=false",
    "mamoji.notifications.reminder.enabled=false",
    "mamoji.notifications.delivery.enabled=false",
    "spring.main.web-application-type=none"
})
class ProductionBootstrapIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    ApplicationContext application;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void bootstrapCreatesOnlyMinimalEnterpriseDataAndDoesNotRegisterDemoBeans() {
        assertInstanceOf(EnterpriseDataInitializer.class, application.getBean("enterpriseDataInitializer"));
        assertFalse(application.containsBean("ledgerDataInitializer"));
        assertFalse(application.containsBean("categoryDataInitializer"));
        assertFalse(application.containsBean("receiptVoucherDataInitializer"));
        assertFalse(application.containsBean("demoReceiptVoucherDataInitializer"));
        assertFalse(application.containsBean("accountDataInitializer"));
        assertFalse(application.containsBean("transactionDataInitializer"));
        assertFalse(application.containsBean("budgetDataInitializer"));
        assertFalse(application.containsBean("recurringItemDataInitializer"));
        assertFalse(application.containsBean("taxItemDataInitializer"));

        assertEquals(1, count("users"));
        assertEquals(1, count("companies"));
        assertEquals(1, count("departments"));
        assertEquals(1, count("employees"));
        assertEquals(1, count("employment_events"));
        assertEquals(1, count("company_memberships"));
        assertEquals(1, count("ledgers"));
        assertEquals(2, count("categories"));
        assertEquals(0, count("accounts"));
        assertEquals(0, count("transactions"));
        assertEquals(0, count("budgets"));
        assertEquals(0, count("recurring_items"));
        assertEquals(0, count("tax_items"));
        assertEquals(0, count("receipt_vouchers"));
        assertEquals(0, count("entity_transfers"));

        assertEquals("Bootstrap Company", jdbc.queryForObject("SELECT name FROM companies", String.class));
        assertEquals("founder", jdbc.queryForObject("SELECT role FROM company_memberships", String.class));
        assertEquals("company", jdbc.queryForObject("SELECT scope FROM company_memberships", String.class));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
