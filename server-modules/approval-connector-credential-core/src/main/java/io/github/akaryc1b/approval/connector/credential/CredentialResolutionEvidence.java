package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record CredentialResolutionEvidence(
    CredentialResolutionStatus status,
    String credentialReferenceHash,
    String providerKey,
    String keyId,
    String versionId,
    CredentialMaterialType credentialType,
    ConnectorOperation operation,
    String policyVersion,
    Instant resolvedAt,
    Instant validUntil,
    String descriptorFingerprint,
    String sourceEvidenceHash,
    Map<String, String> metadata
) {

    public CredentialResolutionEvidence {
        status = Objects.requireNonNull(status, "status must not be null");
        credentialReferenceHash = CredentialContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        keyId = optionalIdentifier(keyId, "keyId");
        versionId = optionalIdentifier(versionId, "versionId");
        credentialType = Objects.requireNonNull(
            credentialType,
            "credentialType must not be null"
        );
        operation = Objects.requireNonNull(operation, "operation must not be null");
        policyVersion = optionalIdentifier(policyVersion, "policyVersion");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        descriptorFingerprint = optionalSha(descriptorFingerprint, "descriptorFingerprint");
        sourceEvidenceHash = optionalSha(sourceEvidenceHash, "sourceEvidenceHash");
        metadata = CredentialContractSupport.boundedMetadata(metadata, "metadata");
    }

    public String canonicalJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
            .append("\"status\":").append(CredentialContractSupport.json(status.name()))
            .append(",\"credentialReferenceHash\":")
            .append(CredentialContractSupport.json(credentialReferenceHash))
            .append(",\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"keyId\":").append(CredentialContractSupport.json(keyId))
            .append(",\"versionId\":").append(CredentialContractSupport.json(versionId))
            .append(",\"credentialType\":")
            .append(CredentialContractSupport.json(credentialType.name()))
            .append(",\"operation\":").append(CredentialContractSupport.json(operation.name()))
            .append(",\"policyVersion\":")
            .append(CredentialContractSupport.json(policyVersion))
            .append(",\"resolvedAt\":").append(CredentialContractSupport.instant(resolvedAt))
            .append(",\"validUntil\":").append(CredentialContractSupport.instant(validUntil))
            .append(",\"descriptorFingerprint\":")
            .append(CredentialContractSupport.json(descriptorFingerprint))
            .append(",\"sourceEvidenceHash\":")
            .append(CredentialContractSupport.json(sourceEvidenceHash))
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

    public boolean productionExecutionAuthorized() {
        return false;
    }

    public boolean authorizationSatisfied() {
        return false;
    }

    public boolean auditRecorded() {
        return false;
    }

    private static String optionalIdentifier(String value, String name) {
        return value == null ? null : CredentialContractSupport.requireIdentifier(value, name);
    }

    private static String optionalSha(String value, String name) {
        return value == null ? null : CredentialContractSupport.requireSha256(value, name);
    }
}
