package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

public record IdempotencyEvidence(
    String idempotencyKey,
    String requestHash,
    IdempotencyResult result
) {

    public IdempotencyEvidence {
        idempotencyKey = ConnectorContractSupport.requireSafeIdentifier(
            idempotencyKey,
            "idempotencyKey"
        );
        requestHash = ConnectorContractSupport.requireSha256(requestHash, "requestHash");
        result = Objects.requireNonNull(result, "result must not be null");
    }

    public enum IdempotencyResult {
        FIRST_SEEN,
        REPLAYED_SAME_RESULT,
        CONFLICT
    }
}
