package io.github.akaryc1b.approval.connector.invocation;

import io.github.akaryc1b.approval.connector.invocation.GovernedConnectorInvocationContracts
    .InvocationEvidence;

import java.time.Instant;
import java.util.Objects;

/** Secret-free observation seam for optional process-local diagnostics. */
@FunctionalInterface
public interface ConnectorInvocationObservationSink {

    void record(InvocationEvidence evidence, Instant evaluatedAt);

    static ConnectorInvocationObservationSink noop() {
        return (evidence, evaluatedAt) -> {
            Objects.requireNonNull(evidence, "evidence must not be null");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        };
    }
}
