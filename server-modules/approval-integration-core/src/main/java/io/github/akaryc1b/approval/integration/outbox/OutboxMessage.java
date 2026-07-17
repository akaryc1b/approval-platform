package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable message appended in the same transaction as the approval state change.
 */
public record OutboxMessage(
    UUID id,
    ConnectorContext context,
    BusinessEvent event,
    Instant availableAt,
    Instant createdAt
) {

    public OutboxMessage {
        id = Objects.requireNonNull(id, "id must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        event = Objects.requireNonNull(event, "event must not be null");
        availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!context.tenantId().equals(event.payload().getOrDefault("tenantId", context.tenantId()))) {
            throw new IllegalArgumentException("event tenantId must match connector context when present");
        }
    }

    public static OutboxMessage create(
        ConnectorContext context,
        BusinessEvent event,
        Instant now
    ) {
        return new OutboxMessage(UUID.randomUUID(), context, event, now, now);
    }
}
