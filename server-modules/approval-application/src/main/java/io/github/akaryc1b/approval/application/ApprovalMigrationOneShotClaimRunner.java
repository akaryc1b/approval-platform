package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationAttemptClaimService.ClaimCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;

import java.util.Objects;

/** Internal one-shot worker adapter. It has no scheduler and is disabled by default. */
public final class ApprovalMigrationOneShotClaimRunner {

    private final ApprovalMigrationAttemptClaimService claims;
    private final boolean enabled;

    public ApprovalMigrationOneShotClaimRunner(
        ApprovalMigrationAttemptClaimService claims,
        boolean enabled
    ) {
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.enabled = enabled;
    }

    public ClaimResult runOnce(ClaimCommand command) {
        if (!enabled) {
            throw new MigrationWorkerDisabledException(
                "migration worker is disabled and production execution is not authorized"
            );
        }
        return claims.claim(command);
    }

    public boolean enabled() {
        return enabled;
    }

    public static final class MigrationWorkerDisabledException extends RuntimeException {
        public MigrationWorkerDisabledException(String message) {
            super(message);
        }
    }
}
