package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;

import java.util.Objects;

/**
 * Internal server-owned exact material request. It is never a Controller DTO.
 */
public record CredentialMaterialRequest(
    CredentialReference credentialReference,
    String tenantId,
    String providerKey,
    String routePlanHash,
    String credentialBindingHash,
    CredentialMaterialVersion expectedVersion,
    CredentialMaterialType materialType,
    ConnectorOperation operation,
    String protocolProfile,
    String capability,
    CredentialMaterialEnvironment environment,
    String policyRevision
) {

    public CredentialMaterialRequest {
        credentialReference = Objects.requireNonNull(
            credentialReference,
            "credentialReference must not be null"
        );
        tenantId = CredentialContractSupport.requireIdentifier(tenantId, "tenantId");
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        routePlanHash = CredentialContractSupport.requireSha256(routePlanHash, "routePlanHash");
        credentialBindingHash = CredentialContractSupport.requireSha256(
            credentialBindingHash,
            "credentialBindingHash"
        );
        expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion must not be null");
        materialType = Objects.requireNonNull(materialType, "materialType must not be null");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        protocolProfile = CredentialContractSupport.requireIdentifier(
            protocolProfile,
            "protocolProfile"
        );
        capability = CredentialContractSupport.requireIdentifier(capability, "capability");
        environment = Objects.requireNonNull(environment, "environment must not be null");
        policyRevision = CredentialContractSupport.requireIdentifier(
            policyRevision,
            "policyRevision"
        );
        if (!providerKey.equals(credentialReference.providerKey())) {
            throw new IllegalArgumentException("credential reference belongs to another provider");
        }
    }

    public String tenantHash() {
        return CanonicalPayloadHash.sha256Utf8("tenant\n" + tenantId);
    }

    public String credentialReferenceHash() {
        return CanonicalPayloadHash.sha256Utf8(
            credentialReference.providerKey() + "\n" + credentialReference.referenceId()
        );
    }

    public String canonicalEvidenceJson() {
        return new StringBuilder(768)
            .append('{')
            .append("\"tenantHash\":").append(CredentialContractSupport.json(tenantHash()))
            .append(",\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"credentialReferenceHash\":")
            .append(CredentialContractSupport.json(credentialReferenceHash()))
            .append(",\"routePlanHash\":").append(CredentialContractSupport.json(routePlanHash))
            .append(",\"credentialBindingHash\":")
            .append(CredentialContractSupport.json(credentialBindingHash))
            .append(",\"versionReference\":")
            .append(CredentialContractSupport.json(expectedVersion.versionReference()))
            .append(",\"versionEvidenceHash\":")
            .append(CredentialContractSupport.json(expectedVersion.versionEvidenceHash()))
            .append(",\"materialType\":")
            .append(CredentialContractSupport.json(materialType.name()))
            .append(",\"operation\":").append(CredentialContractSupport.json(operation.name()))
            .append(",\"protocolProfile\":")
            .append(CredentialContractSupport.json(protocolProfile))
            .append(",\"capability\":").append(CredentialContractSupport.json(capability))
            .append(",\"environment\":")
            .append(CredentialContractSupport.json(environment.name()))
            .append(",\"policyRevision\":")
            .append(CredentialContractSupport.json(policyRevision))
            .append('}')
            .toString();
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidenceJson());
    }

    @Override
    public String toString() {
        return "CredentialMaterialRequest[providerKey=" + providerKey
            + ", materialType=" + materialType
            + ", versionReference=" + expectedVersion.versionReference()
            + ", evidenceHash=" + evidenceHash() + "]";
    }
}
