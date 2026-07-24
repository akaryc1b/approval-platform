package io.github.akaryc1b.approval.connector.contract;

/**
 * Production capabilities that remain deliberately blocked after M6-A contract validation.
 */
public enum ConnectorFoundationBlockedCapability {
    REAL_PROVIDER_TRANSPORT,
    PRODUCTION_CREDENTIALS,
    PERSISTENCE,
    CONNECTOR_WORKER,
    AUTOMATIC_EXECUTION,
    AUTOMATIC_RETRY,
    HEALTH_BASED_ROUTING,
    APPROVAL_STATE_MUTATION
}
