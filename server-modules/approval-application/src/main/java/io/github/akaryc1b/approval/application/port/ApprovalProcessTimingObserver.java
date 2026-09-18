package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;

import java.time.Instant;

/**
 * Optional local observation of a successful terminal projection mutation, not a durable event.
 * Called after the store's writes, inside the caller's transaction. Consumers must defer success
 * publication until commit, handle rollback/savepoints, and never perform network or database IO.
 * Only terminal outcome and persisted timestamps are supplied, never business or tenant identity.
 * Missing timing evidence must be omitted rather than represented as zero.
 */
@FunctionalInterface
public interface ApprovalProcessTimingObserver {

    ApprovalProcessTimingObserver NONE = (outcome, createdAt, terminalAt) -> { };

    void terminal(InstanceStatus outcome, Instant createdAt, Instant terminalAt);
}
