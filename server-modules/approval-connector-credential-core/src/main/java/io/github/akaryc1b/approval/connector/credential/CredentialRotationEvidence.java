package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CredentialRotationEvidence(
    CredentialRotationStatus status,
    String credentialReferenceHash,
    String providerKey,
    String keyId,
    String previousVersionId,
    String activeVersionId,
    String previousDescriptorFingerprint,
    String activeDescriptorFingerprint,
    String sourceEvidenceHash,
    String policyVersion,
    Instant evaluatedAt,
    Map<String, String> metadata
) {

    public CredentialRotationEvidence {
        status = Objects.requireNonNull(status, "status must not be null");
        credentialReferenceHash = CredentialContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        keyId = CredentialContractSupport.requireIdentifier(keyId, "keyId");
        previousVersionId = CredentialContractSupport.requireIdentifier(
            previousVersionId,
            "previousVersionId"
        );
        activeVersionId = CredentialContractSupport.requireIdentifier(
            activeVersionId,
            "activeVersionId"
        );
        previousDescriptorFingerprint = CredentialContractSupport.requireSha256(
            previousDescriptorFingerprint,
            "previousDescriptorFingerprint"
        );
        activeDescriptorFingerprint = CredentialContractSupport.requireSha256(
            activeDescriptorFingerprint,
            "activeDescriptorFingerprint"
        );
        sourceEvidenceHash = CredentialContractSupport.requireSha256(
            sourceEvidenceHash,
            "sourceEvidenceHash"
        );
        policyVersion = CredentialContractSupport.requireIdentifier(policyVersion, "policyVersion");
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        metadata = CredentialContractSupport.boundedMetadata(metadata, "metadata");
        if (previousVersionId.equals(activeVersionId)) {
            throw new IllegalArgumentException("rotation versions must differ");
        }
    }

    public String canonicalJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
            .append("\"status\":").append(CredentialContractSupport.json(status.name()))
            .append(",\"credentialReferenceHash\":")
            .append(CredentialContractSupport.json(credentialReferenceHash))
            .append(",\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"keyId\":").append(CredentialContractSupport.json(keyId))
            .append(",\"previousVersionId\":")
            .append(CredentialContractSupport.json(previousVersionId))
            .append(",\"activeVersionId\":")
            .append(CredentialContractSupport.json(activeVersionId))
            .append(",\"previousDescriptorFingerprint\":")
            .append(CredentialContractSupport.json(previousDescriptorFingerprint))
            .append(",\"activeDescriptorFingerprint\":")
            .append(CredentialContractSupport.json(activeDescriptorFingerprint))
            .append(",\"sourceEvidenceHash\":")
            .append(CredentialContractSupport.json(sourceEvidenceHash))
            .append(",\"policyVersion\":")
            .append(CredentialContractSupport.json(policyVersion))
            .append(",\"evaluatedAt\":")
            .append(CredentialContractSupport.instant(evaluatedAt))
            .append(",\"metadata\":{");
        int index = 0;
        for (var entry : metadata.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append(CredentialContractSupport.json(entry.getKey()))
                .append(':')
                .append(CredentialContractSupport.json(entry.getValue()));
        }
        return json.append("}}").toString();
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }

    public boolean previousVersionFallbackAllowed() {
        return false;
    }

    public boolean productionExecutionAuthorized() {
        return false;
    }
}
