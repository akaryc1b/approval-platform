package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationRequest;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationResult;
import io.github.akaryc1b.approval.connector.invocation.GovernedReadOnlyConnectorInvocationCoordinator;

import java.time.Clock;
import java.util.Objects;

/**
 * Server-owned invocation facade that records secret-free evidence without changing P7 semantics.
 */
public final class ObservedReadOnlyConnectorInvocationService {

    private final GovernedReadOnlyConnectorInvocationCoordinator coordinator;
    private final ConnectorInvocationObservationSink observationSink;
    private final Clock clock;

    public ObservedReadOnlyConnectorInvocationService(
        GovernedReadOnlyConnectorInvocationCoordinator coordinator,
        ConnectorInvocationObservationSink observationSink,
        Clock clock
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.observationSink = Objects.requireNonNull(
            observationSink,
            "observationSink must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public InvocationResult invoke(String trustedTenantId, InvocationRequest request) {
        InvocationResult result = coordinator.invoke(trustedTenantId, request);
        try {
            observationSink.record(result.evidence(), clock.instant());
        } catch (RuntimeException problem) {
            // Diagnostics are best effort and cannot alter invocation semantics.
        }
        return result;
    }
}
