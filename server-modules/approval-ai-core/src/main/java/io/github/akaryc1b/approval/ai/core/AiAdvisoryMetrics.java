package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;

import java.util.Objects;

/** Low-cardinality metrics boundary. */
@FunctionalInterface
public interface AiAdvisoryMetrics {

    void record(MetricEvent event);

    static AiAdvisoryMetrics noop() {
        return event -> {
        };
    }

    record MetricEvent(
        AiCapability capability,
        AiOutcomeClassification result,
        AiOutcomeClassification failureClass,
        AiProviderType providerType,
        PolicyResult policyResult
    ) {
        public MetricEvent {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            result = Objects.requireNonNull(result, "result must not be null");
            failureClass = Objects.requireNonNull(
                failureClass,
                "failureClass must not be null"
            );
            providerType = Objects.requireNonNull(
                providerType,
                "providerType must not be null"
            );
            policyResult = Objects.requireNonNull(
                policyResult,
                "policyResult must not be null"
            );
        }
    }

    enum PolicyResult {
        ALLOWED,
        DISABLED,
        UNSUPPORTED,
        REJECTED,
        BLOCKED
    }
}
