package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;

/** Audit-ready routing, circuit and usage evidence for one coordinated advisory request. */
public record AiAdvisoryExecutionEvidence(
    String requestId,
    String traceId,
    String tenantId,
    String operatorId,
    String resourceType,
    String resourceId,
    String authorizationReference,
    AiCapability capability,
    String routeId,
    AiVersionReferences versions,
    AiOutcomeClassification resultClassification,
    AiUsageEvidence usageEvidence,
    AiProviderCircuitBreaker.State circuitStateBefore,
    AiProviderCircuitBreaker.State circuitStateAfter,
    int skippedCandidates,
    boolean providerInvocationStarted,
    boolean postInvocationFallbackAttempted
) {

    public AiAdvisoryExecutionEvidence {
        requestId = requireText(requestId, "requestId", 128);
        traceId = normalizeOptional(traceId, 128);
        tenantId = requireText(tenantId, "tenantId", 120);
        operatorId = requireText(operatorId, "operatorId", 200);
        resourceType = requireText(resourceType, "resourceType", 80);
        resourceId = requireText(resourceId, "resourceId", 200);
        authorizationReference = requireText(
            authorizationReference,
            "authorizationReference",
            200
        );
        capability = Objects.requireNonNull(capability, "capability must not be null");
        routeId = normalizeOptional(routeId, 120);
        resultClassification = Objects.requireNonNull(
            resultClassification,
            "resultClassification must not be null"
        );
        usageEvidence = Objects.requireNonNull(
            usageEvidence,
            "usageEvidence must not be null"
        );
        circuitStateBefore = Objects.requireNonNull(
            circuitStateBefore,
            "circuitStateBefore must not be null"
        );
        circuitStateAfter = Objects.requireNonNull(
            circuitStateAfter,
            "circuitStateAfter must not be null"
        );
        if (skippedCandidates < 0) {
            throw new IllegalArgumentException("skippedCandidates must not be negative");
        }
        if (providerInvocationStarted && (routeId == null || versions == null)) {
            throw new IllegalArgumentException(
                "provider invocation evidence requires exact route and versions"
            );
        }
        if (postInvocationFallbackAttempted) {
            throw new IllegalArgumentException(
                "post-invocation fallback is prohibited in the M6-D safe foundation"
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

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("optional evidence value must be bounded");
        }
        return normalized;
    }
}
