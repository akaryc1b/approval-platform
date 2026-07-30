package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable D7 canary, one-shot orchestration, batch and kill-switch evidence. */
public final class ApprovalMigrationOrchestrationEvidence {

    public static final String CANARY_ALGORITHM_VERSION = "CANONICAL_FIRST_V1";
    public static final String ZERO_HASH = "0".repeat(64);

    private ApprovalMigrationOrchestrationEvidence() {
    }

    public enum OrchestrationPhase {
        CANARY,
        BOUNDED
    }

    public enum CanaryGate {
        PENDING,
        RUNNING,
        READY,
        PAUSED
    }

    public enum RunEventType {
        PREPARED,
        DISPATCH_ALLOWED,
        KILL_SWITCH_BLOCKED,
        CANARY_COMPLETED,
        BATCH_RECORDED,
        PAUSED,
        COMPLETED
    }

    public enum PauseReason {
        NONE,
        CANARY_IN_FLIGHT,
        CANARY_UNKNOWN,
        CANARY_RECONCILIATION,
        CANARY_MANUAL_REVIEW,
        CANARY_BINDING_CONFLICT,
        CANARY_NOT_EXACT_TARGET,
        KILL_SWITCH_ACTIVE,
        STALE_KILL_SWITCH_REVISION,
        STALE_ORCHESTRATION_REVISION,
        STALE_WORKER,
        STALE_LEASE,
        TERMINAL_FAILURE,
        MISSING_OR_INCOMPLETE_EVIDENCE,
        EMPTY_BATCH
    }

    public enum AttemptDisposition {
        EXACTLY_COMPLETED,
        UNKNOWN,
        RECONCILING,
        MANUAL_REVIEW_REQUIRED,
        BINDING_CONFLICT,
        TERMINAL_FAILURE,
        IN_FLIGHT,
        KILL_SWITCH_BLOCKED
    }

