package io.github.akaryc1b.approval.ai.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable metadata-only evaluation suite for later provider-adapter acceptance. */
public record AiEvaluationSuite(
    String suiteId,
    int version,
    int maximumAllowedFailures,
    List<AiEvaluationCase> cases
) {

    public AiEvaluationSuite {
        suiteId = requireText(suiteId, "suiteId", 160);
        if (version < 1) {
            throw new IllegalArgumentException("suite version must be positive");
        }
        if (maximumAllowedFailures < 0) {
            throw new IllegalArgumentException("maximumAllowedFailures must not be negative");
        }
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty() || cases.size() > 500) {
            throw new IllegalArgumentException("evaluation cases must be non-empty and bounded");
        }
        Set<String> ids = new HashSet<>();
        for (AiEvaluationCase evaluationCase : cases) {
            Objects.requireNonNull(evaluationCase, "evaluation case must not be null");
            if (!ids.add(evaluationCase.caseId())) {
                throw new IllegalArgumentException(
                    "evaluation case IDs must be unique: " + evaluationCase.caseId()
                );
            }
        }
        if (maximumAllowedFailures >= cases.size()) {
            throw new IllegalArgumentException(
                "maximumAllowedFailures must be less than the case count"
            );
        }
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
