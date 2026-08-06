package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceControlHealthContracts
    .ControlHealthView;

/** Server-owned source for one read-only shared-runtime control-health view. */
@FunctionalInterface
public interface ControlledAutomationGovernanceControlHealthSource {

    ControlHealthView current();
}
