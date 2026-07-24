package io.github.akaryc1b.approval.connector.contract;

/**
 * A decision classifies ownership only. No decision grants production enablement.
 */
public enum ConnectorProductionDecision {
    EXISTING_PLATFORM_BOUNDARY,
    FUTURE_EXPLICIT_GATE,
    SHARED_COORDINATION_REQUIRED,
    BLOCKED
}
