package io.github.akaryc1b.approval.ai.core;

import java.time.Duration;
import java.util.Objects;

/** Server-owned per-route invocation bounds. */
public record AiInvocationBudget(
    Duration timeout,
    int maximumInputCharacters,
    int maximumInputFields,
    double minimumConfidence
) {

    public AiInvocationBudget {
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumInputCharacters < 1 || maximumInputFields < 1) {
            throw new IllegalArgumentException("input budgets must be positive");
        }
        if (Double.isNaN(minimumConfidence)
            || minimumConfidence < 0.0d
            || minimumConfidence > 1.0d) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 1");
        }
    }
}
