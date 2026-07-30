package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Exact structured-output metadata with advisory-only and human-review invariants. */
public record AiOutputSchemaDescriptor(
    AiVersionReferences.OutputSchemaVersion version,
    Set<AiCapability> allowedCapabilities,
    Set<Section> requiredSections,
    boolean advisoryOnly,
    boolean humanReviewRequired
) {

    private static final Set<Section> REQUIRED_FOUNDATION_SECTIONS = Set.of(
        Section.SUMMARY,
        Section.CONFIDENCE,
        Section.LIMITATIONS,
        Section.VERSION_PROVENANCE,
        Section.HUMAN_REVIEW_FLAG
    );

    public AiOutputSchemaDescriptor {
        version = Objects.requireNonNull(version, "version must not be null");
        allowedCapabilities = boundedCapabilities(allowedCapabilities);
        requiredSections = requiredSections == null
            ? Set.of()
            : Set.copyOf(requiredSections);
        if (!requiredSections.containsAll(REQUIRED_FOUNDATION_SECTIONS)) {
            throw new IllegalArgumentException(
                "output schema must retain advisory foundation sections"
            );
        }
        if (!advisoryOnly) {
            throw new IllegalArgumentException("output schema must remain advisory only");
        }
        if (!humanReviewRequired) {
            throw new IllegalArgumentException("output schema must require human review");
        }
    }

    public boolean supports(AiCapability capability) {
        return allowedCapabilities.contains(capability);
    }

    public static Set<Section> allAdvisorySections() {
        return Set.copyOf(EnumSet.allOf(Section.class));
    }

    public enum Section {
        SUMMARY,
        OBSERVATIONS,
        RISK_SIGNALS,
        MISSING_MATERIALS,
        RECOMMENDATIONS,
        EVIDENCE_REFERENCES,
        CONFIDENCE,
        LIMITATIONS,
        VERSION_PROVENANCE,
        HUMAN_REVIEW_FLAG
    }

    private static Set<AiCapability> boundedCapabilities(Set<AiCapability> values) {
        Set<AiCapability> copy = values == null ? Set.of() : Set.copyOf(values);
        if (copy.isEmpty() || copy.size() > AiCapability.values().length) {
            throw new IllegalArgumentException("allowedCapabilities must be non-empty and bounded");
        }
        return copy;
    }
}
