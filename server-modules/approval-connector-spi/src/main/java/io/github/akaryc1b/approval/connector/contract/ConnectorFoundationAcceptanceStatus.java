package io.github.akaryc1b.approval.connector.contract;

/**
 * Contract-foundation review status. This is not formal milestone acceptance or production enablement.
 */
public enum ConnectorFoundationAcceptanceStatus {
    READY_FOR_FORMAL_ACCEPTANCE_REVIEW,
    INCOMPLETE_CONTRACT_COVERAGE,
    INCOMPLETE_ADMISSION_EVIDENCE,
    REGISTRY_EVIDENCE_MISMATCH
}
