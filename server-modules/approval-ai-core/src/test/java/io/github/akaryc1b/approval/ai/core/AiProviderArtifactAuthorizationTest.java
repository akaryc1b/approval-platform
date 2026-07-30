package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderArtifactAuthorizationTest {

    @Test
    void publicRegistryRequiresExactArtifactAuthorizationBeforeRouteMatch() {
        AiVersionReferences versions = AiAdvisoryArtifactRegistryTest.versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        AiProviderRoute route = route(versions);

        AiProviderRegistry authorized = new AiProviderRegistry(
            List.of(provider),
            AiAdvisoryArtifactRegistryTest.registry(versions)
        );
        assertTrue(authorized.matches(provider, route));

        AiAdvisoryArtifactRegistry emptyArtifacts = new AiAdvisoryArtifactRegistry(
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
        AiProviderRegistry blocked = new AiProviderRegistry(
            List.of(provider),
            emptyArtifacts
        );
        assertFalse(blocked.matches(provider, route));
    }

    private static AiProviderRoute route(AiVersionReferences versions) {
        return new AiProviderRoute(
            "artifact-route",
            1,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(200), 16_000, 8, 0.60d)
        );
    }
}
