package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;

import java.time.Instant;

/** Server-owned source for one bounded tenant-scoped durable governance history view. */
@FunctionalInterface
public interface ControlledAutomationGovernanceHistorySource {

    HistoryView history(String trustedTenantId, Instant fromInclusive, Instant toExclusive);
}
