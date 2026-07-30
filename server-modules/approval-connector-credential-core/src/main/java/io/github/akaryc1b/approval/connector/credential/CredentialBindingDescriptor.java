package io.github.akaryc1b.approval.connector.credential;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;
import io.github.akaryc1b.approval.connector.contract.ConnectorOperation;
import io.github.akaryc1b.approval.connector.contract.CredentialReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CredentialBindingDescriptor(
    CredentialReference reference,
    String tenantId,
    String providerKey,
    CredentialMaterialType credentialType,
    String keyId,
    String versionId,
    CredentialBindingState state,
    Instant notBefore,
    Instant expiresAt,
    Set<ConnectorOperation> allowedOperations,
    String policyVersion,
    Map<String, String> metadata
) {

    public CredentialBindingDescriptor {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        tenantId = CredentialContractSupport.requireIdentifier(tenantId, "tenantId");
        providerKey = CredentialContractSupport.requireIdentifier(providerKey, "providerKey");
        credentialType = Objects.requireNonNull(credentialType, "credentialType must not be null");
        keyId = CredentialContractSupport.requireIdentifier(keyId, "keyId");
        versionId = CredentialContractSupport.requireIdentifier(versionId, "versionId");
        state = Objects.requireNonNull(state, "state must not be null");
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException("expiresAt must be after notBefore");
        }
        if (allowedOperations == null || allowedOperations.isEmpty()) {
            throw new IllegalArgumentException("allowedOperations must not be empty");
        }
        if (allowedOperations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("allowedOperations must not contain null");
        }
        allowedOperations = Set.copyOf(allowedOperations);
        policyVersion = CredentialContractSupport.requireIdentifier(policyVersion, "policyVersion");
        metadata = CredentialContractSupport.boundedMetadata(metadata, "metadata");
        if (!providerKey.equals(reference.providerKey())) {
            throw new IllegalArgumentException("reference belongs to another provider");
        }
    }

    public String referenceHash() {
        return CanonicalPayloadHash.sha256Utf8(
            reference.providerKey() + "\n" + reference.referenceId()
        );
    }

    public String canonicalJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
            .append("\"referenceHash\":").append(CredentialContractSupport.json(referenceHash()))
            .append(",\"tenantId\":").append(CredentialContractSupport.json(tenantId))
            .append(",\"providerKey\":").append(CredentialContractSupport.json(providerKey))
            .append(",\"credentialType\":")
            .append(CredentialContractSupport.json(credentialType.name()))
            .append(",\"keyId\":").append(CredentialContractSupport.json(keyId))
            .append(",\"versionId\":").append(CredentialContractSupport.json(versionId))
            .append(",\"state\":").append(CredentialContractSupport.json(state.name()))
            .append(",\"notBefore\":").append(CredentialContractSupport.instant(notBefore))
            .append(",\"expiresAt\":").append(CredentialContractSupport.instant(expiresAt))
            .append(",\"allowedOperations\":[");
        var operations = new ArrayList<>(allowedOperations);
        operations.sort(Comparator.comparing(Enum::name));
        for (int index = 0; index < operations.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(CredentialContractSupport.json(operations.get(index).name()));
        }
        json.append("]")
            .append(",\"policyVersion\":")
            .append(CredentialContractSupport.json(policyVersion))
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

    public String fingerprint() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }
}
