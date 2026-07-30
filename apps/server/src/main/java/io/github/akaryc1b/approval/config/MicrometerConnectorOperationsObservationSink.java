package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.connector.invocation.ConnectorInvocationObservationSink;
import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;
import io.github.akaryc1b.approval.connector.operations.BoundedConnectorOperationsDiagnosticsStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;

/** Closed low-cardinality metrics plus best-effort process-local diagnostics. */
public final class MicrometerConnectorOperationsObservationSink
    implements ConnectorInvocationObservationSink {

    public static final String METRIC = "approval.connector.invocation.event";
    private static final Logger LOGGER = LoggerFactory.getLogger(
        MicrometerConnectorOperationsObservationSink.class
    );

    private final BoundedConnectorOperationsDiagnosticsStore store;
    private final MeterRegistry meters;

    public MicrometerConnectorOperationsObservationSink(
        BoundedConnectorOperationsDiagnosticsStore store,
        MeterRegistry meters
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    public void record(InvocationEvidence evidence, Instant evaluatedAt) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        try {
            store.record(evidence, evaluatedAt);
        } catch (RuntimeException problem) {
            LOGGER.warn("Connector diagnostics snapshot failed; invocation semantics remain unchanged");
        }
        try {
            Counter.builder(METRIC)
                .description("Closed Connector invocation outcome counter")
                .tag("provider", "dingtalk")
                .tag("operation", evidence.connectorOperation().name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", evidence.completionClassification().name().toLowerCase(java.util.Locale.ROOT))
                .tag("failure", evidence.stableFailureCode().name().toLowerCase(java.util.Locale.ROOT))
                .tag("duration", evidence.durationBucket().name().toLowerCase(java.util.Locale.ROOT))
                .register(meters)
                .increment();
        } catch (RuntimeException problem) {
            LOGGER.warn("Connector metrics failed; invocation semantics remain unchanged");
        }
    }
}
