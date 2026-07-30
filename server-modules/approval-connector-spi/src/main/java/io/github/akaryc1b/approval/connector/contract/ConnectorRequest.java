package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Client-safe operation request. Trusted tenant, operator, authority, audit and credential identity
 * are deliberately absent and must come from {@link TrustedConnectorExecutionContext}.
 */
public record ConnectorRequest<P>(
    String requestId,
    String traceId,
    String idempotencyKey,
    ConnectorOperation operation,
    String canonicalPayloadHash,
    ConnectorSecurityEvidence securityEvidence,
    P payload
) {

    public ConnectorRequest {
        requestId = ConnectorContractSupport.requireSafeIdentifier(requestId, "requestId");
        traceId = ConnectorContractSupport.optionalText(traceId, "traceId", 128);
        idempotencyKey = ConnectorContractSupport.requireSafeIdentifier(
            idempotencyKey,
            "idempotencyKey"
        );
        operation = Objects.requireNonNull(operation, "operation must not be null");
        canonicalPayloadHash = ConnectorContractSupport.requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
        payload = Objects.requireNonNull(payload, "payload must not be null");
        if (securityEvidence != null
            && !canonicalPayloadHash.equals(securityEvidence.canonicalPayloadHash())) {
            throw new IllegalArgumentException("security evidence payload hash does not match request");
        }
    }
}
