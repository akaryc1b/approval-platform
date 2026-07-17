package io.github.akaryc1b.approval.domain.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit fact emitted for every approval command and administrative repair action.
 */
public record AuditEvent(
    UUID eventId,
    String tenantId,
    String operatorId,
    String action,
    String aggregateType,
    String aggregateId,
    String requestId,
    String traceId,
    Instant occurredAt,
    Map<String, String> attributes
) {

    public AuditEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = requireText(tenantId, "tenantId");
        operatorId = requireText(operatorId, "operatorId");
        action = requireText(action, "action");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        requestId = requireText(requestId, "requestId");
        traceId = traceId == null || traceId.isBlank() ? null : traceId;
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
