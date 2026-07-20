package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;

import java.util.Optional;

/** Request-local parent task outcome used by projection-level collaboration gates. */
public final class ApprovalTaskOutcomeContext {

    private final ThreadLocal<TaskOutcome> outcome = new ThreadLocal<>();

    public void set(TaskOutcome value) {
        outcome.set(value);
    }

    public Optional<TaskOutcome> current() {
        return Optional.ofNullable(outcome.get());
    }

    public void clear() {
        outcome.remove();
    }
}
