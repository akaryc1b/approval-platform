package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.util.Objects;

/**
 * Secret-free public description of one source decision.
 */
public record CredentialMaterialDescriptor(
    String providerKey,
    String credentialReferenceHash,
    String versionReference,
    CredentialMaterialType materialType,
    CredentialMaterialLoadClassification classification,
    CredentialMaterialFailure failure,
    String requestEvidenceHash,
    String sourceEvidenceHash,
    long acquisitionOrdinal
) {

    public CredentialMaterialDescriptor {
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        credentialReferenceHash = CredentialContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        versionReference = CredentialContractSupport.requireIdentifier(
            versionReference,
            "versionReference"
        );
        materialType = Objects.requireNonNull(materialType, "materialType must not be null");
        classification = Objects.requireNonNull(classification, "classification must not be null");
        failure = Objects.requireNonNull(failure, "failure must not be null");
        requestEvidenceHash = CredentialContractSupport.requireSha256(
            requestEvidenceHash,
            "requestEvidenceHash"
        );
        sourceEvidenceHash = CredentialContractSupport.requireSha256(
            sourceEvidenceHash,
            "sourceEvidenceHash"
        );
        if (acquisitionOrdinal < 0) {
            throw new IllegalArgumentException("acquisitionOrdinal must not be negative");
        }
        if (classification == CredentialMaterialLoadClassification.LOADED
            && failure != CredentialMaterialFailure.NONE) {
            throw new IllegalArgumentException("loaded descriptor must have NONE failure");
        }
        if (classification == CredentialMaterialLoadClassification.REJECTED
            && failure == CredentialMaterialFailure.NONE) {
            throw new IllegalArgumentException("rejected descriptor must have a failure");
        }
    }

    public static CredentialMaterialDescriptor loaded(
        CredentialMaterialRequest request,
        String sourceEvidenceHash,
        long acquisitionOrdinal
    ) {
        Objects.requireNonNull(request, "request must not be null");
        return new CredentialMaterialDescriptor(
            request.providerKey(),
            request.credentialReferenceHash(),
            request.expectedVersion().versionReference(),
            request.materialType(),
            CredentialMaterialLoadClassification.LOADED,
            CredentialMaterialFailure.NONE,
            request.evidenceHash(),
            sourceEvidenceHash,
            acquisitionOrdinal
        );
    }

    public String canonicalJson() {
        return new StringBuilder(640)
            .append('{')
            .append("\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"credentialReferenceHash\":")
            .append(CredentialContractSupport.json(credentialReferenceHash))
            .append(",\"versionReference\":")
            .append(CredentialContractSupport.json(versionReference))
            .append(",\"materialType\":")
            .append(CredentialContractSupport.json(materialType.name()))
            .append(",\"classification\":")
            .append(CredentialContractSupport.json(classification.name()))
            .append(",\"failure\":")
            .append(CredentialContractSupport.json(failure.stableCode()))
            .append(",\"requestEvidenceHash\":")
            .append(CredentialContractSupport.json(requestEvidenceHash))
            .append(",\"sourceEvidenceHash\":")
            .append(CredentialContractSupport.json(sourceEvidenceHash))
            .append(",\"acquisitionOrdinal\":").append(acquisitionOrdinal)
            .append('}')
            .toString();
    }

    public String descriptorHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }

    @Override
    public String toString() {
        return canonicalJson();
    }
}
