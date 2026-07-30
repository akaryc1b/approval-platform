package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic server-owned route order and fallback boundary. */
public record AiProviderRoutingPolicy(
    boolean enabled,
    boolean allowPreInvocationCandidateFallback,
    boolean allowPostInvocationFallback,
    List<AiProviderRoute> routes
) {

    private static final Comparator<AiProviderRoute> ROUTE_ORDER = Comparator
        .comparingInt(AiProviderRoute::priority)
        .thenComparing(AiProviderRoute::routeId);

    public AiProviderRoutingPolicy {
        routes = routes == null ? List.of() : List.copyOf(routes);
        if (allowPostInvocationFallback) {
            throw new IllegalArgumentException(
                "post-invocation provider fallback is prohibited in the M6-D safe foundation"
            );
        }
        if (enabled && routes.isEmpty()) {
            throw new IllegalArgumentException("enabled routing policy must declare routes");
        }
        Set<String> routeIds = new HashSet<>();
        io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion policyVersion =
            routes.isEmpty() ? null : routes.get(0).versions().policy();
        for (AiProviderRoute route : routes) {
            Objects.requireNonNull(route, "route must not be null");
            if (!routeIds.add(route.routeId())) {
                throw new IllegalArgumentException("route IDs must be unique");
            }
            if (!route.versions().policy().equals(policyVersion)) {
                throw new IllegalArgumentException(
                    "all candidate routes must use the same input policy version"
                );
            }
        }
    }

    public List<AiProviderRoute> orderedRoutes(AiCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        return routes.stream()
            .filter(route -> route.supports(capability))
            .sorted(ROUTE_ORDER)
            .toList();
    }

    public static AiProviderRoutingPolicy disabled() {
        return new AiProviderRoutingPolicy(false, false, false, List.of());
    }
}
