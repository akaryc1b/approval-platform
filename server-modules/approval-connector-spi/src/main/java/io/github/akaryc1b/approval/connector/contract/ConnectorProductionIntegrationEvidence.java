package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic ownership-gate evidence. It never enables production behavior.
 */
public record ConnectorProductionIntegrationEvidence(
    String policyVersion,
    ConnectorProductionIntegrationGateStatus status,
    String foundationAnchorHash,
    List<ConnectorProductionOwnershipEntry> ownershipEntries,
    Instant evaluatedAt
) {

    public ConnectorProductionIntegrationEvidence {
        policyVersion = ConnectorContractSupport.requireSafeIdentifier(
            policyVersion,
            "policyVersion"
        );
        status = Objects.requireNonNull(status, "status must not be null");
        foundationAnchorHash = ConnectorContractSupport.requireSha256(
            foundationAnchorHash,
            "foundationAnchorHash"
        );
        ownershipEntries = ownershipEntries == null
            ? List.of()
            : ownershipEntries.stream()
                .sorted(Comparator.comparing(entry -> entry.capability().name()))
                .toList();
        if (ownershipEntries.size() > 32
            || ownershipEntries.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ownershipEntries are invalid");
        }
        evaluatedAt = Objects.requireNonNull(
            evaluatedAt,
            "evaluatedAt must not be null"
        );
    }

    public String canonicalEvidence() {
        String entries = ownershipEntries.stream()
            .map(ConnectorProductionOwnershipEntry::canonicalEvidence)
            .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        return "policyVersion=" + policyVersion
            + "\nstatus=" + status.name()
            + "\nfoundationAnchorHash=" + foundationAnchorHash
            + "\nownershipEntries=" + entries
            + "\nevaluatedAt=" + evaluatedAt;
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    public boolean readyForScopedImplementationReview() {
        return status
            == ConnectorProductionIntegrationGateStatus
                .READY_FOR_SCOPED_IMPLEMENTATION_REVIEW;
    }

    public boolean productionEnabled() {
        return false;
    }

    public boolean providerTransportEnabled() {
        return false;
    }

    public boolean credentialResolutionEnabled() {
        return false;
    }

    public boolean tenantRoutingEnabled() {
        return false;
    }

    public boolean persistenceEnabled() {
        return false;
    }

    public boolean providerExecutionEnabled() {
        return false;
    }

    public boolean automaticRetryEnabled() {
        return false;
    }

    public boolean recoveryWorkerEnabled() {
        return false;
    }

    public boolean schemaChangeAllowed() {
        return false;
    }

    public boolean approvalStateMutationAllowed() {
        return false;
    }

    public boolean requiresExplicitCapabilityGate() {
        return true;
    }
}
