package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Short-transaction persistence boundary around one out-of-transaction engine dispatch. */
public interface ApprovalMigrationEngineExecutionStore {

    PreparedDispatch prepare(PrepareRequest request);

    ApprovalMigrationAttempt finalizeOutcome(FinalizeRequest request);

    record PrepareRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public PrepareRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
            requirePositive(expectedFenceRevision, "expectedFenceRevision");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record PreparedDispatch(
        UUID engineRequestId,
        String requestEvidenceHash,
        ApprovalMigrationAttempt attempt,
        long fenceRevision,
        ProcessInstanceMigrationPort.MigrationCommand engineCommand,
        Instant preparedAt,
        String requestId,
        String traceId
    ) {
        public PreparedDispatch {
            engineRequestId = Objects.requireNonNull(
                engineRequestId,
                "engineRequestId must not be null"
            );
            requestEvidenceHash = requireHash(requestEvidenceHash, "requestEvidenceHash");
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            requirePositive(fenceRevision, "fenceRevision");
            engineCommand = Objects.requireNonNull(engineCommand, "engineCommand must not be null");
            preparedAt = Objects.requireNonNull(preparedAt, "preparedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
            if (!attempt.attemptId().equals(engineCommand.attemptId())
                || !attempt.tenantId().equals(engineCommand.tenantId())
                || !attempt.approvalInstanceId().equals(engineCommand.approvalInstanceId())) {
                throw new IllegalArgumentException("prepared dispatch identity is inconsistent");
            }
        }
    }

    record FinalizeRequest(
        PreparedDispatch prepared,
        FinalDisposition disposition,
        boolean engineCallAttempted,
        boolean engineCallReturned,
        boolean engineCallMayHaveOccurred,
        String stableCode,
        String boundedSummary,
        String preDispatchSnapshotHash,
        Instant happenedAt
    ) {
        public FinalizeRequest {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            stableCode = requireText(stableCode, "stableCode", 96);
            boundedSummary = optionalText(boundedSummary, "boundedSummary", 1000);
            preDispatchSnapshotHash = requireHash(
                preDispatchSnapshotHash,
                "preDispatchSnapshotHash"
            );
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            if (happenedAt.isBefore(prepared.preparedAt())) {
                throw new IllegalArgumentException("finalization cannot precede preparation");
            }
            if (engineCallReturned && !engineCallAttempted) {
                throw new IllegalArgumentException("engine call cannot return before attempt");
            }
            if (disposition == FinalDisposition.CALL_RETURNED_AWAITING_VERIFICATION
                && (!engineCallAttempted || !engineCallReturned || engineCallMayHaveOccurred)) {
                throw new IllegalArgumentException("returned call evidence is inconsistent");
            }
            if (disposition == FinalDisposition.AMBIGUOUS_UNKNOWN
                && (engineCallReturned || !engineCallMayHaveOccurred)) {
                throw new IllegalArgumentException("UNKNOWN evidence is inconsistent");
            }
            if (disposition == FinalDisposition.PRE_DISPATCH_REJECTED && engineCallAttempted) {
                throw new IllegalArgumentException("pre-dispatch rejection cannot attempt engine call");
            }
        }
    }

    enum FinalDisposition {
        PRE_DISPATCH_REJECTED,
        ENGINE_REJECTED,
        CALL_RETURNED_AWAITING_VERIFICATION,
        AMBIGUOUS_UNKNOWN
    }

    final class ExecutionConflictException extends RuntimeException {
        public ExecutionConflictException(String message) {
            super(message);
        }

        public ExecutionConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
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
