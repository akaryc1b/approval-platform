package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic evidence for an execution-admission revalidation decision.
 */
public record ConnectorExecutionAdmissionEvidence(
    String admissionPolicyVersion,
    ConnectorExecutionAdmissionStatus status,
    String planHash,
    String registryFingerprint,
    String contractFingerprint,
    String selectionEvidenceHash,
    String compatibilityEvidenceHash,
    String authorizationEvidenceHash,
    String trustedContextEvidenceHash,
    String requestEvidenceHash,
    String credentialReferenceHash,
    Instant checkedAt
) {

    public ConnectorExecutionAdmissionEvidence {
        admissionPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            admissionPolicyVersion,
            "admissionPolicyVersion"
        );
        status = Objects.requireNonNull(status, "status must not be null");
        planHash = ConnectorContractSupport.requireSha256(planHash, "planHash");
        registryFingerprint = ConnectorContractSupport.requireSha256(
            registryFingerprint,
            "registryFingerprint"
        );
        contractFingerprint = ConnectorContractSupport.requireSha256(
            contractFingerprint,
            "contractFingerprint"
        );
        selectionEvidenceHash = ConnectorContractSupport.requireSha256(
            selectionEvidenceHash,
            "selectionEvidenceHash"
        );
        compatibilityEvidenceHash = ConnectorContractSupport.requireSha256(
            compatibilityEvidenceHash,
            "compatibilityEvidenceHash"
        );
        authorizationEvidenceHash = ConnectorContractSupport.requireSha256(
            authorizationEvidenceHash,
            "authorizationEvidenceHash"
        );
        trustedContextEvidenceHash = ConnectorContractSupport.requireSha256(
            trustedContextEvidenceHash,
            "trustedContextEvidenceHash"
        );
        requestEvidenceHash = ConnectorContractSupport.requireSha256(
            requestEvidenceHash,
            "requestEvidenceHash"
        );
        credentialReferenceHash = ConnectorContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }

    public String canonicalEvidence() {
        return "admissionPolicyVersion=" + admissionPolicyVersion
            + "\nstatus=" + status.name()
            + "\nplanHash=" + planHash
            + "\nregistryFingerprint=" + registryFingerprint
            + "\ncontractFingerprint=" + contractFingerprint
            + "\nselectionEvidenceHash=" + selectionEvidenceHash
            + "\ncompatibilityEvidenceHash=" + compatibilityEvidenceHash
            + "\nauthorizationEvidenceHash=" + authorizationEvidenceHash
            + "\ntrustedContextEvidenceHash=" + trustedContextEvidenceHash
            + "\nrequestEvidenceHash=" + requestEvidenceHash
            + "\ncredentialReferenceHash=" + credentialReferenceHash
            + "\ncheckedAt=" + checkedAt;
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }
}
