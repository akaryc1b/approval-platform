package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;

import java.util.UUID;

/** D7 adapter boundary that delegates every claim to the accepted D2 bounded claim service. */
@FunctionalInterface
public interface ApprovalMigrationBoundedClaimCoordinator {

    ClaimResult claim(
        String tenantId,
        UUID intentId,
        int limit,
        String requestId,
        String traceId
    );
}
