package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable D8 plan-level aggregation, event and completion evidence. */
public final class ApprovalMigrationPlanAggregationEvidence {

    public static final String ZERO_HASH = "0".repeat(64);

    private ApprovalMigrationPlanAggregationEvidence() {
    }

    public enum AggregateStatus {
        NOT_STARTED,
        CANARY_PENDING,
        CANARY_RUNNING,
        BOUNDED_EXECUTION_RUNNING,
        PAUSED,
        KILL_SWITCH_BLOCKED,
        UNKNOWN_PRESENT,
        RECONCILIATION_PRESENT,
        MANUAL_REVIEW_PRESENT,
        TERMINAL_FAILURE_PRESENT,
        PARTIALLY_COMPLETED,
        ALL_INSTANCES_EXACTLY_COMPLETED,
        COMPLETED_WITH_MANUAL_DISPOSITION,
        COMPLETION_CONFLICT,
        INVALID_INCOMPLETE_EVIDENCE
    }

    public enum InstanceStatus {
        NOT_STARTED,
        IN_FLIGHT,
        EXACTLY_COMPLETED,
        MANUALLY_DISPOSED,
        UNKNOWN,
        RECONCILING,
        MANUAL_REVIEW_REQUIRED,
        TERMINAL_FAILURE,
        COMPLETION_CONFLICT,
        INVALID_INCOMPLETE_EVIDENCE
    }

