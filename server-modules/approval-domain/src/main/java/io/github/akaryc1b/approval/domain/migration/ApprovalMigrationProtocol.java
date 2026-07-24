package io.github.akaryc1b.approval.domain.migration;

import java.util.EnumSet;
import java.util.Set;

/** Closed M5 migration persistence vocabulary and state transitions. */
public final class ApprovalMigrationProtocol {

    private static final Set<IntentStatus> TERMINAL_INTENTS = EnumSet.of(
        IntentStatus.COMPLETED,
        IntentStatus.PARTIAL,
        IntentStatus.FAILED,
        IntentStatus.CANCELLED
    );
    private static final Set<AttemptStatus> TERMINAL_ATTEMPTS = EnumSet.of(
        AttemptStatus.SUCCEEDED,
        AttemptStatus.BLOCKED_STALE,
        AttemptStatus.FAILED_RETRYABLE,
        AttemptStatus.FAILED_TERMINAL,
        AttemptStatus.CANCELLED
    );
    private static final Set<ReconciliationStatus> TERMINAL_RECONCILIATIONS = EnumSet.of(
        ReconciliationStatus.RESOLVED_SOURCE,
        ReconciliationStatus.RESOLVED_TARGET,
        ReconciliationStatus.RESOLVED_TERMINAL,
        ReconciliationStatus.UNRESOLVED
    );

    private ApprovalMigrationProtocol() {
    }

    public enum IntentStatus {
        PENDING, RUNNING, RECONCILING, COMPLETED, PARTIAL, FAILED, CANCELLED
    }

    public enum AttemptStatus {
        PENDING, CLAIMED, ENGINE_REQUESTED, VERIFYING, UNKNOWN, RECONCILING,
        SUCCEEDED, BLOCKED_STALE, FAILED_RETRYABLE, FAILED_TERMINAL, CANCELLED
    }

    public enum EngineOutcome {
        NOT_REQUESTED, ACCEPTED, REJECTED, CONFIRMED, UNKNOWN, VERIFICATION_MISMATCH
    }

    public enum VerificationOutcome {
        SOURCE_CONFIRMED, TARGET_CONFIRMED, TARGET_TERMINAL_CONFIRMED,
        MISSING_NO_EVIDENCE, MIXED_RECONCILIATION_REQUIRED, INCOMPLETE_RECONCILIATION_REQUIRED
    }

    public enum ReconciliationStatus {
        OPEN, MANUAL_REVIEW_REQUIRED, RESOLVED_SOURCE, RESOLVED_TARGET,
        RESOLVED_TERMINAL, UNRESOLVED
    }

    public enum FailureClass {
        NONE, STALE_ASSESSMENT, STALE_BINDING, STALE_INSTANCE_STATE,
        ENGINE_REJECTED, ENGINE_OUTCOME_UNKNOWN, VERIFICATION_MISMATCH,
        PLATFORM_EVIDENCE_CONFLICT, RECONCILIATION_REQUIRED, INTERNAL
    }

    public static void requireIntentTransition(IntentStatus from, IntentStatus to) {
        boolean allowed = switch (from) {
            case PENDING -> to == IntentStatus.RUNNING || to == IntentStatus.CANCELLED
                || to == IntentStatus.FAILED;
            case RUNNING -> to == IntentStatus.RECONCILING || to == IntentStatus.COMPLETED
                || to == IntentStatus.PARTIAL || to == IntentStatus.FAILED
                || to == IntentStatus.CANCELLED;
            case RECONCILING -> to == IntentStatus.COMPLETED || to == IntentStatus.PARTIAL
                || to == IntentStatus.FAILED;
            case COMPLETED, PARTIAL, FAILED, CANCELLED -> false;
        };
        ApprovalMigrationRules.requireTransition(allowed, "intent", from, to);
    }

    public static void requireAttemptTransition(AttemptStatus from, AttemptStatus to) {
        boolean allowed = switch (from) {
            case PENDING -> to == AttemptStatus.CLAIMED || to == AttemptStatus.BLOCKED_STALE
                || to == AttemptStatus.CANCELLED;
            case CLAIMED -> to == AttemptStatus.CLAIMED || to == AttemptStatus.ENGINE_REQUESTED
                || to == AttemptStatus.BLOCKED_STALE || to == AttemptStatus.FAILED_RETRYABLE
                || to == AttemptStatus.FAILED_TERMINAL || to == AttemptStatus.CANCELLED;
            case ENGINE_REQUESTED -> to == AttemptStatus.VERIFYING || to == AttemptStatus.UNKNOWN
                || to == AttemptStatus.FAILED_RETRYABLE || to == AttemptStatus.FAILED_TERMINAL;
            case VERIFYING -> to == AttemptStatus.SUCCEEDED || to == AttemptStatus.RECONCILING
                || to == AttemptStatus.FAILED_TERMINAL;
            case UNKNOWN -> to == AttemptStatus.RECONCILING;
            case RECONCILING -> to == AttemptStatus.SUCCEEDED || to == AttemptStatus.BLOCKED_STALE
                || to == AttemptStatus.FAILED_TERMINAL;
            case SUCCEEDED, BLOCKED_STALE, FAILED_RETRYABLE, FAILED_TERMINAL, CANCELLED -> false;
        };
        ApprovalMigrationRules.requireTransition(allowed, "attempt", from, to);
    }

    public static void requireReconciliationTransition(
        ReconciliationStatus from,
        ReconciliationStatus to
    ) {
        boolean allowed = from == ReconciliationStatus.OPEN
            && (to == ReconciliationStatus.MANUAL_REVIEW_REQUIRED || terminal(to))
            || from == ReconciliationStatus.MANUAL_REVIEW_REQUIRED && terminal(to);
        ApprovalMigrationRules.requireTransition(allowed, "reconciliation", from, to);
    }

    public static boolean terminal(IntentStatus status) {
        return TERMINAL_INTENTS.contains(status);
    }

    public static boolean terminal(AttemptStatus status) {
        return TERMINAL_ATTEMPTS.contains(status);
    }

    public static boolean terminal(ReconciliationStatus status) {
        return TERMINAL_RECONCILIATIONS.contains(status);
    }
}
