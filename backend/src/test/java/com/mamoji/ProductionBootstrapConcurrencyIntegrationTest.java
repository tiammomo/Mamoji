package com.mamoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.bootstrap.ProductionBootstrapCommand;
import com.mamoji.service.support.PasswordHasher;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "mamoji.runtime.environment=local",
    "mamoji.bootstrap.mode=disabled",
    "mamoji.object-storage.enabled=false",
    "mamoji.outbox.consumer.enabled=false",
    "mamoji.notifications.reminder.enabled=false",
    "mamoji.notifications.delivery.enabled=false",
    "spring.main.web-application-type=none"
})
@Import(ProductionBootstrapConcurrencyIntegrationTest.HashingTestConfiguration.class)
class ProductionBootstrapConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Autowired
    ProductionBootstrapCommand bootstrap;

    @Autowired
    CoordinatedPasswordHasher passwordHasher;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void bootstrapRollsBackOnFailureThenSerializesTwoConcurrentWorkers() throws Exception {
        ProductionBootstrapCommand.Request invalid = request("X".repeat(201));

        assertThrows(IllegalArgumentException.class, () -> bootstrap.execute(invalid));
        assertEquals(0, count("users"));
        assertEquals(0, count("companies"));
        assertEquals(1, passwordHasher.hashCount());

        passwordHasher.blockNextHash();
        CompletableFuture<ProductionBootstrapCommand.Outcome> first = CompletableFuture.supplyAsync(
            () -> bootstrap.execute(request("Concurrent Company"))
        );
        assertTrue(passwordHasher.awaitHashStarted(10, TimeUnit.SECONDS));
        CompletableFuture<ProductionBootstrapCommand.Outcome> second = CompletableFuture.supplyAsync(
            () -> bootstrap.execute(request("Concurrent Company"))
        );

        assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
        passwordHasher.releaseHash();

        List<ProductionBootstrapCommand.Outcome> outcomes = List.of(
            first.get(10, TimeUnit.SECONDS),
            second.get(10, TimeUnit.SECONDS)
        );
        assertEquals(1, outcomes.stream().filter(ProductionBootstrapCommand.Outcome.CREATED::equals).count());
        assertEquals(1, outcomes.stream()
            .filter(ProductionBootstrapCommand.Outcome.ALREADY_INITIALIZED::equals)
            .count());
        assertEquals(2, passwordHasher.hashCount());

        assertEquals(1, count("users"));
        assertEquals(1, count("companies"));
        assertEquals(1, count("departments"));
        assertEquals(1, count("employees"));
        assertEquals(1, count("employment_events"));
        assertEquals(1, count("company_memberships"));
        assertEquals(1, count("ledgers"));
        assertEquals(2, count("categories"));
        assertEquals(1, count("audit_logs"));
    }

    private ProductionBootstrapCommand.Request request(String companyName) {
        return new ProductionBootstrapCommand.Request(
            "owner@mamoji.test",
            "Strong-pass-123!",
            "Concurrent Owner",
            12,
            true,
            companyName,
            null,
            "Industry",
            "Taxpayer",
            "CNY"
        );
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HashingTestConfiguration {
        @Bean
        @Primary
        CoordinatedPasswordHasher coordinatedPasswordHasher() {
            return new CoordinatedPasswordHasher();
        }
    }

    static final class CoordinatedPasswordHasher extends PasswordHasher {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final CountDownLatch hashStarted = new CountDownLatch(1);
        private final CountDownLatch releaseHash = new CountDownLatch(1);
        private int hashCount;

        @Override
        public synchronized String hash(String password) {
            hashCount++;
            if (blockNext.compareAndSet(true, false)) {
                hashStarted.countDown();
                try {
                    if (!releaseHash.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release bootstrap password hashing");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while coordinating bootstrap test", exception);
                }
            }
            return "integration-password-hash";
        }

        synchronized int hashCount() {
            return hashCount;
        }

        void blockNextHash() {
            blockNext.set(true);
        }

        boolean awaitHashStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return hashStarted.await(timeout, unit);
        }

        void releaseHash() {
            releaseHash.countDown();
        }
    }
}
