package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

/**
 * Client-safe correlation evidence for reconciling an uncertain provider execution.
 */
public record ConnectorReconciliationRequest(
    String reconciliationRequestId,
    String traceId,
    String originalRequestId,
    String originalIdempotencyKey,
    String originalPayloadHash,
    ConnectorOperation originalOperation,
    ConnectorOutcome originalOutcome,
    String providerRequestId
) {

    public ConnectorReconciliationRequest {
        reconciliationRequestId = ConnectorContractSupport.requireSafeIdentifier(
            reconciliationRequestId,
            "reconciliationRequestId"
        );
        traceId = ConnectorContractSupport.optionalText(traceId, "traceId", 128);
        originalRequestId = ConnectorContractSupport.requireSafeIdentifier(
            originalRequestId,
            "originalRequestId"
        );
        originalIdempotencyKey = ConnectorContractSupport.requireSafeIdentifier(
            originalIdempotencyKey,
            "originalIdempotencyKey"
        );
        originalPayloadHash = ConnectorContractSupport.requireSha256(
            originalPayloadHash,
            "originalPayloadHash"
        );
        originalOperation = Objects.requireNonNull(
            originalOperation,
            "originalOperation must not be null"
        );
        originalOutcome = Objects.requireNonNull(
            originalOutcome,
            "originalOutcome must not be null"
        );
        if (originalOutcome != ConnectorOutcome.TIMEOUT
            && originalOutcome != ConnectorOutcome.UNKNOWN) {
            throw new IllegalArgumentException(
                "only TIMEOUT or UNKNOWN results may enter reconciliation"
            );
        }
        providerRequestId = ConnectorContractSupport.optionalText(
            providerRequestId,
            "providerRequestId",
            128
        );
    }

    public String canonicalJson() {
        return new StringBuilder(384)
            .append('{')
            .append("\"reconciliationRequestId\":")
            .append(ConnectorContractSupport.json(reconciliationRequestId))
            .append(",\"traceId\":")
            .append(traceId == null ? "null" : ConnectorContractSupport.json(traceId))
            .append(",\"originalRequestId\":")
            .append(ConnectorContractSupport.json(originalRequestId))
            .append(",\"originalIdempotencyKey\":")
            .append(ConnectorContractSupport.json(originalIdempotencyKey))
            .append(",\"originalPayloadHash\":")
            .append(ConnectorContractSupport.json(originalPayloadHash))
            .append(",\"originalOperation\":")
            .append(ConnectorContractSupport.json(originalOperation.name()))
            .append(",\"originalOutcome\":")
            .append(ConnectorContractSupport.json(originalOutcome.name()))
            .append(",\"providerRequestId\":")
            .append(providerRequestId == null
                ? "null"
                : ConnectorContractSupport.json(providerRequestId))
            .append('}')
            .toString();
    }
}
