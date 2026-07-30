package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;

import java.util.Objects;

public record CapturedCredentialBindingPlan(
    String providerKey,
    ConnectorOperation operation,
    CredentialMaterialType credentialType,
    String keyId,
    String versionId,
    String credentialReferenceHash,
    String descriptorFingerprint,
    String policyVersion
) {

    public CapturedCredentialBindingPlan {
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        credentialType = Objects.requireNonNull(credentialType, "credentialType must not be null");
        keyId = CredentialContractSupport.requireIdentifier(keyId, "keyId");
        versionId = CredentialContractSupport.requireIdentifier(versionId, "versionId");
        credentialReferenceHash = CredentialContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        descriptorFingerprint = CredentialContractSupport.requireSha256(
            descriptorFingerprint,
            "descriptorFingerprint"
        );
        policyVersion = CredentialContractSupport.requireIdentifier(policyVersion, "policyVersion");
    }

    public String canonicalJson() {
        return new StringBuilder(384)
            .append('{')
            .append("\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"operation\":").append(CredentialContractSupport.json(operation.name()))
            .append(",\"credentialType\":")
            .append(CredentialContractSupport.json(credentialType.name()))
            .append(",\"keyId\":").append(CredentialContractSupport.json(keyId))
            .append(",\"versionId\":").append(CredentialContractSupport.json(versionId))
            .append(",\"credentialReferenceHash\":")
            .append(CredentialContractSupport.json(credentialReferenceHash))
            .append(",\"descriptorFingerprint\":")
            .append(CredentialContractSupport.json(descriptorFingerprint))
            .append(",\"policyVersion\":")
            .append(CredentialContractSupport.json(policyVersion))
            .append('}')
            .toString();
    }

    public String planHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }

    public boolean credentialMaterialPresent() {
        return false;
    }

    public boolean absoluteEndpointPresent() {
        return false;
    }

    public boolean productionTransportEnabled() {
        return false;
    }
}
