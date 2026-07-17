package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Appends completion callbacks using the existing transactional Outbox schema.
 */
public final class JdbcApprovalBusinessEventOutbox implements ApprovalBusinessEventOutbox {

    public static final String COMPLETED_EVENT_TYPE = "purchase-payment.completed.v1";

    private final OutboxRepository repository;
    private final Supplier<UUID> identifierGenerator;

    public JdbcApprovalBusinessEventOutbox(
        OutboxRepository repository,
        Supplier<UUID> identifierGenerator
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.identifierGenerator = Objects.requireNonNull(
            identifierGenerator,
            "identifierGenerator must not be null"
        );
    }

    @Override
    public void enqueueCompleted(
        RequestContext context,
        String connectorKey,
        InstanceProjection instance,
        Instant occurredAt
    ) {
        Objects.requireNonNull(context, "context must not be null");
        connectorKey = requireText(connectorKey, "connectorKey");
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        String eventIdempotencyKey = COMPLETED_EVENT_TYPE + ':' + instance.instanceId();
        ConnectorContext connectorContext = new ConnectorContext(
            connectorKey,
            context.tenantId(),
            context.requestId(),
            context.traceId(),
            occurredAt
        );
        BusinessEvent event = new BusinessEvent(
            identifierGenerator.get(),
            COMPLETED_EVENT_TYPE,
            "APPROVAL_INSTANCE",
            instance.instanceId().toString(),
            occurredAt,
            eventIdempotencyKey,
            payload(instance)
        );
        repository.append(new OutboxMessage(
            identifierGenerator.get(),
            connectorContext,
            event,
            occurredAt,
            occurredAt
        ));
    }

    private static Map<String, Object> payload(InstanceProjection instance) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instance.instanceId());
        payload.put("businessKey", instance.businessKey());
        payload.put("status", "COMPLETED");
        payload.put("amount", instance.amount());
        payload.put("supplier", instance.supplier());
        payload.put("purchaseOrderReference", instance.purchaseOrderReference());
        payload.put("attachmentIds", instance.attachmentIds());
        payload.put("initiatorId", instance.initiatorId());
        payload.put("definitionKey", instance.definitionKey());
        payload.put("definitionVersion", instance.definitionVersion());
        payload.put("formVersion", instance.formVersion());
        payload.put("compilerVersion", instance.compilerVersion());
        payload.put("contentHash", instance.contentHash());
        payload.put("assigneeSnapshot", instance.assigneeSnapshot());
        return Map.copyOf(payload);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
