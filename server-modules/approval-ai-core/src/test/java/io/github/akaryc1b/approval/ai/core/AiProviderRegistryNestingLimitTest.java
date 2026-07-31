package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderRegistryNestingLimitTest {

    @Test
    void routeMatchingRejectsPolicyNestingBeyondProviderContract() {
        AiVersionReferences versions = AiTestFixtures.versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        AiProviderRegistry registry = new AiProviderRegistry(List.of(provider));
        AiProviderRoute route = new AiProviderRoute(
            "route-a",
            0,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(200), 16_000, 64, 0.60d)
        );

        assertTrue(registry.matches(provider, route, policy(versions, 50, 4)));
        assertFalse(registry.matches(provider, route, policy(versions, 51, 4)));
        assertFalse(registry.matches(provider, route, policy(versions, 50, 5)));
    }

    private static AiDataMinimizationPolicy policy(
        AiVersionReferences versions,
        int maximumCollectionSize,
        int maximumDepth
    ) {
        return new AiDataMinimizationPolicy(
            versions.policy(),
            Map.of(),
            new AiDataMinimizationPolicy.InputLimits(
                64,
                4_000,
                16_000,
                maximumCollectionSize,
                maximumDepth
            ),
            true
        );
    }
}
