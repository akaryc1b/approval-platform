package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.BoundedBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanaryGate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.KillSwitchObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationRun;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short platform transactions for D7 immutable orchestration evidence. */
public interface ApprovalMigrationOrchestrationStore {

    PreparedOrchestration prepare(PrepareRequest request);

    DispatchAuthorization authorizeDispatch(DispatchRequest request);

    FinalizedOrchestration finalizeRun(FinalizeRequest request);

    record PrepareRequest(
        String tenantId,
        UUID intentId,
        int requestedLimit,
        long expectedRunRevision,
        Snapshot killSwitch,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public PrepareRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            if (requestedLimit < 1 || requestedLimit > 100) {
                throw new IllegalArgumentException("requestedLimit must be between 1 and 100");
            }
            requirePositive(expectedRunRevision, "expectedRunRevision");
            killSwitch = Objects.requireNonNull(killSwitch, "killSwitch must not be null");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record PreparedOrchestration(
        OrchestrationRun run,
        CanarySelection canary,
        CanaryGate canaryGate,
        PauseReason pauseReason,
        OrchestrationEvent latestEvent,
        boolean dispatchEligible,
        boolean replayed,
        boolean finalized
    ) {
        public PreparedOrchestration {
            run = Objects.requireNonNull(run, "run must not be null");
            canary = Objects.requireNonNull(canary, "canary must not be null");
            canaryGate = Objects.requireNonNull(canaryGate, "canaryGate must not be null");
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            latestEvent = Objects.requireNonNull(latestEvent, "latestEvent must not be null");
            if (dispatchEligible && pauseReason != PauseReason.NONE) {
                throw new IllegalArgumentException("dispatch-eligible run cannot be paused");
            }
            if (finalized && latestEvent.eventType().name().equals("PREPARED")) {
                throw new IllegalArgumentException("finalized run cannot end with PREPARED");
            }
        }
    }

    record DispatchRequest(
        OrchestrationRun run,
        UUID attemptId,
        long expectedRunRevision,
        long expectedKillSwitchRevision,
        Snapshot observedKillSwitch,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public DispatchRequest {
            run = Objects.requireNonNull(run, "run must not be null");
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            requirePositive(expectedRunRevision, "expectedRunRevision");
            requirePositive(expectedKillSwitchRevision, "expectedKillSwitchRevision");
            observedKillSwitch = Objects.requireNonNull(
                observedKillSwitch,
                "observedKillSwitch must not be null"
            );
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record DispatchAuthorization(
        KillSwitchObservation observation,
        OrchestrationEvent event,
        boolean allowed,
        PauseReason pauseReason,
        boolean replayed
    ) {
        public DispatchAuthorization {
            observation = Objects.requireNonNull(observation, "observation must not be null");
            event = Objects.requireNonNull(event, "event must not be null");
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            if (allowed != observation.dispatchAllowed()) {
                throw new IllegalArgumentException("dispatch authorization does not match observation");
            }
            if (allowed != (pauseReason == PauseReason.NONE)) {
                throw new IllegalArgumentException("dispatch pause reason is inconsistent");
            }
        }
    }

    record FinalizeRequest(
        PreparedOrchestration prepared,
        ApprovalMigrationClaimBatch claimBatch,
        List<UUID> processedAttemptIds,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public FinalizeRequest {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            processedAttemptIds = processedAttemptIds == null
                ? List.of()
                : List.copyOf(processedAttemptIds);
            if (processedAttemptIds.stream().anyMatch(Objects::isNull)
                || processedAttemptIds.stream().distinct().count() != processedAttemptIds.size()) {
                throw new IllegalArgumentException("processedAttemptIds are invalid");
            }
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
            if (claimBatch == null && !processedAttemptIds.isEmpty()) {
                throw new IllegalArgumentException("processed attempts require D2 claim evidence");
            }
        }
    }

    record FinalizedOrchestration(
        OrchestrationRun run,
        OrchestrationEvent event,
        BoundedBatch batch,
        PauseReason pauseReason,
        boolean planExactlyCompleted,
        boolean replayed
    ) {
        public FinalizedOrchestration {
            run = Objects.requireNonNull(run, "run must not be null");
            event = Objects.requireNonNull(event, "event must not be null");
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
        }
    }

    final class OrchestrationConflictException extends RuntimeException {
        public OrchestrationConflictException(String message) {
            super(message);
        }

        public OrchestrationConflictException(String message, Throwable cause) {
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
