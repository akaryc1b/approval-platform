package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Objects;
import java.util.Set;

/** Knowledge-source metadata only; retrieval and customer data remain blocked in M6-D. */
public record AiKnowledgeSourceDescriptor(
    AiVersionReferences.KnowledgeSourceVersion version,
    Set<AiCapability> allowedCapabilities,
    SourceKind sourceKind,
    boolean retrievalEnabled
) {

    public AiKnowledgeSourceDescriptor {
        version = Objects.requireNonNull(version, "version must not be null");
        allowedCapabilities = boundedCapabilities(allowedCapabilities);
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if (version.containsCustomerData()) {
            throw new IllegalArgumentException("customer knowledge data is prohibited in M6-D");
        }
        if (retrievalEnabled) {
            throw new IllegalArgumentException("knowledge retrieval is prohibited in M6-D");
        }
        boolean none = "none".equals(version.sourceId());
        if (none != (sourceKind == SourceKind.NONE)) {
            throw new IllegalArgumentException(
                "knowledge source kind must match the exact none/non-none version"
            );
        }
    }

    public boolean supports(AiCapability capability) {
        return allowedCapabilities.contains(capability);
    }

    public enum SourceKind {
        NONE,
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
