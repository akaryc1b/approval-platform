package io.github.akaryc1b.approval.connector.contract;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Opaque server-issued authorization evidence for one exact connector invocation plan.
 *
 * <p>This record does not replace the platform authorization or audit model. It binds only the
 * connector-local decision inputs required by the deterministic planner.</p>
 */
public record ConnectorInvocationAuthorizationEvidence(
    String authorizationDecisionId,
    String authorizationPolicyVersion,
    String tenantId,
    String providerKey,
    String contractKey,
    ConnectorOperation operation,
    String requestId,
    String idempotencyKey,
    String canonicalPayloadHash,
    String selectionEvidenceHash,
    Instant authorizedAt,
    Instant expiresAt
) {

    private static final Duration MAXIMUM_VALIDITY = Duration.ofMinutes(10);

    public ConnectorInvocationAuthorizationEvidence {
        authorizationDecisionId = ConnectorContractSupport.requireSafeIdentifier(
            authorizationDecisionId,
            "authorizationDecisionId"
        );
        authorizationPolicyVersion = ConnectorContractSupport.requireSafeIdentifier(
            authorizationPolicyVersion,
            "authorizationPolicyVersion"
        );
        tenantId = ConnectorContractSupport.requireSafeIdentifier(tenantId, "tenantId");
        providerKey = ConnectorContractSupport.requireSafeIdentifier(providerKey, "providerKey");
        contractKey = ConnectorContractSupport.requireSafeIdentifier(contractKey, "contractKey");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        requestId = ConnectorContractSupport.requireSafeIdentifier(requestId, "requestId");
        idempotencyKey = ConnectorContractSupport.requireSafeIdentifier(
            idempotencyKey,
            "idempotencyKey"
        );
        canonicalPayloadHash = ConnectorContractSupport.requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        selectionEvidenceHash = ConnectorContractSupport.requireSha256(
            selectionEvidenceHash,
            "selectionEvidenceHash"
        );
        authorizedAt = Objects.requireNonNull(authorizedAt, "authorizedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Duration validity = Duration.between(authorizedAt, expiresAt);
        if (validity.isNegative() || validity.isZero() || validity.compareTo(MAXIMUM_VALIDITY) > 0) {
            throw new IllegalArgumentException(
                "authorization validity must be positive and no longer than ten minutes"
            );
        }
    }

    public boolean validAt(Instant instant) {
        instant = Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(authorizedAt) && !instant.isAfter(expiresAt);
    }

    public String canonicalEvidence() {
        return "authorizationDecisionId=" + authorizationDecisionId
            + "\nauthorizationPolicyVersion=" + authorizationPolicyVersion
            + "\ntenantId=" + tenantId
            + "\nproviderKey=" + providerKey
            + "\ncontractKey=" + contractKey
            + "\noperation=" + operation.name()
            + "\nrequestId=" + requestId
            + "\nidempotencyKey=" + idempotencyKey
            + "\ncanonicalPayloadHash=" + canonicalPayloadHash
            + "\nselectionEvidenceHash=" + selectionEvidenceHash
            + "\nauthorizedAt=" + authorizedAt
            + "\nexpiresAt=" + expiresAt;
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalEvidence());
    }
}
