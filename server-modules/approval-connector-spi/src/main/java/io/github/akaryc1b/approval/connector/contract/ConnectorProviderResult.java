package io.github.akaryc1b.approval.connector.contract;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded provider-owned evidence. It must not contain credentials or high-cardinality metrics.
 */
public record ConnectorProviderResult(
    String providerRequestId,
    int statusCode,
    Instant completedAt,
    Map<String, String> metadata
) {

    public ConnectorProviderResult {
        providerRequestId = ConnectorContractSupport.optionalText(
            providerRequestId,
            "providerRequestId",
            128
        );
        if (statusCode != 0 && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode must be 0 or a valid HTTP status");
        }
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        metadata = ConnectorContractSupport.boundedMetadata(
            metadata,
            "provider metadata",
            8,
            64,
            256,
            true
        );
    }
}
