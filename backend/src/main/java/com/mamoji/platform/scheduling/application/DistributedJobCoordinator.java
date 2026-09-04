package com.mamoji.platform.scheduling.application;

import com.mamoji.platform.scheduling.infrastructure.ScheduledJobLeaseRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs scheduled work only while this process owns the current database lease. */
@Service
public class DistributedJobCoordinator {
    private static final Logger log = LoggerFactory.getLogger(DistributedJobCoordinator.class);

    private final ScheduledJobLeaseRepository leases;

    public DistributedJobCoordinator(ScheduledJobLeaseRepository leases) {
        this.leases = leases;
    }

    public boolean runIfDue(
        String jobName,
        long cadenceMillis,
        long leaseMillis,
        Runnable action
    ) {
        Optional<String> lockToken = leases.tryAcquire(jobName, leaseMillis);
        if (lockToken.isEmpty()) {
            return false;
        }
        String token = lockToken.get();
        try {
            action.run();
            if (!leases.markCompleted(jobName, token, cadenceMillis)) {
                log.warn(
                    "Ignored completion for scheduled job {} because lease {} is no longer current",
                    jobName,
                    token
                );
            }
            return true;
        } catch (RuntimeException ex) {
            if (!leases.markFailed(jobName, token, cadenceMillis, limited(errorMessage(ex), 1000))) {
                log.warn(
                    "Ignored failure for scheduled job {} because lease {} is no longer current",
                    jobName,
                    token
                );
            }
            throw ex;
        }
    }

    private String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
    }

    private String limited(String value, int maxLength) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
