package io.github.akaryc1b.approval.connector.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable compatibility matrix evidence for one exact operation contract.
 */
public record ConnectorProviderCompatibilityReport(
    String matrixVersion,
    String registryFingerprint,
    String contractKey,
    String contractFingerprint,
    String requiredProtocolVersion,
    List<ConnectorProviderCompatibilityEntry> entries
) {

    public ConnectorProviderCompatibilityReport {
        matrixVersion = ConnectorContractSupport.requireSafeIdentifier(
            matrixVersion,
            "matrixVersion"
        );
        registryFingerprint = ConnectorContractSupport.requireSha256(
            registryFingerprint,
            "registryFingerprint"
        );
        contractKey = ConnectorContractSupport.requireSafeIdentifier(contractKey, "contractKey");
        contractFingerprint = ConnectorContractSupport.requireSha256(
            contractFingerprint,
            "contractFingerprint"
        );
        requiredProtocolVersion = ConnectorContractSupport.optionalText(
            requiredProtocolVersion,
            "requiredProtocolVersion",
            64
        );
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > 32 || entries.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entries must contain between 1 and 32 values");
        }
        List<ConnectorProviderCompatibilityEntry> sorted = entries.stream()
            .sorted((left, right) -> left.providerKey().compareTo(right.providerKey()))
            .toList();
        if (sorted.stream().map(ConnectorProviderCompatibilityEntry::providerKey).distinct().count()
            != sorted.size()) {
            throw new IllegalArgumentException("entries must not contain duplicate provider keys");
        }
        entries = sorted;
    }

    public Optional<ConnectorProviderCompatibilityEntry> findEntry(String providerKey) {
        String key = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        return entries.stream().filter(entry -> entry.providerKey().equals(key)).findFirst();
    }

    public List<String> compatibleProviderKeys() {
        return entries.stream()
            .filter(ConnectorProviderCompatibilityEntry::compatible)
            .map(ConnectorProviderCompatibilityEntry::providerKey)
            .toList();
    }

    public String canonicalEvidence() {
        return "matrixVersion=" + matrixVersion
            + "\nregistryFingerprint=" + registryFingerprint
            + "\ncontractKey=" + contractKey
            + "\ncontractFingerprint=" + contractFingerprint
            + "\nrequiredProtocol=" + optional(requiredProtocolVersion)
            + "\nentries=" + entries.stream()
                .map(ConnectorProviderCompatibilityEntry::canonicalEvidence)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + ";" + right);
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
