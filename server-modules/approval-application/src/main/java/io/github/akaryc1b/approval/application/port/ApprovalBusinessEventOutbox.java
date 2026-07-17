package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Instant;
import java.util.Objects;

/**
 * Appends business events in the same transaction as approval projection changes.
 */
public interface ApprovalBusinessEventOutbox {

    void enqueueCompleted(
        RequestContext context,
        String connectorKey,
        InstanceProjection instance,
        Instant occurredAt
    );

    static ApprovalBusinessEventOutbox noOp() {
        return (context, connectorKey, instance, occurredAt) -> {
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(instance, "instance must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        };
    }
}
