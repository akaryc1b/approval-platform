package io.github.akaryc1b.approval.domain.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable versioned audit fact emitted for approval commands and administrative operations.
 */
public record AuditEvent(
    UUID eventId,
    String tenantId,
    String operatorId,
    String action,
    String aggregateType,
    String aggregateId,
    String schemaName,
    int schemaVersion,
    String requestId,
    String traceId,
    Instant occurredAt,
    Map<String, String> attributes
) {

    private static final int MAX_ATTRIBUTE_COUNT = 128;
    private static final int MAX_ATTRIBUTE_KEY_LENGTH = 128;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 32_768;

    /**
     * Compatibility constructor for existing emitters. Every newly constructed event still receives
     * an explicit current schema name and version from its action contract.
     */
    public AuditEvent(
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
        this(
            eventId,
            tenantId,
            operatorId,
            action,
            aggregateType,
            aggregateId,
            AuditEventContract.resolve(action).schemaName(),
            AuditEventContract.resolve(action).schemaVersion(),
            requestId,
            traceId,
            occurredAt,
            attributes
        );
    }

    public AuditEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = requireText(tenantId, "tenantId");
        operatorId = requireText(operatorId, "operatorId");
        action = requireText(action, "action");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        schemaName = requireText(schemaName, "schemaName");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requestId = requireText(requestId, "requestId");
        traceId = normalizeOptional(traceId);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        attributes = normalizeAttributes(attributes);
        AuditEventContract.resolve(action).validate(schemaName, schemaVersion, attributes);
    }

    private static Map<String, String> normalizeAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException("audit attributes must not exceed 128 entries");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = requireText(key, "audit attribute key");
            if (normalizedKey.length() > MAX_ATTRIBUTE_KEY_LENGTH) {
                throw new IllegalArgumentException(
                    "audit attribute key must not exceed 128 characters"
                );
            }
            Objects.requireNonNull(value, "audit attribute value must not be null");
            if (value.length() > MAX_ATTRIBUTE_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                    "audit attribute value must not exceed 32768 characters"
                );
            }
            if (normalized.putIfAbsent(normalizedKey, value) != null) {
                throw new IllegalArgumentException(
                    "duplicate audit attribute after key normalization: " + normalizedKey
                );
            }
        });
        return Map.copyOf(normalized);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
