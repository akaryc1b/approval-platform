package io.github.akaryc1b.approval.domain.context;

import java.util.Objects;

/**
 * Immutable identity and correlation context for an externally initiated write command.
 */
public record RequestContext(
    String tenantId,
    String operatorId,
    String requestId,
    String idempotencyKey,
    String traceId
) {

    public RequestContext {
        tenantId = requireText(tenantId, "tenantId");
        operatorId = requireText(operatorId, "operatorId");
        requestId = requireText(requestId, "requestId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        traceId = normalizeOptional(traceId);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
