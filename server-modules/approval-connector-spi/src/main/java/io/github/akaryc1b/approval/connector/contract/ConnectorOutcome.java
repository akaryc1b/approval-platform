package io.github.akaryc1b.approval.connector.contract;

public enum ConnectorOutcome {
    SUCCESS,
    REJECTED,
    RATE_LIMITED,
    RETRYABLE_PROVIDER_FAILURE,
    PERMANENT_PROVIDER_FAILURE,
    TIMEOUT,
    UNKNOWN
}
