package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

public record ConnectorReconciliationResult<T>(
    ReconciliationStatus status,
    T value,
    ConnectorProviderResult providerResult,
    ConnectorReconciliationEvidence evidence,
    ConnectorError error
) {

    public ConnectorReconciliationResult {
        status = Objects.requireNonNull(status, "status must not be null");
        providerResult = Objects.requireNonNull(
            providerResult,
            "providerResult must not be null"
        );
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        if (status != evidence.status()) {
            throw new IllegalArgumentException("result status does not match evidence status");
        }
        if (status == ReconciliationStatus.CONFIRMED_SUCCESS) {
            value = Objects.requireNonNull(value, "confirmed success value must not be null");
            if (error != null) {
                throw new IllegalArgumentException(
                    "confirmed success must not contain an error"
                );
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException(
                    "non-success reconciliation must not contain a value"
                );
            }
            error = Objects.requireNonNull(error, "non-success error must not be null");
        }
    }

    public ReconciliationDecision decision() {
        return switch (status) {
            case CONFIRMED_SUCCESS -> ReconciliationDecision.COMPLETE_SUCCESS;
            case CONFIRMED_REJECTION, CONFIRMED_PERMANENT_FAILURE ->
                ReconciliationDecision.COMPLETE_FAILURE;
            case STILL_UNKNOWN, NOT_FOUND -> ReconciliationDecision.RECONCILE_AGAIN;
            case CONFLICT -> ReconciliationDecision.MANUAL_REVIEW;
        };
    }

    /**
     * Reconciliation never directly authorizes replay of the original side effect.
     */
    public boolean automaticRetryAllowed() {
        return false;
    }
}
