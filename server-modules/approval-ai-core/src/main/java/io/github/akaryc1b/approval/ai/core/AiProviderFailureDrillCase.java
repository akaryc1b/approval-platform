package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;

/** Expected fail-closed startup behavior for one offline deployment fault drill. */
public record AiProviderFailureDrillCase(
    String caseId,
    AiProviderDeploymentReadinessReport.FaultClass expectedFaultClass,
    AiProviderDeploymentReadinessReport.Status expectedStatus,
    Criticality criticality
) {

    public AiProviderFailureDrillCase {
        caseId = requireText(caseId, "caseId", 160);
        expectedFaultClass = Objects.requireNonNull(
            expectedFaultClass,
            "expectedFaultClass must not be null"
        );
        expectedStatus = Objects.requireNonNull(
            expectedStatus,
            "expectedStatus must not be null"
        );
        criticality = Objects.requireNonNull(criticality, "criticality must not be null");
        if (expectedStatus == AiProviderDeploymentReadinessReport.Status.READY_FOR_FAULT_DRILL) {
            throw new IllegalArgumentException(
                "failure drill cases must expect a fail-closed or disabled status"
            );
        }
    }

    public enum Criticality {
        REQUIRED,
        CRITICAL
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
