package io.github.akaryc1b.approval.connector.contract;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic provider-selection evidence. Provider keys are sorted and bounded.
 */
public record ConnectorProviderSelectionEvidence(
    String policyVersion,
    String registryFingerprint,
    String contractKey,
    List<String> allowedProviderKeys,
    List<String> eligibleProviderKeys,
    String preferredProviderKey,
    String requiredProtocolVersion,
    String selectedProviderKey,
    ConnectorProviderSelectionStatus status
) {

    public ConnectorProviderSelectionEvidence {
        policyVersion = ConnectorContractSupport.requireSafeIdentifier(
            policyVersion,
            "policyVersion"
        );
        registryFingerprint = ConnectorContractSupport.requireSha256(
            registryFingerprint,
            "registryFingerprint"
        );
        contractKey = ConnectorContractSupport.requireSafeIdentifier(
            contractKey,
            "contractKey"
        );
        allowedProviderKeys = sortedKeys(
            allowedProviderKeys,
            "allowedProviderKeys",
            false
        );
        eligibleProviderKeys = sortedKeys(
            eligibleProviderKeys,
            "eligibleProviderKeys",
            true
        );
        preferredProviderKey = optionalProvider(preferredProviderKey, "preferredProviderKey");
        requiredProtocolVersion = ConnectorContractSupport.optionalText(
            requiredProtocolVersion,
            "requiredProtocolVersion",
            64
        );
        selectedProviderKey = optionalProvider(selectedProviderKey, "selectedProviderKey");
        status = Objects.requireNonNull(status, "status must not be null");
        if (!allowedProviderKeys.containsAll(eligibleProviderKeys)) {
            throw new IllegalArgumentException(
                "eligibleProviderKeys must be a subset of allowedProviderKeys"
            );
        }
        if (status == ConnectorProviderSelectionStatus.SELECTED) {
            if (selectedProviderKey == null
                || !eligibleProviderKeys.contains(selectedProviderKey)) {
                throw new IllegalArgumentException(
                    "selected evidence must contain one eligible selectedProviderKey"
                );
            }
        } else if (selectedProviderKey != null) {
            throw new IllegalArgumentException(
                "non-selected evidence must not contain selectedProviderKey"
            );
        }
    }

    public String canonicalEvidence() {
        return "policyVersion=" + policyVersion
            + "\nregistryFingerprint=" + registryFingerprint
            + "\ncontractKey=" + contractKey
            + "\nallowed=" + String.join(",", allowedProviderKeys)
            + "\neligible=" + String.join(",", eligibleProviderKeys)
            + "\npreferred=" + optional(preferredProviderKey)
            + "\nprotocol=" + optional(requiredProtocolVersion)
            + "\nselected=" + optional(selectedProviderKey)
            + "\nstatus=" + status.name();
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    private static List<String> sortedKeys(
        List<String> source,
        String name,
        boolean mayBeEmpty
    ) {
        source = source == null ? List.of() : List.copyOf(source);
        if (!mayBeEmpty && source.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (source.size() > 32 || source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        List<String> normalized = source.stream()
            .map(key -> ConnectorContractSupport.requireSafeIdentifier(key, name + " entry"))
            .distinct()
            .sorted()
            .toList();
        if (normalized.size() != source.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return normalized;
    }

    private static String optionalProvider(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ConnectorContractSupport.requireSafeIdentifier(value, name);
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
