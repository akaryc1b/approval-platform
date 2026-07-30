package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded evidence produced by one reconciliation observation.
 */
public record ConnectorReconciliationEvidence(
    String originalRequestId,
    String originalIdempotencyKey,
    String originalPayloadHash,
    ConnectorOperation originalOperation,
    ConnectorOutcome originalOutcome,
    String providerRequestId,
    ReconciliationStatus status,
    Instant observedAt,
    Map<String, String> details
) {

    public ConnectorReconciliationEvidence {
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
                "reconciliation evidence must bind a TIMEOUT or UNKNOWN result"
            );
        }
        providerRequestId = ConnectorContractSupport.optionalText(
            providerRequestId,
            "providerRequestId",
            128
        );
        status = Objects.requireNonNull(status, "status must not be null");
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
        details = ConnectorContractSupport.boundedMetadata(
            details,
            "reconciliation details",
            8,
            64,
            256,
            true
        );
    }

    public String canonicalJson() {
        StringBuilder json = new StringBuilder(512)
            .append('{')
            .append("\"originalRequestId\":")
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
            .append(",\"status\":")
            .append(ConnectorContractSupport.json(status.name()))
            .append(",\"observedAt\":")
            .append(ConnectorContractSupport.json(observedAt.toString()))
            .append(",\"details\":{");
        int index = 0;
        for (var entry : details.entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            json.append(ConnectorContractSupport.json(entry.getKey()))
                .append(':')
                .append(ConnectorContractSupport.json(entry.getValue()));
        }
        return json.append("}}").toString();
    }

    public String evidenceHash() {
        return CanonicalPayloadHash.sha256Utf8(canonicalJson());
    }
}
