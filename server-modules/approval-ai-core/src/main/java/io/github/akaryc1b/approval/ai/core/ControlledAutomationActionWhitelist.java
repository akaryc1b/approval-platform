package io.github.akaryc1b.approval.ai.core;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-owned lookup for the exact controlled-automation Action Whitelist snapshot.
 *
 * <p>This contract grants no command authority. A resolved definition is only schema and risk
 * metadata used to build a non-executable Proposal. Command admission remains outside P1.</p>
 */
public interface ControlledAutomationActionWhitelist {

    String version();

    Optional<ActionDefinition> resolve(String canonicalActionType);

    static ControlledAutomationActionWhitelist empty(String version) {
        return new EmptyActionWhitelist(version);
    }

    enum ParameterType {
        BOOLEAN,
        ENUM,
        IDENTIFIER,
        INSTANT,
        INTEGER,
        TEXT
    }

    enum RiskClassification {
        LOW,
        MEDIUM,
        HIGH
    }

    enum ReauthenticationRequirement {
        REQUIRED
    }

    record ParameterDefinition(
        String name,
        ParameterType type,
        Set<String> allowedEnumValues
    ) {
        public ParameterDefinition {
            name = ControlledAutomationProposal.requireParameterName(name);
            type = Objects.requireNonNull(type, "type must not be null");
            allowedEnumValues = allowedEnumValues == null
                ? Set.of()
                : Set.copyOf(allowedEnumValues);
            if (type == ParameterType.ENUM) {
                if (allowedEnumValues.isEmpty() || allowedEnumValues.size() > 32) {
                    throw new IllegalArgumentException(
                        "enum parameters require one to thirty-two allowed values"
                    );
                }
                for (String value : allowedEnumValues) {
                    ControlledAutomationProposal.requireEnumValue(value);
                }
            } else if (!allowedEnumValues.isEmpty()) {
                throw new IllegalArgumentException(
                    "only enum parameters may declare allowed values"
                );
            }
        }
    }

    record ActionDefinition(
        String canonicalActionType,
        String targetResourceType,
        Map<String, ParameterDefinition> parameterSchema,
        RiskClassification riskClassification,
        String sideEffectSummary,
        ReauthenticationRequirement reauthenticationRequirement
    ) {
        public ActionDefinition {
            canonicalActionType = ControlledAutomationProposal.requireCanonicalActionType(
                canonicalActionType
            );
            targetResourceType = ControlledAutomationProposal.requireResourceType(
                targetResourceType
            );
            parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
            if (parameterSchema.size() > ControlledAutomationProposal.MAXIMUM_PARAMETERS) {
                throw new IllegalArgumentException("parameter schema exceeds the closed limit");
            }
            for (Map.Entry<String, ParameterDefinition> entry : parameterSchema.entrySet()) {
                String key = ControlledAutomationProposal.requireParameterName(entry.getKey());
                ParameterDefinition definition = Objects.requireNonNull(
                    entry.getValue(),
                    "parameter definition must not be null"
                );
                if (!key.equals(definition.name())) {
                    throw new IllegalArgumentException(
                        "parameter schema key must match the exact definition name"
                    );
                }
            }
            riskClassification = Objects.requireNonNull(
                riskClassification,
                "riskClassification must not be null"
            );
            sideEffectSummary = ControlledAutomationProposal.requireHumanSummary(
                sideEffectSummary,
                "sideEffectSummary"
            );
            reauthenticationRequirement = Objects.requireNonNull(
                reauthenticationRequirement,
                "reauthenticationRequirement must not be null"
            );
        }
    }

    final class EmptyActionWhitelist implements ControlledAutomationActionWhitelist {

        private final String version;

        private EmptyActionWhitelist(String version) {
            this.version = ControlledAutomationProposal.requireVersion(version, "whitelistVersion");
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public Optional<ActionDefinition> resolve(String canonicalActionType) {
            ControlledAutomationProposal.requireCanonicalActionType(canonicalActionType);
            return Optional.empty();
        }
    }
}
