package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.AttemptDisposition;

import java.util.Objects;
import java.util.UUID;

/** One bounded D3 -> D4 -> D5 attempt pipeline. It never retries an ambiguous dispatch. */
@FunctionalInterface
public interface ApprovalMigrationAttemptPipeline {

    PipelineResult process(PipelineRequest request);

    record PipelineRequest(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationCommandFence fence,
        String workerId,
        String requestId,
        String traceId
    ) {
        public PipelineRequest {
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            fence = Objects.requireNonNull(fence, "fence must not be null");
            workerId = requireText(workerId, "workerId", 200);
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
            if (!attempt.tenantId().equals(fence.tenantId())
                || !attempt.attemptId().equals(fence.attemptId())) {
                throw new IllegalArgumentException("attempt and command fence lineage mismatch");
            }
        }
    }

    record PipelineResult(
        UUID attemptId,
        AttemptDisposition disposition,
        UUID verificationId,
        UUID completionId,
        UUID conflictId
    ) {
        public PipelineResult {
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            if (disposition == AttemptDisposition.EXACTLY_COMPLETED
                && (verificationId == null || completionId == null || conflictId != null)) {
                throw new IllegalArgumentException("exact completion evidence is incomplete");
            }
            if (disposition == AttemptDisposition.BINDING_CONFLICT
                && (verificationId == null || conflictId == null || completionId != null)) {
                throw new IllegalArgumentException("binding conflict evidence is incomplete");
            }
        }
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
