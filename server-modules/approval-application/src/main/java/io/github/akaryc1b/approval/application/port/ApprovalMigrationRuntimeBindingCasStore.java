package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationBindingCasConflictEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationInstanceCompletionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationRuntimeBindingEvidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One short platform transaction for exact-target runtime-binding CAS and completion. */
public interface ApprovalMigrationRuntimeBindingCasStore {

    BindingCasResult complete(CompletionRequest request);

    record CompletionRequest(
        String tenantId,
        UUID attemptId,
        UUID verificationId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        long expectedBindingRevision,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public CompletionRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            verificationId = Objects.requireNonNull(
                verificationId,
                "verificationId must not be null"
            );
            workerId = requireText(workerId, "workerId", 200);
            requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
            requirePositive(expectedFenceRevision, "expectedFenceRevision");
            requirePositive(expectedBindingRevision, "expectedBindingRevision");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record BindingCasResult(
        BindingCasDisposition disposition,
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationRuntimeBindingEvidence bindingEvidence,
        ApprovalMigrationInstanceCompletionEvidence completionEvidence,
        ApprovalMigrationBindingCasConflictEvidence conflictEvidence
    ) {
        public BindingCasResult {
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            boolean completion = disposition == BindingCasDisposition.COMPLETED
                || disposition == BindingCasDisposition.REPLAYED_COMPLETION;
            boolean conflict = disposition == BindingCasDisposition.RECONCILIATION_REQUIRED
                || disposition == BindingCasDisposition.REPLAYED_CONFLICT;
            if (completion != (bindingEvidence != null && completionEvidence != null)
                || conflict != (conflictEvidence != null)
                || completion == conflict) {
                throw new IllegalArgumentException("binding CAS result evidence is inconsistent");
            }
            if (bindingEvidence != null
                && (!bindingEvidence.tenantId().equals(attempt.tenantId())
                    || !bindingEvidence.attemptId().equals(attempt.attemptId()))) {
                throw new IllegalArgumentException("binding evidence attempt lineage mismatch");
            }
            if (completionEvidence != null
                && (!completionEvidence.tenantId().equals(attempt.tenantId())
                    || !completionEvidence.attemptId().equals(attempt.attemptId()))) {
                throw new IllegalArgumentException("completion evidence attempt lineage mismatch");
            }
            if (conflictEvidence != null
                && (!conflictEvidence.tenantId().equals(attempt.tenantId())
                    || !conflictEvidence.attemptId().equals(attempt.attemptId()))) {
                throw new IllegalArgumentException("conflict evidence attempt lineage mismatch");
            }
        }

        public boolean completed() {
            return disposition == BindingCasDisposition.COMPLETED
                || disposition == BindingCasDisposition.REPLAYED_COMPLETION;
        }
    }

    enum BindingCasDisposition {
        COMPLETED,
        RECONCILIATION_REQUIRED,
        REPLAYED_COMPLETION,
        REPLAYED_CONFLICT
    }

    final class BindingCasException extends RuntimeException {
        public BindingCasException(String message) {
            super(message);
        }

        public BindingCasException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
