package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceIncidentReadinessContracts
    .IncidentReadinessView;

import java.time.Instant;

/** Tenant-scoped P6-F composite incident-readiness source. */
@FunctionalInterface
public interface ControlledAutomationGovernanceIncidentReadinessSource {

    IncidentReadinessView readiness(
        String trustedTenantId,
        Instant fromInclusive,
        Instant toExclusive
    );
}
