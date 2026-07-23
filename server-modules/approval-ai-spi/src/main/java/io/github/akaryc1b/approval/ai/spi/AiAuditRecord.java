package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;

/** Audit contract for one advisory attempt and its later human decision linkage. */
public record AiAuditRecord(
    String requestId,
    String traceId,
    String tenantId,
    String operatorId,
    String resourceType,
    String resourceId,
    AiCapability capability,
    AiVersionReferences.PolicyVersion inputPolicyVersion,
    AiVersionReferences versions,
    AiOutcomeClassification resultClassification,
    String humanDecisionReference
) {

    public AiAuditRecord {
        requestId = requireText(requestId, "requestId", 128);
        traceId = normalizeOptional(traceId, 128);
        tenantId = requireText(tenantId, "tenantId", 120);
        operatorId = requireText(operatorId, "operatorId", 200);
        resourceType = requireText(resourceType, "resourceType", 80);
        resourceId = requireText(resourceId, "resourceId", 200);
        capability = Objects.requireNonNull(capability, "capability must not be null");
        inputPolicyVersion = Objects.requireNonNull(
            inputPolicyVersion,
            "inputPolicyVersion must not be null"
        );
        versions = Objects.requireNonNull(versions, "versions must not be null");
        if (!inputPolicyVersion.equals(versions.policy())) {
            throw new IllegalArgumentException(
                "inputPolicyVersion must match the version references policy"
            );
        }
        resultClassification = Objects.requireNonNull(
            resultClassification,
            "resultClassification must not be null"
        );
        humanDecisionReference = normalizeOptional(humanDecisionReference, 200);
    }

    public AiAuditRecord withHumanDecisionReference(String reference) {
        return new AiAuditRecord(
            requestId,
            traceId,
            tenantId,
            operatorId,
            resourceType,
            resourceId,
            capability,
            inputPolicyVersion,
            versions,
            resultClassification,
            reference
        );
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
            throw new IllegalArgumentException("optional audit value must be bounded");
        }
        return normalized;
    }
}
