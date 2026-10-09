package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/**
 * Process-local delivery observations for the reserved SLA notification route.
 * Durable queue/dead-letter authority remains the Outbox database state.
 */
public final class ObservedSlaTimeoutNotificationConnector implements BusinessCallbackConnector {

    private static final String KIND = "sla_timeout";
    private final BusinessCallbackConnector delegate;
    private final MeterRegistry registry;

    public ObservedSlaTimeoutNotificationConnector(BusinessCallbackConnector delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public CallbackReceipt deliver(ConnectorContext context, BusinessEvent event) {
        increment("approval.notification.attempts", "kind", KIND);
        CallbackReceipt receipt;
        try {
            receipt = delegate.deliver(context, event);
        } catch (RuntimeException failure) {
            increment("approval.notification.failures", "kind", KIND, "category", "exception");
            throw failure;
        }
        if (receipt == null) {
            increment("approval.notification.failures", "kind", KIND, "category", "invalid_receipt");
            return null;
        }
        switch (receipt.status()) {
            case DELIVERED -> increment("approval.notification.delivered", "kind", KIND);
            case RETRYABLE_FAILURE ->
                increment("approval.notification.failures", "kind", KIND, "category", "retryable");
            case PERMANENT_FAILURE ->
                increment("approval.notification.failures", "kind", KIND, "category", "permanent");
        }
        return receipt;
    }

    private void increment(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException unavailable) {
            // Telemetry must never change the existing delivery result, retry, lease or dead-letter behavior.
        }
    }
}
