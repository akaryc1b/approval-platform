package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable orchestration plan evidence. It contains no executable adapter and authorizes no retry.
 */
public record ConnectorOrchestrationPlan(
    String tenantId,
    String providerKey,
    String contractKey,
    ConnectorOperation operation,
    String requestId,
    String traceId,
    String idempotencyKey,
    String canonicalPayloadHash,
    String credentialReferenceHash,
    String registryFingerprint,
    String compatibilityEvidenceHash,
    String selectionEvidenceHash,
    String authorizationEvidenceHash,
    Instant plannedAt
) {

    public ConnectorOrchestrationPlan {
        tenantId = ConnectorContractSupport.requireSafeIdentifier(tenantId, "tenantId");
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        contractKey = ConnectorContractSupport.requireSafeIdentifier(contractKey, "contractKey");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        requestId = ConnectorContractSupport.requireSafeIdentifier(requestId, "requestId");
        traceId = ConnectorContractSupport.optionalText(traceId, "traceId", 128);
        idempotencyKey = ConnectorContractSupport.requireSafeIdentifier(
            idempotencyKey,
            "idempotencyKey"
        );
        canonicalPayloadHash = ConnectorContractSupport.requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        credentialReferenceHash = ConnectorContractSupport.requireSha256(
            credentialReferenceHash,
            "credentialReferenceHash"
        );
        registryFingerprint = ConnectorContractSupport.requireSha256(
            registryFingerprint,
            "registryFingerprint"
        );
        compatibilityEvidenceHash = ConnectorContractSupport.requireSha256(
            compatibilityEvidenceHash,
            "compatibilityEvidenceHash"
        );
        selectionEvidenceHash = ConnectorContractSupport.requireSha256(
            selectionEvidenceHash,
            "selectionEvidenceHash"
        );
        authorizationEvidenceHash = ConnectorContractSupport.requireSha256(
            authorizationEvidenceHash,
            "authorizationEvidenceHash"
        );
        plannedAt = Objects.requireNonNull(plannedAt, "plannedAt must not be null");
    }

    public String canonicalEvidence() {
        return "tenantId=" + tenantId
            + "\nproviderKey=" + providerKey
            + "\ncontractKey=" + contractKey
            + "\noperation=" + operation.name()
            + "\nrequestId=" + requestId
            + "\ntraceId=" + optional(traceId)
            + "\nidempotencyKey=" + idempotencyKey
            + "\ncanonicalPayloadHash=" + canonicalPayloadHash
            + "\ncredentialReferenceHash=" + credentialReferenceHash
            + "\nregistryFingerprint=" + registryFingerprint
            + "\ncompatibilityEvidenceHash=" + compatibilityEvidenceHash
            + "\nselectionEvidenceHash=" + selectionEvidenceHash
            + "\nauthorizationEvidenceHash=" + authorizationEvidenceHash
            + "\nplannedAt=" + plannedAt;
    }

    public String planHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }

    public boolean automaticExecutionAllowed() {
        return false;
    }

    public boolean automaticRetryAllowed() {
        return false;
    }

    public boolean requiresExplicitExecution() {
        return true;
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
