package com.mamoji.approval.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mamoji.approval.domain.ApprovalWorkflow.Action;
import com.mamoji.approval.domain.ApprovalWorkflow.Status;
import com.mamoji.approval.domain.ApprovalWorkflow.Transition;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApprovalWorkflowTest {
    @Test
    void submissionStartsReviewAndSynchronizesTheEntity() {
        Transition submission = ApprovalWorkflow.submission();

        assertEquals(Action.SUBMIT, submission.action());
        assertEquals(Status.PENDING, submission.targetStatus());
        assertEquals("review", submission.currentStep());
        assertEquals("pending", submission.entityStatus());
        assertFalse(submission.commentRequired());
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void allowsOnlyDeclaredPendingTransitions(
        Action action,
        Status target,
        String entityStatus,
        boolean commentRequired
    ) {
        Transition transition = ApprovalWorkflow.transition(Status.PENDING, action);

        assertEquals(target, transition.targetStatus());
        assertEquals("completed", transition.currentStep());
        assertEquals(entityStatus, transition.entityStatus());
        assertEquals(commentRequired, transition.commentRequired());
    }

    @ParameterizedTest
    @MethodSource("terminalStatesAndActions")
    void rejectsEveryTransitionOutOfATerminalState(Status status, Action action) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> ApprovalWorkflow.transition(status, action)
        );

        assertTrue(exception.getMessage().contains(status.value()));
        assertTrue(exception.getMessage().contains(action.value()));
    }

    @Test
    void rejectsUnknownExternalActionsAndStoredStatuses() {
        assertThrows(IllegalArgumentException.class, () -> Action.fromExternal("submit"));
        assertThrows(IllegalArgumentException.class, () -> Action.fromExternal("skip"));
        assertThrows(IllegalArgumentException.class, () -> Status.fromStored("completed"));
    }

    private static Stream<Arguments> allowedTransitions() {
        return Stream.of(
            Arguments.of(Action.APPROVE, Status.APPROVED, "approved", false),
            Arguments.of(Action.REJECT, Status.REJECTED, "rejected", true),
            Arguments.of(Action.WITHDRAW, Status.WITHDRAWN, "not_submitted", false)
        );
    }

    private static Stream<Arguments> terminalStatesAndActions() {
        return Stream.of(Status.APPROVED, Status.REJECTED, Status.WITHDRAWN)
            .flatMap(status -> Stream.of(Action.APPROVE, Action.REJECT, Action.WITHDRAW)
                .map(action -> Arguments.of(status, action)));
    }
}
