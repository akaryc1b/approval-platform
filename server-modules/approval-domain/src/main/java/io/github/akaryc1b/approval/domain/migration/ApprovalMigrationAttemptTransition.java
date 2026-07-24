package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;

import java.time.Instant;
import java.util.Objects;

/** Evidence supplied to one current-attempt CAS transition. */
public record ApprovalMigrationAttemptTransition(
    AttemptStatus status,
    EngineOutcome engineOutcome,
    String leaseOwner,
    Instant leaseUntil,
    String engineRequestReference,
    FailureClass failureClass,
    String errorSummary,
    Instant happenedAt
) {
    public ApprovalMigrationAttemptTransition {
        status = Objects.requireNonNull(status, "status must not be null");
        engineOutcome = Objects.requireNonNull(engineOutcome, "engineOutcome must not be null");
        leaseOwner = ApprovalMigrationRules.optionalText(leaseOwner, "leaseOwner", 200);
        engineRequestReference = ApprovalMigrationRules.optionalText(
            engineRequestReference,
            "engineRequestReference",
            256
        );
        failureClass = Objects.requireNonNull(failureClass, "failureClass must not be null");
        errorSummary = ApprovalMigrationRules.optionalText(errorSummary, "errorSummary", 1000);
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        ApprovalMigrationRules.validateAttemptEvidence(
            status, engineOutcome, leaseOwner, leaseUntil,
            engineRequestReference, failureClass, errorSummary
        );
    }
}
