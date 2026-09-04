package com.mamoji.platform.scheduling.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mamoji.platform.scheduling.infrastructure.ScheduledJobLeaseRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DistributedJobCoordinatorTest {
    private final ScheduledJobLeaseRepository leases = mock(ScheduledJobLeaseRepository.class);
    private final DistributedJobCoordinator coordinator = new DistributedJobCoordinator(leases);

    @Test
    void skipsWorkWhenAnotherInstanceOwnsTheCadence() {
        Runnable action = mock(Runnable.class);
        when(leases.tryAcquire("reminders", 600_000)).thenReturn(Optional.empty());

        assertFalse(coordinator.runIfDue("reminders", 60_000, 600_000, action));

        verify(action, never()).run();
        verify(leases, never()).markCompleted("reminders", "lease", 60_000);
    }

    @Test
    void completesOnlyTheLeaseUsedToRunTheAction() {
        Runnable action = mock(Runnable.class);
        when(leases.tryAcquire("reminders", 600_000)).thenReturn(Optional.of("lease"));
        when(leases.markCompleted("reminders", "lease", 60_000)).thenReturn(true);

        assertTrue(coordinator.runIfDue("reminders", 60_000, 600_000, action));

        verify(action).run();
        verify(leases).markCompleted("reminders", "lease", 60_000);
    }

    @Test
    void recordsFailureAgainstTheCurrentLeaseAndRethrows() {
        Runnable action = () -> {
            throw new IllegalStateException("reminder scan failed");
        };
        when(leases.tryAcquire("reminders", 600_000)).thenReturn(Optional.of("lease"));
        when(leases.markFailed("reminders", "lease", 60_000, "reminder scan failed")).thenReturn(true);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> coordinator.runIfDue("reminders", 60_000, 600_000, action)
        );

        assertEquals("reminder scan failed", failure.getMessage());
        verify(leases).markFailed("reminders", "lease", 60_000, "reminder scan failed");
    }
}
