package io.github.akaryc1b.approval.connector.contract;

import java.util.Objects;

public record ConnectorResult<T>(
    ConnectorOutcome outcome,
    T value,
    ConnectorProviderResult providerResult,
    IdempotencyEvidence idempotencyEvidence,
    ConnectorSecurityEvidence securityEvidence,
    ConnectorError error
) {

    public ConnectorResult {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        providerResult = Objects.requireNonNull(providerResult, "providerResult must not be null");
        idempotencyEvidence = Objects.requireNonNull(
            idempotencyEvidence,
            "idempotencyEvidence must not be null"
        );
        if (outcome == ConnectorOutcome.SUCCESS) {
            value = Objects.requireNonNull(value, "successful result value must not be null");
            if (error != null) {
                throw new IllegalArgumentException("successful result must not contain an error");
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException("failed result must not contain a value");
            }
            error = Objects.requireNonNull(error, "failed result error must not be null");
        }
    }

    public static <T> ConnectorResult<T> success(
        T value,
        ConnectorProviderResult providerResult,
        IdempotencyEvidence idempotencyEvidence,
        ConnectorSecurityEvidence securityEvidence
    ) {
        return new ConnectorResult<>(
            ConnectorOutcome.SUCCESS,
            value,
            providerResult,
            idempotencyEvidence,
            securityEvidence,
            null
        );
    }

    public static <T> ConnectorResult<T> failure(
        ConnectorOutcome outcome,
        ConnectorProviderResult providerResult,
        IdempotencyEvidence idempotencyEvidence,
        ConnectorSecurityEvidence securityEvidence,
        ConnectorError error
    ) {
        if (outcome == ConnectorOutcome.SUCCESS) {
            throw new IllegalArgumentException("failure outcome must not be SUCCESS");
        }
        return new ConnectorResult<>(
            outcome,
            null,
            providerResult,
            idempotencyEvidence,
            securityEvidence,
            error
        );
    }

    public RetryDisposition retryDisposition() {
        return switch (outcome) {
            case SUCCESS, REJECTED, PERMANENT_PROVIDER_FAILURE -> RetryDisposition.DO_NOT_RETRY;
            case RATE_LIMITED, RETRYABLE_PROVIDER_FAILURE -> RetryDisposition.RETRY_WITH_BACKOFF;
            case TIMEOUT, UNKNOWN -> RetryDisposition.RECONCILE_BEFORE_RETRY;
        };
    }

    public ConnectorResult<T> withIdempotencyEvidence(IdempotencyEvidence evidence) {
        return new ConnectorResult<>(
            outcome,
            value,
            providerResult,
            evidence,
            securityEvidence,
            error
        );
    }
}
