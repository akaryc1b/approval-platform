package io.github.akaryc1b.approval.connector.contract;

/**
 * Closed result of revalidating a connector orchestration plan immediately before invocation.
 */
public enum ConnectorExecutionAdmissionStatus {
    ADMITTED,
    PLAN_TIME_INVALID,
    REGISTRY_STALE,
    CONTRACT_MISMATCH,
    SELECTION_MISMATCH,
    COMPATIBILITY_MISMATCH,
    BINDING_UNAVAILABLE,
    TRUSTED_CONTEXT_MISMATCH,
    REQUEST_MISMATCH,
    CREDENTIAL_MISMATCH,
    AUTHORIZATION_MISMATCH,
    AUTHORIZATION_EXPIRED
}
