package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Proposed ownership matrix for one production integration review.
 */
public record ConnectorProductionIntegrationGateRequest(
    ConnectorFoundationAcceptanceAnchor foundationAnchor,
    String policyVersion,
    List<ConnectorProductionOwnershipEntry> ownershipEntries,
    Instant evaluatedAt
) {

    public ConnectorProductionIntegrationGateRequest {
        foundationAnchor = Objects.requireNonNull(
            foundationAnchor,
            "foundationAnchor must not be null"
        );
        policyVersion = ConnectorContractSupport.requireSafeIdentifier(
            policyVersion,
            "policyVersion"
        );
        ownershipEntries = ownershipEntries == null
            ? List.of()
            : List.copyOf(ownershipEntries);
        if (ownershipEntries.size() > 32
            || ownershipEntries.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ownershipEntries are invalid");
        }
        evaluatedAt = Objects.requireNonNull(
            evaluatedAt,
            "evaluatedAt must not be null"
        );
    }
}
