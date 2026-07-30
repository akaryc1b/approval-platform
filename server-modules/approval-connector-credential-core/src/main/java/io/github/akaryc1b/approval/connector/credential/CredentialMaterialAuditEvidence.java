package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.util.Objects;

/**
 * Immutable, hash-only acquisition and release evidence snapshot.
 */
public record CredentialMaterialAuditEvidence(
    String requestEvidenceHash,
    String descriptorHash,
    String sourceEvidenceHash,
    CredentialMaterialFailure failure,
    boolean opened,
    boolean activeUse,
    boolean closeRequested,
    boolean closed,
    boolean releaseFailed,
    long openOrdinal,
    long closeOrdinal,
    String releaseEvidenceHash
) {

    public CredentialMaterialAuditEvidence {
        requestEvidenceHash = CredentialContractSupport.requireSha256(
            requestEvidenceHash,
            "requestEvidenceHash"
        );
        descriptorHash = CredentialContractSupport.requireSha256(descriptorHash, "descriptorHash");
        sourceEvidenceHash = CredentialContractSupport.requireSha256(
            sourceEvidenceHash,
            "sourceEvidenceHash"
        );
        failure = Objects.requireNonNull(failure, "failure must not be null");
        if (openOrdinal < 0 || closeOrdinal < -1) {
            throw new IllegalArgumentException("ordinals are outside the closed range");
        }
        releaseEvidenceHash = CredentialContractSupport.requireSha256(
            releaseEvidenceHash,
            "releaseEvidenceHash"
        );
    }

    public String canonicalJson() {
        return new StringBuilder(768)
            .append('{')
            .append("\"requestEvidenceHash\":")
            .append(CredentialContractSupport.json(requestEvidenceHash))
            .append(",\"descriptorHash\":")
            .append(CredentialContractSupport.json(descriptorHash))
            .append(",\"sourceEvidenceHash\":")
            .append(CredentialContractSupport.json(sourceEvidenceHash))
            .append(",\"failure\":").append(CredentialContractSupport.json(failure.stableCode()))
            .append(",\"opened\":").append(opened)
            .append(",\"activeUse\":").append(activeUse)
            .append(",\"closeRequested\":").append(closeRequested)
            .append(",\"closed\":").append(closed)
            .append(",\"releaseFailed\":").append(releaseFailed)
            .append(",\"openOrdinal\":").append(openOrdinal)
            .append(",\"closeOrdinal\":").append(closeOrdinal)
            .append(",\"releaseEvidenceHash\":")
            .append(CredentialContractSupport.json(releaseEvidenceHash))
            .append('}')
            .toString();
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }
}
