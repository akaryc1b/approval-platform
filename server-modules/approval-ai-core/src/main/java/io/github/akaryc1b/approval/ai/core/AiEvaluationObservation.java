package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;

/** One fixture observation identified only by bounded fixture hash and coordinator evidence. */
public record AiEvaluationObservation(
    String caseId,
    String fixtureHash,
    AiCoordinatedAdvisoryOutcome outcome
) {

    public AiEvaluationObservation {
        caseId = requireText(caseId, "caseId", 160);
        fixtureHash = requireText(fixtureHash, "fixtureHash", 160);
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
