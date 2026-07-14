package com.mamoji.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The current repositories keep a process-local read model. Until that read
 * model is replaced by database-backed queries, running multiple backend
 * processes would allow stale reads and stale full-row writes. A PostgreSQL
 * session advisory lock turns that unsupported topology into a fail-fast
 * startup error instead of silent accounting corruption.
 */
@Component
public class SingleInstanceDatabaseGuard {
    private static final long LOCK_KEY = 0x4D414D4F4A49L;

    private final DataSource dataSource;
    private final boolean enabled;
    private Connection leaseConnection;

    public SingleInstanceDatabaseGuard(
        DataSource dataSource,
        @Value("${mamoji.runtime.single-instance-guard-enabled:true}") boolean enabled
    ) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @PostConstruct
    void acquire() {
        if (!enabled) {
            return;
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        connection = null;
                        throw new IllegalStateException(
                            "Another Mamoji backend already holds the database lease; "
                                + "the current process-local read model supports exactly one backend instance"
                        );
                    }
                }
            }
            leaseConnection = connection;
            connection = null;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to acquire the Mamoji single-instance database lease", ex);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // Preserve the startup failure that led here.
                }
            }
        }
    }

    @PreDestroy
    void release() {
        Connection connection = leaseConnection;
        leaseConnection = null;
        if (connection == null) {
            return;
        }
        try (connection;
             PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet ignored = statement.executeQuery()) {
                // Closing the lease session is still the final fallback for releasing the lock.
            }
        } catch (Exception ignored) {
            // The database also releases session locks when the connection closes.
        }
    }
}
