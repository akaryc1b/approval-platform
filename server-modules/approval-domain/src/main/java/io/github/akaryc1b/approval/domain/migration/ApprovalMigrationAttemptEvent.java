package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only attempt creation or transition evidence. */
public record ApprovalMigrationAttemptEvent(
    UUID eventId,
    String tenantId,
    UUID attemptId,
    long revision,
    AttemptStatus fromStatus,
    AttemptStatus toStatus,
    EngineOutcome engineOutcome,
    String leaseActor,
    String leaseOwner,
    Instant leaseUntil,
    String engineRequestReference,
    FailureClass failureClass,
    String errorSummary,
    Instant happenedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationAttemptEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        engineOutcome = Objects.requireNonNull(engineOutcome, "engineOutcome must not be null");
        leaseActor = ApprovalMigrationRules.optionalText(leaseActor, "leaseActor", 200);
        leaseOwner = ApprovalMigrationRules.optionalText(leaseOwner, "leaseOwner", 200);
        engineRequestReference = ApprovalMigrationRules.optionalText(
            engineRequestReference,
            "engineRequestReference",
            256
        );
        failureClass = Objects.requireNonNull(failureClass, "failureClass must not be null");
        errorSummary = ApprovalMigrationRules.optionalText(errorSummary, "errorSummary", 1000);
        if ((leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException("attempt event lease evidence is incomplete");
        }
        if (toStatus != AttemptStatus.CLAIMED && (leaseOwner != null || leaseUntil != null)) {
            throw new IllegalArgumentException("only a CLAIMED event can retain resulting lease evidence");
        }
        if (fromStatus == null) {
            if (revision != 1 || toStatus != AttemptStatus.PENDING
                || engineOutcome != EngineOutcome.NOT_REQUESTED) {
                throw new IllegalArgumentException("initial attempt event must create PENDING revision 1");
            }
        } else {
            if (revision < 2) {
                throw new IllegalArgumentException("transition event revision must exceed one");
            }
            ApprovalMigrationProtocol.requireAttemptTransition(fromStatus, toStatus);
        }
        boolean failed = toStatus == AttemptStatus.UNKNOWN
            || toStatus == AttemptStatus.RECONCILING
            || toStatus == AttemptStatus.BLOCKED_STALE
            || toStatus == AttemptStatus.FAILED_RETRYABLE
            || toStatus == AttemptStatus.FAILED_TERMINAL;
        if (failed != (failureClass != FailureClass.NONE && errorSummary != null)) {
            throw new IllegalArgumentException("attempt event failure evidence is inconsistent");
        }
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }

    /** Backward-compatible constructor for pre-M5-B4 sparse event evidence. */
    public ApprovalMigrationAttemptEvent(
        UUID eventId,
        String tenantId,
        UUID attemptId,
        long revision,
        AttemptStatus fromStatus,
        AttemptStatus toStatus,
        EngineOutcome engineOutcome,
        FailureClass failureClass,
        String errorSummary,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        this(
            eventId, tenantId, attemptId, revision, fromStatus, toStatus, engineOutcome,
            null, null, null, null, failureClass, errorSummary, happenedAt, requestId, traceId
        );
    }

    public ApprovalMigrationAttemptEvent withDurableEvidence(
        ApprovalMigrationAttempt attempt,
        String actor
    ) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (!tenantId.equals(attempt.tenantId()) || !attemptId.equals(attempt.attemptId())
            || revision != attempt.revision() || toStatus != attempt.status()
            || engineOutcome != attempt.engineOutcome() || failureClass != attempt.failureClass()
            || !Objects.equals(errorSummary, attempt.errorSummary())
            || !happenedAt.equals(attempt.updatedAt())) {
            throw new IllegalArgumentException("attempt event does not match durable attempt state");
        }
        String normalizedActor = ApprovalMigrationRules.optionalText(actor, "leaseActor", 200);
        boolean leaseTransition = toStatus == AttemptStatus.CLAIMED
            || fromStatus == AttemptStatus.CLAIMED;
        if (leaseTransition != (normalizedActor != null)) {
            throw new IllegalArgumentException("lease transition requires exactly one lease actor");
        }
        return new ApprovalMigrationAttemptEvent(
            eventId, tenantId, attemptId, revision, fromStatus, toStatus, engineOutcome,
            normalizedActor, attempt.leaseOwner(), attempt.leaseUntil(),
            attempt.engineRequestReference(), failureClass, errorSummary,
            happenedAt, requestId, traceId
        );
    }
}
