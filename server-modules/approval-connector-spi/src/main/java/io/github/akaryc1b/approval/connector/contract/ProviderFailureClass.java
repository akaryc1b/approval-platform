package io.github.akaryc1b.approval.connector.contract;

public enum ProviderFailureClass {
    VALIDATION,
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMIT,
    TRANSIENT,
    PERMANENT,
    TIMEOUT,
    UNKNOWN
}
