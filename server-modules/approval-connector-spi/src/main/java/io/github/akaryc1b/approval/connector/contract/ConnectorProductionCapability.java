package io.github.akaryc1b.approval.connector.contract;

/**
 * Closed capability set that must receive explicit ownership before production implementation.
 */
public enum ConnectorProductionCapability {
    PROVIDER_TRANSPORT,
    CREDENTIAL_RESOLUTION,
    TENANT_ROUTING,
    EXECUTION_COORDINATION,
    IDEMPOTENCY,
    PERSISTENCE,
    RETRY_POLICY,
    RECONCILIATION_RECOVERY,
    AUTHORIZATION_INTEGRATION,
    AUDIT_INTEGRATION,
    OBSERVABILITY,
    APPROVAL_STATE_ACTIONS
}
