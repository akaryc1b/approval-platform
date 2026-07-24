package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiProviderRegistryTest {

    @Test
    void resolvesOnlyTheExactRegisteredProviderVersion() {
        AiVersionReferences versions = AiTestFixtures.versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        AiProviderRegistry registry = new AiProviderRegistry(List.of(provider));

        assertEquals(1, registry.size());
        assertSame(provider, registry.find(versions.provider()).orElseThrow());
        assertEquals(
            0,
            registry.find(new AiVersionReferences.ProviderVersion(
                versions.provider().providerId(),
                "different"
            )).stream().count()
        );
    }

    @Test
    void rejectsDuplicateExactProviderVersionRegistration() {
        AiVersionReferences versions = AiTestFixtures.versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderRegistry(List.of(provider, provider))
        );
    }
}
