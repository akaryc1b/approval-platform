package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, bounded M5-D8 plan aggregate, event and completion evidence. */
public final class ApprovalMigrationPlanAggregationEvidence {

    public static final String ZERO_HASH = "0".repeat(64);
    public static final int MAX_SELECTED_COUNT = 5_000;

    private ApprovalMigrationPlanAggregationEvidence() {
    }

    public enum AggregateStatus {
        NOT_STARTED,
        CANARY_PENDING,
        CANARY_IN_PROGRESS,
        BOUNDED_EXECUTION_IN_PROGRESS,
        PAUSED,
        UNRESOLVED,
        TERMINAL_FAILURE_PRESENT,
        PARTIALLY_COMPLETED,
        COMPLETED_SUCCEEDED,
        COMPLETED_WITH_TERMINAL_FAILURE,
        INVALID_OR_INCOMPLETE_EVIDENCE
    }

    public enum TerminalOutcome {
        NONE,
        SUCCEEDED,
        COMPLETED_WITH_TERMINAL_FAILURE,
        UNRESOLVED,
        INVALID_EVIDENCE
    }

    public enum CanaryStatus {
        NOT_SELECTED,
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        PAUSED,
        INVALID
    }

    public enum OrchestrationStatus {
        NOT_STARTED,
        CANARY_IN_PROGRESS,
        BOUNDED_IN_PROGRESS,
        PAUSED,
        COMPLETED,
        INVALID
    }

    public enum PauseReason {
        NONE,
        KILL_SWITCH,
        UNKNOWN,
        RECONCILIATION,
        MANUAL_REVIEW,
        BINDING_CONFLICT,
        STALE_AUTHORITY,
        TERMINAL_FAILURE,
        CANARY_IN_FLIGHT,
        EMPTY_BATCH,
        INCOMPLETE_EVIDENCE
    }

    public enum InstanceStatus {
        UNPROVISIONED,
        PENDING,
        CLAIMED,
        ENGINE_REQUESTED,
        VERIFYING,
        RECONCILING,
        UNKNOWN,
        MANUAL_REVIEW_REQUIRED,
        BINDING_CONFLICT,
        BLOCKED_STALE,
        TERMINAL_FAILURE,
        EXACTLY_COMPLETED,
        INVALID_INCOMPLETE_EVIDENCE
    }

