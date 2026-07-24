package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic M6-A foundation review evidence. It never grants formal acceptance or enablement.
 */
public record ConnectorFoundationAcceptanceEvidence(
    String foundationVersion,
    ConnectorFoundationAcceptanceStatus status,
    String registryFingerprint,
    List<String> operationContractFingerprints,
    String selectionPolicyVersion,
    String compatibilityMatrixVersion,
    String orchestrationPolicyVersion,
    String admissionPolicyVersion,
    List<String> admissionEvidenceHashes,
    Set<ConnectorFoundationBlockedCapability> blockedCapabilities,
    Instant evaluatedAt
) {

    public ConnectorFoundationAcceptanceEvidence {
        foundationVersion = ConnectorContractSupport.requireSafeIdentifier(
            foundationVersion,
            "foundationVersion"
        );
        status = Objects.requireNonNull(status, "status must not be null");
        registryFingerprint = ConnectorContractSupport.requireSha256(
            registryFingerprint,
            "registryFingerprint"
        );
        operationContractFingerprints = sortedHashes(
            operationContractFingerprints,
            "operationContractFingerprints",
            false
        );
        selectionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            selectionPolicyVersion,
            "selectionPolicyVersion"
        );
        compatibilityMatrixVersion = ConnectorContractSupport.requireSafeIdentifier(
            compatibilityMatrixVersion,
            "compatibilityMatrixVersion"
        );
        orchestrationPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            orchestrationPolicyVersion,
            "orchestrationPolicyVersion"
        );
        admissionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            admissionPolicyVersion,
            "admissionPolicyVersion"
        );
        admissionEvidenceHashes = sortedHashes(
            admissionEvidenceHashes,
            "admissionEvidenceHashes",
            true
        );
        blockedCapabilities = blockedCapabilities == null
            ? Set.of()
            : Set.copyOf(blockedCapabilities);
        if (!blockedCapabilities.equals(EnumSet.allOf(ConnectorFoundationBlockedCapability.class))) {
            throw new IllegalArgumentException(
                "blockedCapabilities must retain every production safety block"
            );
        }
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    }

    public String canonicalEvidence() {
        return "foundationVersion=" + foundationVersion
            + "\nstatus=" + status.name()
            + "\nregistryFingerprint=" + registryFingerprint
            + "\noperationContracts=" + String.join(",", operationContractFingerprints)
            + "\nselectionPolicyVersion=" + selectionPolicyVersion
            + "\ncompatibilityMatrixVersion=" + compatibilityMatrixVersion
            + "\norchestrationPolicyVersion=" + orchestrationPolicyVersion
            + "\nadmissionPolicyVersion=" + admissionPolicyVersion
            + "\nadmissionEvidence=" + String.join(",", admissionEvidenceHashes)
            + "\nblockedCapabilities=" + blockedCapabilities.stream()
                .map(Enum::name)
                .sorted()
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "," + right)
            + "\nevaluatedAt=" + evaluatedAt;
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    public boolean readyForFormalAcceptanceReview() {
        return status == ConnectorFoundationAcceptanceStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW;
    }

    public boolean formalAcceptanceGranted() {
        return false;
    }

    public boolean productionEnabled() {
        return false;
    }

    public boolean automaticExecutionEnabled() {
        return false;
    }

    public boolean automaticRetryEnabled() {
        return false;
    }

    public boolean requiresExplicitFormalAcceptance() {
        return true;
    }

    private static List<String> sortedHashes(
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
            .map(value -> ConnectorContractSupport.requireSha256(value, name + " entry"))
            .distinct()
            .sorted()
            .toList();
        if (normalized.size() != source.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
        return normalized;
    }
}
