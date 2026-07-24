package io.github.akaryc1b.approval.connector.contract;

/**
 * Closed result set for the production integration ownership gate.
 */
public enum ConnectorProductionIntegrationGateStatus {
    READY_FOR_SCOPED_IMPLEMENTATION_REVIEW,
    FOUNDATION_EVIDENCE_MISMATCH,
    INCOMPLETE_CAPABILITY_MATRIX,
    DUPLICATE_CAPABILITY,
    PROHIBITED_CAPABILITY_OPENED,
    OWNERSHIP_CONFLICT
}
