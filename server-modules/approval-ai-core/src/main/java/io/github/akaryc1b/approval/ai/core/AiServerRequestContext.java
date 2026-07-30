package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;

/** Trusted server-owned request identity. */
public record AiServerRequestContext(
    String tenantId,
    String operatorId,
    String requestId,
    String traceId
) {

    public AiServerRequestContext {
        tenantId = requireText(tenantId, "tenantId", 120);
        operatorId = requireText(operatorId, "operatorId", 200);
        requestId = requireText(requestId, "requestId", 128);
        traceId = normalizeOptional(traceId, 128);
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
            throw new IllegalArgumentException("traceId must be bounded");
        }
        return normalized;
    }
}
