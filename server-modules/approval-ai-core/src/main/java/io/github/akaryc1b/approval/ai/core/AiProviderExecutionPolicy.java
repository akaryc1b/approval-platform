package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Server-side provider, model, capability, timeout and confidence authorization. */
public record AiProviderExecutionPolicy(
    boolean enabled,
    Set<String> allowedProviderIds,
    Set<String> allowedModelAuthorizationKeys,
    Set<AiCapability> allowedCapabilities,
    Duration maximumTimeout,
    double minimumConfidence
) {

    public AiProviderExecutionPolicy {
        allowedProviderIds = allowedProviderIds == null
            ? Set.of()
            : Set.copyOf(allowedProviderIds);
        allowedModelAuthorizationKeys = allowedModelAuthorizationKeys == null
            ? Set.of()
            : Set.copyOf(allowedModelAuthorizationKeys);
        allowedCapabilities = allowedCapabilities == null
            ? Set.of()
            : Set.copyOf(allowedCapabilities);
        maximumTimeout = Objects.requireNonNull(
            maximumTimeout,
            "maximumTimeout must not be null"
        );
        if (maximumTimeout.isZero() || maximumTimeout.isNegative()) {
            throw new IllegalArgumentException("maximumTimeout must be positive");
        }
        if (Double.isNaN(minimumConfidence)
            || minimumConfidence < 0.0d
            || minimumConfidence > 1.0d) {
            throw new IllegalArgumentException("minimumConfidence must be between 0 and 1");
        }
    }

    public boolean allowsModel(AiVersionReferences.ModelVersion model) {
        return allowedModelAuthorizationKeys.contains(model.authorizationKey());
    }
}
