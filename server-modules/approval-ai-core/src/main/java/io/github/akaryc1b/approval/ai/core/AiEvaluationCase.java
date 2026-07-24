package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** Metadata-only deterministic evaluation expectation; no business input is stored. */
public record AiEvaluationCase(
    String caseId,
    AiCapability capability,
    AiVersionReferences versions,
    Set<AiOutcomeClassification> expectedClassifications,
    boolean critical,
    boolean providerInvocationRequired,
    double minimumConfidence,
    int minimumEvidenceReferences,
    long maximumObservedLatencyMillis
) {

    public AiEvaluationCase {
        caseId = requireText(caseId, "caseId", 160);
        capability = Objects.requireNonNull(capability, "capability must not be null");
        versions = Objects.requireNonNull(versions, "versions must not be null");
        expectedClassifications = expectedClassifications == null
            ? Set.of()
            : Set.copyOf(expectedClassifications);
        if (expectedClassifications.isEmpty()
            || expectedClassifications.size() > AiOutcomeClassification.values().length) {
            throw new IllegalArgumentException(
                "expectedClassifications must be non-empty and bounded"
            );
        }
        if (Double.isNaN(minimumConfidence)
            || minimumConfidence < 0.0d
            || minimumConfidence > 1.0d) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 1");
        }
        if (minimumEvidenceReferences < 0 || minimumEvidenceReferences > 200) {
            throw new IllegalArgumentException(
                "minimumEvidenceReferences must be between 0 and 200"
            );
        }
        if (maximumObservedLatencyMillis < 0) {
            throw new IllegalArgumentException(
                "maximumObservedLatencyMillis must not be negative"
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