    public record CanarySelection(
        UUID selectionId,
        String tenantId,
        UUID planId,
        UUID intentId,
        String algorithmVersion,
        int sequenceNo,
        UUID approvalInstanceId,
        String planHash,
        String instanceEvidenceHash,
        String selectionEvidenceHash,
        Instant recordedAt,
        String requestId,
        String traceId
    ) {
        public CanarySelection {
            selectionId = Objects.requireNonNull(selectionId, "selectionId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            algorithmVersion = text(algorithmVersion, "algorithmVersion", 64);
            if (!CANARY_ALGORITHM_VERSION.equals(algorithmVersion) || sequenceNo != 1) {
                throw new IllegalArgumentException("D7 canary must use canonical sequence one");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            planHash = hash(planHash, "planHash");
            instanceEvidenceHash = hash(instanceEvidenceHash, "instanceEvidenceHash");
            selectionEvidenceHash = hash(selectionEvidenceHash, "selectionEvidenceHash");
            recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record OrchestrationRun(
        UUID runId,
        String tenantId,
        UUID planId,
        UUID intentId,
        long runRevision,
        OrchestrationPhase phase,
        int requestedLimit,
        UUID canarySelectionId,
        long expectedKillSwitchRevision,
        String predecessorHash,
        String requestHash,
        String runEvidenceHash,
        Instant startedAt,
        String requestId,
        String traceId
    ) {
        public OrchestrationRun {
            runId = Objects.requireNonNull(runId, "runId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            positive(runRevision, "runRevision");
            phase = Objects.requireNonNull(phase, "phase must not be null");
            if (requestedLimit < 1 || requestedLimit > 100) {
                throw new IllegalArgumentException("requestedLimit must be between 1 and 100");
            }
            canarySelectionId = Objects.requireNonNull(
                canarySelectionId,
                "canarySelectionId must not be null"
            );
            positive(expectedKillSwitchRevision, "expectedKillSwitchRevision");
            predecessorHash = hash(predecessorHash, "predecessorHash");
            requestHash = hash(requestHash, "requestHash");
            runEvidenceHash = hash(runEvidenceHash, "runEvidenceHash");
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record OrchestrationEvent(
        UUID eventId,
        String tenantId,
        UUID runId,
        long sequence,
        RunEventType eventType,
        PauseReason pauseReason,
        UUID attemptId,
        String predecessorHash,
        String eventEvidenceHash,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public OrchestrationEvent {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            runId = Objects.requireNonNull(runId, "runId must not be null");
            positive(sequence, "sequence");
            eventType = Objects.requireNonNull(eventType, "eventType must not be null");
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            if (eventType == RunEventType.PAUSED || eventType == RunEventType.KILL_SWITCH_BLOCKED) {
                if (pauseReason == PauseReason.NONE) {
                    throw new IllegalArgumentException("paused orchestration event requires a reason");
                }
            } else if (pauseReason != PauseReason.NONE) {
                throw new IllegalArgumentException("non-paused orchestration event cannot carry a reason");
            }
            predecessorHash = hash(predecessorHash, "predecessorHash");
            eventEvidenceHash = hash(eventEvidenceHash, "eventEvidenceHash");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record BoundedBatch(
        UUID batchEvidenceId,
        String tenantId,
        UUID runId,
        UUID claimBatchId,
        int requestedLimit,
        List<UUID> attemptIds,
        List<AttemptDisposition> dispositions,
        String predecessorHash,
        String batchEvidenceHash,
        Instant recordedAt,
        String requestId,
        String traceId
    ) {
        public BoundedBatch {
            batchEvidenceId = Objects.requireNonNull(
                batchEvidenceId,
                "batchEvidenceId must not be null"
            );
            tenantId = text(tenantId, "tenantId", 128);
            runId = Objects.requireNonNull(runId, "runId must not be null");
            claimBatchId = Objects.requireNonNull(claimBatchId, "claimBatchId must not be null");
            if (requestedLimit < 1 || requestedLimit > 100) {
                throw new IllegalArgumentException("requestedLimit must be between 1 and 100");
            }
            attemptIds = attemptIds == null ? List.of() : List.copyOf(attemptIds);
            dispositions = dispositions == null ? List.of() : List.copyOf(dispositions);
            if (attemptIds.size() != dispositions.size()
                || attemptIds.size() > requestedLimit
                || attemptIds.stream().anyMatch(Objects::isNull)
                || dispositions.stream().anyMatch(Objects::isNull)
                || attemptIds.stream().distinct().count() != attemptIds.size()) {
                throw new IllegalArgumentException("bounded batch evidence is inconsistent");
            }
            predecessorHash = hash(predecessorHash, "predecessorHash");
            batchEvidenceHash = hash(batchEvidenceHash, "batchEvidenceHash");
            recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record KillSwitchObservation(
        UUID observationId,
        String tenantId,
        UUID runId,
        UUID attemptId,
        long expectedRevision,
        long observedRevision,
        boolean enabled,
        boolean dispatchAllowed,
        String reasonCode,
        String requestHash,
        String observationEvidenceHash,
        Instant observedAt,
        String requestId,
        String traceId
    ) {
        public KillSwitchObservation {
            observationId = Objects.requireNonNull(
                observationId,
                "observationId must not be null"
            );
            tenantId = text(tenantId, "tenantId", 128);
            runId = Objects.requireNonNull(runId, "runId must not be null");
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            positive(expectedRevision, "expectedRevision");
            positive(observedRevision, "observedRevision");
            reasonCode = text(reasonCode, "reasonCode", 64);
            boolean derivedAllowed = !enabled && expectedRevision == observedRevision;
            if (dispatchAllowed != derivedAllowed) {
                throw new IllegalArgumentException("kill-switch dispatch decision is not server-derived");
            }
            requestHash = hash(requestHash, "requestHash");
            observationEvidenceHash = hash(
                observationEvidenceHash,
                "observationEvidenceHash"
            );
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    private static void positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String hash(String value, String name) {
        String normalized = text(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String optional(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : text(value, name, maximum);
    }

    private static String text(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