    public record InstanceFact(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        InstanceStatus status,
        String evidenceHash
    ) {
        public InstanceFact {
            if (sequenceNo < 1) {
                throw new IllegalArgumentException("sequenceNo must be positive");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            status = Objects.requireNonNull(status, "status must not be null");
            evidenceHash = hash(evidenceHash, "evidenceHash");
        }
    }

    public record PlanSignals(
        boolean canarySelected,
        boolean canaryRunning,
        boolean boundedRunning,
        boolean paused,
        boolean killSwitchBlocked,
        boolean incompleteEvidence,
        String evidenceHash
    ) {
        public PlanSignals {
            evidenceHash = hash(evidenceHash, "evidenceHash");
            if (killSwitchBlocked && !paused) {
                throw new IllegalArgumentException("kill-switch block must pause aggregation");
            }
            if (canaryRunning && !canarySelected) {
                throw new IllegalArgumentException("running canary must be selected");
            }
        }

        public static PlanSignals none() {
            return new PlanSignals(false, false, false, false, false, false, ZERO_HASH);
        }
    }

    public record Summary(
        AggregateStatus status,
        int selectedCount,
        int terminalCount,
        int succeededCount,
        int unresolvedCount,
        List<InstanceFact> canonicalFacts,
        PlanSignals signals
    ) {
        public Summary {
            status = Objects.requireNonNull(status, "status must not be null");
            canonicalFacts = canonicalFacts == null ? List.of() : List.copyOf(canonicalFacts);
            signals = Objects.requireNonNull(signals, "signals must not be null");
            requireCounts(selectedCount, terminalCount, succeededCount, unresolvedCount);
            if (selectedCount != canonicalFacts.size()) {
                throw new IllegalArgumentException("selected count does not match canonical facts");
            }
            if (status != AggregateStatus.INVALID_INCOMPLETE_EVIDENCE) {
                for (int index = 0; index < canonicalFacts.size(); index++) {
                    if (canonicalFacts.get(index).sequenceNo() != index + 1) {
                        throw new IllegalArgumentException(
                            "instance facts are not canonically ordered"
                        );
                    }
                }
            }
            if (status == AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED
                && (selectedCount < 1
                || succeededCount != selectedCount
                || terminalCount != selectedCount
                || unresolvedCount != 0)) {
                throw new IllegalArgumentException(
                    "exact completion requires every selected instance"
                );
            }
            if (status == AggregateStatus.COMPLETED_WITH_MANUAL_DISPOSITION
                && (selectedCount < 1
                || terminalCount != selectedCount
                || unresolvedCount != 0
                || succeededCount == selectedCount)) {
                throw new IllegalArgumentException(
                    "manual completion requires terminal non-exact disposition evidence"
                );
            }
        }
    }

    public record PlanAggregate(
        UUID aggregateId,
        String tenantId,
        UUID planId,
        UUID intentId,
        long aggregateRevision,
        AggregateStatus status,
        int selectedCount,
        int terminalCount,
        int succeededCount,
        int unresolvedCount,
        String inputEvidenceHash,
        String predecessorHash,
        String requestHash,
        String aggregateHash,
        Instant aggregatedAt,
        String requestId,
        String traceId
    ) {
        public PlanAggregate {
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            positive(aggregateRevision, "aggregateRevision");
            status = Objects.requireNonNull(status, "status must not be null");
            requireCounts(selectedCount, terminalCount, succeededCount, unresolvedCount);
            inputEvidenceHash = hash(inputEvidenceHash, "inputEvidenceHash");
            predecessorHash = hash(predecessorHash, "predecessorHash");
            requestHash = hash(requestHash, "requestHash");
            aggregateHash = hash(aggregateHash, "aggregateHash");
            aggregatedAt = Objects.requireNonNull(aggregatedAt, "aggregatedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record PlanAggregateEvent(
        UUID eventId,
        String tenantId,
        UUID aggregateId,
        UUID planId,
        UUID intentId,
        long aggregateRevision,
        AggregateStatus status,
        String predecessorHash,
        String eventHash,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public PlanAggregateEvent {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            positive(aggregateRevision, "aggregateRevision");
            status = Objects.requireNonNull(status, "status must not be null");
            predecessorHash = hash(predecessorHash, "predecessorHash");
            eventHash = hash(eventHash, "eventHash");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    public record PlanCompletion(
        UUID completionId,
        String tenantId,
        UUID planId,
        UUID intentId,
        UUID aggregateId,
        long aggregateRevision,
        AggregateStatus completionStatus,
        int selectedCount,
        int terminalCount,
        int succeededCount,
        String inputEvidenceHash,
        String aggregateHash,
        String completionEvidenceHash,
        Instant completedAt,
        String requestId,
        String traceId
    ) {
        public PlanCompletion {
            completionId = Objects.requireNonNull(completionId, "completionId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            positive(aggregateRevision, "aggregateRevision");
            completionStatus = Objects.requireNonNull(
                completionStatus,
                "completionStatus must not be null"
            );
            if (selectedCount < 1 || terminalCount != selectedCount) {
                throw new IllegalArgumentException(
                    "plan completion requires terminal evidence for every selected instance"
                );
            }
            if (completionStatus == AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED) {
                if (succeededCount != selectedCount) {
                    throw new IllegalArgumentException(
                        "exact plan completion requires every selected instance succeeded"
                    );
                }
            } else if (completionStatus == AggregateStatus.COMPLETED_WITH_MANUAL_DISPOSITION) {
                if (succeededCount < 0 || succeededCount >= selectedCount) {
                    throw new IllegalArgumentException(
                        "manual completion requires at least one non-exact terminal disposition"
                    );
                }
            } else {
                throw new IllegalArgumentException("aggregate status cannot create plan completion");
            }
            inputEvidenceHash = hash(inputEvidenceHash, "inputEvidenceHash");
            aggregateHash = hash(aggregateHash, "aggregateHash");
            completionEvidenceHash = hash(completionEvidenceHash, "completionEvidenceHash");
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
        }
    }

    private static void requireCounts(
        int selectedCount,
        int terminalCount,
        int succeededCount,
        int unresolvedCount
    ) {
        if (selectedCount < 0
            || terminalCount < 0
            || succeededCount < 0
            || unresolvedCount < 0
            || succeededCount > terminalCount
            || terminalCount > selectedCount
            || unresolvedCount != selectedCount - terminalCount) {
            throw new IllegalArgumentException("aggregate counts are inconsistent");
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
            throw new IllegalArgumentException(
                name + " is blank or exceeds maximum length " + maximum
            );
        }
        return normalized;
    }
}
