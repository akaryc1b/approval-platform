package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;

import java.util.Objects;

/** Low-cardinality provider-routing metrics; no tenant, user, resource or correlation IDs. */
@FunctionalInterface
public interface AiProviderRoutingMetrics {

    void record(RoutingMetricEvent event);

    static AiProviderRoutingMetrics noop() {
        return event -> {
        };
    }

    record RoutingMetricEvent(
        AiCapability capability,
        RoutingResult result,
        AiOutcomeClassification failureClass,
        AiProviderCircuitBreaker.State circuitState
    ) {
        public RoutingMetricEvent {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            result = Objects.requireNonNull(result, "result must not be null");
            failureClass = Objects.requireNonNull(
                failureClass,
                "failureClass must not be null"
            );
            circuitState = Objects.requireNonNull(
                circuitState,
                "circuitState must not be null"
            );
        }
    }

    enum RoutingResult {
        SELECTED,
        DISABLED,
        UNSUPPORTED,
        CIRCUIT_OPEN,
        BUDGET_BLOCKED
    }
}
