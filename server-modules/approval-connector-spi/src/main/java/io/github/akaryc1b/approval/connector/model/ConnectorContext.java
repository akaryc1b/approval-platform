package io.github.akaryc1b.approval.connector.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Tenant and correlation context passed to every connector invocation.
 */
public record ConnectorContext(
    String connectorKey,
    String tenantId,
    String requestId,
    String traceId,
    Instant requestedAt
) {

    public ConnectorContext {
        connectorKey = requireText(connectorKey, "connectorKey");
        tenantId = requireText(tenantId, "tenantId");
        requestId = requireText(requestId, "requestId");
        traceId = traceId == null || traceId.isBlank() ? null : traceId;
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
