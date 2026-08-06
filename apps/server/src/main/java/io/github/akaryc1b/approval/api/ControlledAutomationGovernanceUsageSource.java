package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceUsageContracts.UsageView;

/** Server-owned tenant-scoped source for read-only process-local AI usage. */
@FunctionalInterface
public interface ControlledAutomationGovernanceUsageSource {

    UsageView current(String trustedTenantId);
}
