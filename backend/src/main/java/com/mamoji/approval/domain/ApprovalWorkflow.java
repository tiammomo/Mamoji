package com.mamoji.approval.domain;

import java.util.Arrays;
import java.util.Map;

/** Allowed lifecycle transitions for an approval request. */
public final class ApprovalWorkflow {
    private static final Transition SUBMISSION = new Transition(
        Action.SUBMIT,
        Status.PENDING,
        "review",
        "pending",
        false
    );
    private static final Map<TransitionKey, Transition> TRANSITIONS = Map.of(
        new TransitionKey(Status.PENDING, Action.APPROVE),
        new Transition(Action.APPROVE, Status.APPROVED, "completed", "approved", false),
        new TransitionKey(Status.PENDING, Action.REJECT),
        new Transition(Action.REJECT, Status.REJECTED, "completed", "rejected", true),
        new TransitionKey(Status.PENDING, Action.WITHDRAW),
        new Transition(Action.WITHDRAW, Status.WITHDRAWN, "completed", "not_submitted", false)
    );

    private ApprovalWorkflow() {
    }

    public static Transition submission() {
        return SUBMISSION;
    }

    public static Transition transition(Status currentStatus, Action action) {
        Transition transition = TRANSITIONS.get(new TransitionKey(currentStatus, action));
        if (transition == null) {
            throw new IllegalStateException(
                "Cannot " + action.value() + " an approval request in status " + currentStatus.value()
            );
        }
        return transition;
    }

    public enum Status {
        PENDING("pending"),
        APPROVED("approved"),
        REJECTED("rejected"),
        WITHDRAWN("withdrawn");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Status fromStored(String value) {
            return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported approval status: " + value));
        }
    }

    public enum Action {
        SUBMIT("submit"),
        APPROVE("approve"),
        REJECT("reject"),
        WITHDRAW("withdraw");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Action fromExternal(String value) {
            return Arrays.stream(values())
                .filter(action -> action != SUBMIT && action.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported approval action: " + value));
        }
    }

    public record Transition(
        Action action,
        Status targetStatus,
        String currentStep,
        String entityStatus,
        boolean commentRequired
    ) {
    }

    private record TransitionKey(Status status, Action action) {
    }
}
