package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable tenant-scoped M5 migration protocol evidence without engine execution. */
public interface ApprovalMigrationProtocolStore {

    IntentCreationResult createIntent(
        ApprovalMigrationIntent intent,
        ApprovalMigrationIntentEvent initialEvent
    );

    Optional<ApprovalMigrationIntent> findIntent(String tenantId, UUID intentId);

    Optional<ApprovalMigrationIntent> findIntentByIdempotencyKey(
        String tenantId,
        String idempotencyKey
    );

    ApprovalMigrationIntent transitionIntent(
        ApprovalMigrationIntent next,
        long expectedRevision,
        ApprovalMigrationIntentEvent event
    );

    List<ApprovalMigrationIntentEvent> findIntentEvents(String tenantId, UUID intentId);

    AttemptCreationResult createAttempt(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationAttemptEvent initialEvent
    );

    Optional<ApprovalMigrationAttempt> findAttempt(String tenantId, UUID attemptId);

    List<ApprovalMigrationAttempt> findAttempts(String tenantId, UUID intentId);

    ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt next,
        long expectedRevision,
        ApprovalMigrationAttemptEvent event
    );

    List<ApprovalMigrationAttemptEvent> findAttemptEvents(String tenantId, UUID attemptId);

    void appendVerification(ApprovalMigrationVerification verification);

    List<ApprovalMigrationVerification> findVerifications(String tenantId, UUID attemptId);

    void appendReconciliation(ApprovalMigrationReconciliation reconciliation);

    List<ApprovalMigrationReconciliation> findReconciliations(String tenantId, UUID attemptId);

    record IntentCreationResult(
        ApprovalMigrationIntent intent,
        boolean replayedExistingRequest
    ) {
        public IntentCreationResult {
            if (intent == null) {
                throw new IllegalArgumentException("intent must not be null");
            }
        }
    }

    record AttemptCreationResult(
        ApprovalMigrationAttempt attempt,
        boolean replayedExistingRequest
    ) {
        public AttemptCreationResult {
            if (attempt == null) {
                throw new IllegalArgumentException("attempt must not be null");
            }
        }
    }

    final class MigrationProtocolConflictException extends RuntimeException {
        public MigrationProtocolConflictException(String message) {
            super(message);
        }

        public MigrationProtocolConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
