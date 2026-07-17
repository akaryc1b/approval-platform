package io.github.akaryc1b.approval.connector.port;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public interface BusinessCallbackConnector {

    CallbackReceipt deliver(ConnectorContext context, BusinessEvent event);

    record BusinessEvent(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        String idempotencyKey,
        Map<String, Object> payload
    ) {
        public BusinessEvent {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            eventType = requireText(eventType, "eventType");
            aggregateType = requireText(aggregateType, "aggregateType");
            aggregateId = requireText(aggregateId, "aggregateId");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    record CallbackReceipt(
        DeliveryStatus status,
        String providerRequestId,
        int responseCode,
        Instant completedAt,
        String errorMessage
    ) {
        public CallbackReceipt {
            status = Objects.requireNonNull(status, "status must not be null");
            providerRequestId = normalize(providerRequestId);
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            errorMessage = normalize(errorMessage);
        }
    }

    enum DeliveryStatus {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
