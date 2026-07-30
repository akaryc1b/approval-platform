package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * One provider row in a deterministic compatibility report.
 */
public record ConnectorProviderCompatibilityEntry(
    String providerKey,
    String actualProtocolVersion,
    ConnectorProviderCompatibilityStatus status
) {

    public ConnectorProviderCompatibilityEntry {
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        actualProtocolVersion = ConnectorContractSupport.optionalText(
            actualProtocolVersion,
            "actualProtocolVersion",
            64
        );
        status = Objects.requireNonNull(status, "status must not be null");
        if (status == ConnectorProviderCompatibilityStatus.PROVIDER_UNKNOWN) {
            if (actualProtocolVersion != null) {
                throw new IllegalArgumentException(
                    "unknown provider compatibility must not contain actualProtocolVersion"
                );
            }
        } else if (actualProtocolVersion == null) {
            throw new IllegalArgumentException(
                "known provider compatibility must contain actualProtocolVersion"
            );
        }
    }

    public boolean compatible() {
        return status == ConnectorProviderCompatibilityStatus.COMPATIBLE;
    }

    public String canonicalEvidence() {
        return "providerKey=" + providerKey
            + "|actualProtocol=" + (actualProtocolVersion == null ? "" : actualProtocolVersion)
            + "|status=" + status.name();
    }
}
