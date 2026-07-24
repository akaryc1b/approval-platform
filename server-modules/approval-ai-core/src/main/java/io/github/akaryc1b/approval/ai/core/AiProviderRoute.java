package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** Exact provider/model/version binding selected only from server configuration. */
public record AiProviderRoute(
    String routeId,
    int priority,
    boolean enabled,
    Set<AiCapability> capabilities,
    AiVersionReferences versions,
    AiInvocationBudget budget
) {

    public AiProviderRoute {
        routeId = requireText(routeId, "routeId", 120);
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (enabled && capabilities.isEmpty()) {
            throw new IllegalArgumentException("enabled route must declare capabilities");
        }
        versions = Objects.requireNonNull(versions, "versions must not be null");
        budget = Objects.requireNonNull(budget, "budget must not be null");
        if (versions.knowledgeSource().containsCustomerData()) {
            throw new IllegalArgumentException(
                "customer knowledge data remains blocked in the M6-D safe foundation"
            );
        }
    }

    public boolean supports(AiCapability capability) {
        return enabled && capabilities.contains(capability);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
