package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.Map;
import java.util.Objects;

/** Versioned masking, minimization and input-bound policy. */
public record AiDataMinimizationPolicy(
    AiVersionReferences.PolicyVersion version,
    Map<String, FieldRule> fieldRules,
    InputLimits limits,
    boolean blockPromptInjectionMarkers
) {

    public AiDataMinimizationPolicy {
        version = Objects.requireNonNull(version, "version must not be null");
        fieldRules = fieldRules == null ? Map.of() : Map.copyOf(fieldRules);
        limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    public FieldRule ruleFor(String fieldKey) {
        return fieldRules.getOrDefault(fieldKey, FieldRule.DEFAULT);
    }

    public enum FieldRule {
        DEFAULT,
        INCLUDE,
        MASK,
        OMIT
    }

    public record InputLimits(
        int maximumFields,
        int maximumTextCharactersPerValue,
        int maximumTotalTextCharacters,
        int maximumCollectionSize,
        int maximumDepth
    ) {
        public InputLimits {
            if (maximumFields < 1
                || maximumTextCharactersPerValue < 1
                || maximumTotalTextCharacters < 1
                || maximumCollectionSize < 1
                || maximumDepth < 1) {
                throw new IllegalArgumentException("all AI input limits must be positive");
            }
        }

        public static InputLimits conservativeDefaults() {
            return new InputLimits(64, 4_000, 16_000, 50, 4);
        }
    }
}
