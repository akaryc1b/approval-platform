package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;

/** Server-owned source for one read-only AI governance snapshot. */
@FunctionalInterface
public interface ControlledAutomationGovernanceSnapshotSource {

    OperationsView current();
}
