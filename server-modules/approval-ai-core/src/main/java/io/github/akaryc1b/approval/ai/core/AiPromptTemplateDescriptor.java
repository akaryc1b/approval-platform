package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** Metadata-only prompt template authorization. No prompt body is stored in this contract. */
public record AiPromptTemplateDescriptor(
    AiVersionReferences.PromptTemplateVersion version,
    Set<AiCapability> allowedCapabilities,
    Availability availability
) {

    public AiPromptTemplateDescriptor {
        version = Objects.requireNonNull(version, "version must not be null");
        allowedCapabilities = boundedCapabilities(allowedCapabilities);
        availability = Objects.requireNonNull(availability, "availability must not be null");
    }

    public boolean supports(AiCapability capability) {
        return allowedCapabilities.contains(capability);
    }

    public enum Availability {
        METADATA_ONLY,
        TEST_FIXTURE_METADATA
    }

    private static Set<AiCapability> boundedCapabilities(Set<AiCapability> values) {
        Set<AiCapability> copy = values == null ? Set.of() : Set.copyOf(values);
        if (copy.isEmpty() || copy.size() > AiCapability.values().length) {
            throw new IllegalArgumentException("allowedCapabilities must be non-empty and bounded");
        }
        return copy;
    }
}