    public record InstanceFact(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        UUID attemptId,
        InstanceStatus status,
        String selectedInstanceEvidenceHash,
        String evidenceHash
    ) {
        public InstanceFact {
            if (sequenceNo < 1 || sequenceNo > MAX_SELECTED_COUNT) {
                throw new IllegalArgumentException("sequenceNo is outside the bounded range");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            status = Objects.requireNonNull(status, "status must not be null");
            selectedInstanceEvidenceHash = hash(
                selectedInstanceEvidenceHash,
                "selectedInstanceEvidenceHash"
            );
            evidenceHash = hash(evidenceHash, "evidenceHash");
            if (status == InstanceStatus.UNPROVISIONED && attemptId != null) {
                throw new IllegalArgumentException(
                    "unprovisioned selected instance cannot carry an attempt"
                );
            }
            if (status != InstanceStatus.UNPROVISIONED && attemptId == null) {
                throw new IllegalArgumentException(
                    "classified attempt evidence requires attemptId"
                );
            }
        }
    }

    public record PlanSignals(
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        boolean incompleteEvidence,
        String evidenceHash
    ) {
        public PlanSignals {
            canaryStatus = Objects.requireNonNull(canaryStatus, "canaryStatus must not be null");
            orchestrationStatus = Objects.requireNonNull(
                orchestrationStatus,
                "orchestrationStatus must not be null"
            );
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            evidenceHash = hash(evidenceHash, "evidenceHash");
            if (incompleteEvidence && pauseReason == PauseReason.NONE) {
                pauseReason = PauseReason.INCOMPLETE_EVIDENCE;
            }
            if (pauseReason == PauseReason.KILL_SWITCH && !killSwitchObserved) {
                throw new IllegalArgumentException(
                    "kill-switch pause requires an immutable observation"
                );
            }
        }

        public boolean paused() {
            return pauseReason != PauseReason.NONE;
        }

        public static PlanSignals none() {
            return new PlanSignals(
                CanaryStatus.NOT_SELECTED,
                OrchestrationStatus.NOT_STARTED,
                PauseReason.NONE,
                false,
                false,
                ZERO_HASH
            );
        }
    }

    public record StateCounts(
        int selectedCount,
        int provisionedAttemptCount,
        int pendingCount,
        int claimedCount,
        int engineRequestedCount,
        int verifyingCount,
        int reconcilingCount,
        int unknownCount,
        int manualReviewCount,
        int bindingConflictCount,
        int blockedStaleCount,
        int terminalFailedCount,
        int exactSuccessCount,
        int unresolvedCount
    ) {
        public StateCounts {
            int[] values = {
                selectedCount,
                provisionedAttemptCount,
                pendingCount,
                claimedCount,
                engineRequestedCount,
                verifyingCount,
                reconcilingCount,
                unknownCount,
                manualReviewCount,
                bindingConflictCount,
                blockedStaleCount,
                terminalFailedCount,
                exactSuccessCount,
                unresolvedCount
            };
            for (int value : values) {
                if (value < 0 || value > MAX_SELECTED_COUNT) {
                    throw new IllegalArgumentException(
                        "aggregate count is outside the bounded range"
                    );
                }
            }
            int classified = pendingCount + claimedCount + engineRequestedCount
                + verifyingCount + reconcilingCount + unknownCount + manualReviewCount
                + bindingConflictCount + blockedStaleCount + terminalFailedCount
                + exactSuccessCount;
            if (classified != selectedCount) {
                throw new IllegalArgumentException(
                    "every selected instance must have exactly one closed classification"
                );
            }
            if (provisionedAttemptCount > selectedCount
                || unresolvedCount != selectedCount - exactSuccessCount - terminalFailedCount) {
                throw new IllegalArgumentException("aggregate counts are inconsistent");
            }
        }
    }

    public record Summary(
        AggregateStatus status,
        TerminalOutcome terminalOutcome,
        StateCounts counts,
        List<InstanceFact> canonicalFacts,
        PlanSignals signals
    ) {
        public Summary {
            status = Objects.requireNonNull(status, "status must not be null");
            terminalOutcome = Objects.requireNonNull(
                terminalOutcome,
                "terminalOutcome must not be null"
            );
            counts = Objects.requireNonNull(counts, "counts must not be null");
            canonicalFacts = canonicalFacts == null ? List.of() : List.copyOf(canonicalFacts);
            signals = Objects.requireNonNull(signals, "signals must not be null");
            if (counts.selectedCount() != canonicalFacts.size()) {
                throw new IllegalArgumentException(
                    "selected count does not match canonical facts"
                );
            }
            Set<UUID> instances = new HashSet<>();
            Set<UUID> attempts = new HashSet<>();
            for (int index = 0; index < canonicalFacts.size(); index++) {
                InstanceFact fact = canonicalFacts.get(index);
                if (fact.sequenceNo() != index + 1
                    || !instances.add(fact.approvalInstanceId())
                    || (fact.attemptId() != null && !attempts.add(fact.attemptId()))) {
                    if (status != AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE) {
                        throw new IllegalArgumentException(
                            "normal summary requires a unique continuous selected sequence"
                        );
                    }
                    break;
                }
            }
            validateTerminal(status, terminalOutcome, counts);
        }
    }

    public record PlanAggregate(
        UUID aggregateId,
        String tenantId,
        String operatorId,
        UUID planId,
        UUID intentId,
        String planHash,
        long aggregateRevision,
        AggregateStatus status,
        TerminalOutcome terminalOutcome,
        StateCounts counts,
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        boolean paused,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        String inputEvidenceHash,
        String predecessorHash,
        String idempotencyKey,
        String requestHash,
        String aggregateHash,
        Instant aggregatedAt,
        String reason,
        String requestId,
        String traceId,
        String auditReference
    ) {
        public PlanAggregate {
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            operatorId = text(operatorId, "operatorId", 256);
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            planHash = hash(planHash, "planHash");
            positive(aggregateRevision, "aggregateRevision");
            status = Objects.requireNonNull(status, "status must not be null");
            terminalOutcome = Objects.requireNonNull(
                terminalOutcome,
                "terminalOutcome must not be null"
            );
            counts = Objects.requireNonNull(counts, "counts must not be null");
            canaryStatus = Objects.requireNonNull(canaryStatus, "canaryStatus must not be null");
            orchestrationStatus = Objects.requireNonNull(
                orchestrationStatus,
                "orchestrationStatus must not be null"
            );
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            if (paused != (pauseReason != PauseReason.NONE)) {
                throw new IllegalArgumentException("paused indicator must match pauseReason");
            }
            inputEvidenceHash = hash(inputEvidenceHash, "inputEvidenceHash");
            predecessorHash = hash(predecessorHash, "predecessorHash");
            idempotencyKey = text(idempotencyKey, "idempotencyKey", 200);
            requestHash = hash(requestHash, "requestHash");
            aggregateHash = hash(aggregateHash, "aggregateHash");
            aggregatedAt = Objects.requireNonNull(aggregatedAt, "aggregatedAt must not be null");
            reason = text(reason, "reason", 1000);
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
            auditReference = text(auditReference, "auditReference", 256);
            validateTerminal(status, terminalOutcome, counts);
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
        TerminalOutcome terminalOutcome,
        PauseReason pauseReason,
        String predecessorHash,
        String aggregateHash,
        String eventHash,
        Instant happenedAt,
        String requestId,
        String traceId,
        String auditReference
    ) {
        public PlanAggregateEvent {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            tenantId = text(tenantId, "tenantId", 128);
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            positive(aggregateRevision, "aggregateRevision");
            status = Objects.requireNonNull(status, "status must not be null");
            terminalOutcome = Objects.requireNonNull(
                terminalOutcome,
                "terminalOutcome must not be null"
            );
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            predecessorHash = hash(predecessorHash, "predecessorHash");
            aggregateHash = hash(aggregateHash, "aggregateHash");
            eventHash = hash(eventHash, "eventHash");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
            auditReference = text(auditReference, "auditReference", 256);
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
        TerminalOutcome terminalOutcome,
        StateCounts counts,
        String inputEvidenceHash,
        String aggregateHash,
        String completionEvidenceHash,
        Instant completedAt,
        String requestId,
        String traceId,
        String auditReference
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
            terminalOutcome = Objects.requireNonNull(
                terminalOutcome,
                "terminalOutcome must not be null"
            );
            counts = Objects.requireNonNull(counts, "counts must not be null");
            validateTerminal(completionStatus, terminalOutcome, counts);
            if (completionStatus != AggregateStatus.COMPLETED_SUCCEEDED
                && completionStatus != AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE) {
                throw new IllegalArgumentException(
                    "non-terminal aggregate cannot create completion evidence"
                );
            }
            inputEvidenceHash = hash(inputEvidenceHash, "inputEvidenceHash");
            aggregateHash = hash(aggregateHash, "aggregateHash");
            completionEvidenceHash = hash(
                completionEvidenceHash,
                "completionEvidenceHash"
            );
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            requestId = text(requestId, "requestId", 256);
            traceId = optional(traceId, "traceId", 256);
            auditReference = text(auditReference, "auditReference", 256);
        }
    }

    private static void validateTerminal(
        AggregateStatus status,
        TerminalOutcome outcome,
        StateCounts counts
    ) {
        if (status == AggregateStatus.COMPLETED_SUCCEEDED) {
            if (outcome != TerminalOutcome.SUCCEEDED
                || counts.selectedCount() < 1
                || counts.exactSuccessCount() != counts.selectedCount()
                || counts.terminalFailedCount() != 0
                || counts.unresolvedCount() != 0) {
                throw new IllegalArgumentException(
                    "successful completion requires exact completion for every selected instance"
                );
            }
        } else if (status == AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE) {
            if (outcome != TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE
                || counts.selectedCount() < 1
                || counts.terminalFailedCount() < 1
                || counts.exactSuccessCount() + counts.terminalFailedCount()
                    != counts.selectedCount()
                || counts.unresolvedCount() != 0) {
                throw new IllegalArgumentException(
                    "terminal-failure completion requires terminal evidence for every instance"
                );
            }
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
