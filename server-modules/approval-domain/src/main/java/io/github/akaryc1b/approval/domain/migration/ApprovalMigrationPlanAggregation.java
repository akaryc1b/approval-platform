package io.github.akaryc1b.approval.domain.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministic M5-D8 plan-level classification and immutable aggregate evidence. */
public final class ApprovalMigrationPlanAggregation {

    public static final String CONTRACT_VERSION = "M5-D8-PLAN-AGGREGATE-V1";
    public static final String ZERO_HASH = "0".repeat(64);

    private ApprovalMigrationPlanAggregation() {
    }

    public enum AggregateState {
        NOT_STARTED,
        CANARY_PENDING,
        CANARY_IN_PROGRESS,
        BOUNDED_EXECUTION_IN_PROGRESS,
        PAUSED_KILL_SWITCH,
        PAUSED_UNKNOWN,
        PAUSED_RECONCILIATION,
        PAUSED_MANUAL_REVIEW,
        PAUSED_BINDING_CONFLICT,
        PAUSED_STALE_AUTHORITY,
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
        INCOMPLETE_EVIDENCE
    }

    public enum InstanceState {
        UNPROVISIONED,
        PENDING,
        CLAIMED,
        ENGINE_REQUESTED,
        VERIFYING,
        RECONCILING,
        UNKNOWN,
        MANUAL_REVIEW,
        BINDING_CONFLICT,
        BLOCKED_STALE,
        TERMINAL_FAILED,
        EXACT_SUCCESS,
        INVALID
    }

    /** One canonical selected-instance classification derived only from immutable server evidence. */
    public record InstanceEvidence(
        int sequence,
        UUID approvalInstanceId,
        String selectedInstanceEvidenceHash,
        UUID attemptId,
        InstanceState state,
        String evidenceHash
    ) {
        public InstanceEvidence {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            selectedInstanceEvidenceHash = requireHash(
                selectedInstanceEvidenceHash,
                "selectedInstanceEvidenceHash"
            );
            state = Objects.requireNonNull(state, "state must not be null");
            evidenceHash = requireHash(evidenceHash, "evidenceHash");
            if (state == InstanceState.UNPROVISIONED && attemptId != null) {
                throw new IllegalArgumentException("unprovisioned selected instance cannot carry an attempt");
            }
            if (state != InstanceState.UNPROVISIONED && attemptId == null) {
                throw new IllegalArgumentException("classified attempt state requires attemptId");
            }
        }
    }

    /** Closed plan-level facts obtained from one exact consumed plan and its immutable lineage. */
    public record AggregateInput(
        UUID planId,
        UUID intentId,
        String planHash,
        int selectedCount,
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        List<InstanceEvidence> instances
    ) {
        public AggregateInput {
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            planHash = requireHash(planHash, "planHash");
            if (selectedCount < 1 || selectedCount > 5_000) {
                throw new IllegalArgumentException("selectedCount must be between 1 and 5000");
            }
            canaryStatus = Objects.requireNonNull(canaryStatus, "canaryStatus must not be null");
            orchestrationStatus = Objects.requireNonNull(
                orchestrationStatus,
                "orchestrationStatus must not be null"
            );
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            instances = instances == null ? List.of() : List.copyOf(instances);
            if (instances.size() != selectedCount) {
                throw new IllegalArgumentException("selected sequence and selectedCount differ");
            }
        }
    }

    /** Immutable aggregate revision. createdAt and auditReference are intentionally outside business hashes. */
    public record PlanAggregate(
        UUID aggregateId,
        UUID planId,
        UUID intentId,
        String planHash,
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
        int unresolvedCount,
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        boolean paused,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        long aggregateRevision,
        String predecessorAggregateHash,
        String inputEvidenceSetHash,
        String aggregateHash,
        String completionEvidenceHash,
        AggregateState aggregateState,
        TerminalOutcome terminalOutcome,
        Instant createdAt,
        String auditReference
    ) {
        public PlanAggregate {
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            planHash = requireHash(planHash, "planHash");
            requireCount(selectedCount, "selectedCount");
            requireCount(provisionedAttemptCount, "provisionedAttemptCount");
            requireCount(pendingCount, "pendingCount");
            requireCount(claimedCount, "claimedCount");
            requireCount(engineRequestedCount, "engineRequestedCount");
            requireCount(verifyingCount, "verifyingCount");
            requireCount(reconcilingCount, "reconcilingCount");
            requireCount(unknownCount, "unknownCount");
            requireCount(manualReviewCount, "manualReviewCount");
            requireCount(bindingConflictCount, "bindingConflictCount");
            requireCount(blockedStaleCount, "blockedStaleCount");
            requireCount(terminalFailedCount, "terminalFailedCount");
            requireCount(exactSuccessCount, "exactSuccessCount");
            requireCount(unresolvedCount, "unresolvedCount");
            canaryStatus = Objects.requireNonNull(canaryStatus, "canaryStatus must not be null");
            orchestrationStatus = Objects.requireNonNull(
                orchestrationStatus,
                "orchestrationStatus must not be null"
            );
            pauseReason = Objects.requireNonNull(pauseReason, "pauseReason must not be null");
            if (paused != (pauseReason != PauseReason.NONE)) {
                throw new IllegalArgumentException("paused indicator must match pauseReason");
            }
            if (aggregateRevision < 1) {
                throw new IllegalArgumentException("aggregateRevision must be positive");
            }
            predecessorAggregateHash = requireHash(
                predecessorAggregateHash,
                "predecessorAggregateHash"
            );
            inputEvidenceSetHash = requireHash(inputEvidenceSetHash, "inputEvidenceSetHash");
            aggregateHash = requireHash(aggregateHash, "aggregateHash");
            completionEvidenceHash = optionalHash(
                completionEvidenceHash,
                "completionEvidenceHash"
            );
            aggregateState = Objects.requireNonNull(aggregateState, "aggregateState must not be null");
            terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome must not be null");
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            auditReference = requireText(auditReference, "auditReference", 256);
            validateTerminalEvidence(
                aggregateState,
                terminalOutcome,
                completionEvidenceHash,
                unresolvedCount,
                terminalFailedCount
            );
        }
    }

    public static PlanAggregate aggregate(
        UUID aggregateId,
        AggregateInput input,
        long revision,
        String predecessorHash,
        Instant createdAt,
        String auditReference
    ) {
        Objects.requireNonNull(input, "input must not be null");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        String predecessor = requireHash(predecessorHash, "predecessorHash");
        Classification classification = classify(input);
        String inputHash = inputEvidenceSetHash(input);
        String aggregateHash = hashValues(
            CONTRACT_VERSION,
            input.planId(),
            input.intentId(),
            input.planHash(),
            input.selectedCount(),
            classification.provisioned(),
            classification.pending(),
            classification.claimed(),
            classification.engineRequested(),
            classification.verifying(),
            classification.reconciling(),
            classification.unknown(),
            classification.manualReview(),
            classification.bindingConflict(),
            classification.blockedStale(),
            classification.terminalFailed(),
            classification.exactSuccess(),
            classification.unresolved(),
            input.canaryStatus(),
            input.orchestrationStatus(),
            input.pauseReason(),
            input.killSwitchObserved(),
            revision,
            predecessor,
            inputHash,
            classification.state(),
            classification.outcome()
        );
        String completionHash = terminal(classification.state())
            ? hashValues(
                "M5-D8-PLAN-COMPLETION-V1",
                input.planId(),
                input.intentId(),
                revision,
                aggregateHash,
                classification.state(),
                classification.outcome(),
                classification.exactSuccess(),
                classification.terminalFailed(),
                classification.unresolved()
            )
            : null;
        return new PlanAggregate(
            aggregateId,
            input.planId(),
            input.intentId(),
            input.planHash(),
            input.selectedCount(),
            classification.provisioned(),
            classification.pending(),
            classification.claimed(),
            classification.engineRequested(),
            classification.verifying(),
            classification.reconciling(),
            classification.unknown(),
            classification.manualReview(),
            classification.bindingConflict(),
            classification.blockedStale(),
            classification.terminalFailed(),
            classification.exactSuccess(),
            classification.unresolved(),
            input.canaryStatus(),
            input.orchestrationStatus(),
            input.pauseReason() != PauseReason.NONE,
            input.pauseReason(),
            input.killSwitchObserved(),
            revision,
            predecessor,
            inputHash,
            aggregateHash,
            completionHash,
            classification.state(),
            classification.outcome(),
            createdAt,
            auditReference
        );
    }

    public static String inputEvidenceSetHash(AggregateInput input) {
        Objects.requireNonNull(input, "input must not be null");
        validateSequence(input.instances(), input.selectedCount());
        List<Object> values = new ArrayList<>(8 + input.instances().size() * 6);
        values.add("M5-D8-SELECTED-EVIDENCE-SET-V1");
        values.add(input.planId());
        values.add(input.intentId());
        values.add(input.planHash());
        values.add(input.selectedCount());
        values.add(input.canaryStatus());
        values.add(input.orchestrationStatus());
        values.add(input.pauseReason());
        values.add(input.killSwitchObserved());
        for (InstanceEvidence instance : input.instances()) {
            values.add(instance.sequence());
            values.add(instance.approvalInstanceId());
            values.add(instance.selectedInstanceEvidenceHash());
            values.add(instance.attemptId());
            values.add(instance.state());
            values.add(instance.evidenceHash());
        }
        return hashValues(values.toArray());
    }

    private static Classification classify(AggregateInput input) {
        boolean sequenceValid = validateSequence(input.instances(), input.selectedCount());
        int provisioned = 0;
        int pending = 0;
        int claimed = 0;
        int engineRequested = 0;
        int verifying = 0;
        int reconciling = 0;
        int unknown = 0;
        int manualReview = 0;
        int bindingConflict = 0;
        int blockedStale = 0;
        int terminalFailed = 0;
        int exactSuccess = 0;
        int invalid = 0;
        for (InstanceEvidence instance : input.instances()) {
            if (instance.attemptId() != null) {
                provisioned++;
            }
            switch (instance.state()) {
                case UNPROVISIONED -> pending++;
                case PENDING -> pending++;
                case CLAIMED -> claimed++;
                case ENGINE_REQUESTED -> engineRequested++;
                case VERIFYING -> verifying++;
                case RECONCILING -> reconciling++;
                case UNKNOWN -> unknown++;
                case MANUAL_REVIEW -> manualReview++;
                case BINDING_CONFLICT -> bindingConflict++;
                case BLOCKED_STALE -> blockedStale++;
                case TERMINAL_FAILED -> terminalFailed++;
                case EXACT_SUCCESS -> exactSuccess++;
                case INVALID -> invalid++;
            }
        }
        int unresolved = input.selectedCount() - exactSuccess - terminalFailed;
        boolean invalidEvidence = !sequenceValid
            || invalid > 0
            || unresolved < 0
            || input.canaryStatus() == CanaryStatus.INVALID
            || input.orchestrationStatus() == OrchestrationStatus.INVALID
            || input.pauseReason() == PauseReason.INCOMPLETE_EVIDENCE;
        AggregateState state = state(
            input,
            invalidEvidence,
            provisioned,
            pending,
            claimed,
            engineRequested,
            verifying,
            reconciling,
            unknown,
            manualReview,
            bindingConflict,
            blockedStale,
            terminalFailed,
            exactSuccess,
            unresolved
        );
        TerminalOutcome outcome = switch (state) {
            case COMPLETED_SUCCEEDED -> TerminalOutcome.SUCCEEDED;
            case COMPLETED_WITH_TERMINAL_FAILURE ->
                TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE;
            case INVALID_OR_INCOMPLETE_EVIDENCE -> TerminalOutcome.INVALID_EVIDENCE;
            default -> unresolved > 0 || terminalFailed > 0
                ? TerminalOutcome.UNRESOLVED
                : TerminalOutcome.NONE;
        };
        return new Classification(
            provisioned,
            pending,
            claimed,
            engineRequested,
            verifying,
            reconciling,
            unknown,
            manualReview,
            bindingConflict,
            blockedStale,
            terminalFailed,
            exactSuccess,
            unresolved,
            state,
            outcome
        );
    }

    private static AggregateState state(
        AggregateInput input,
        boolean invalid,
        int provisioned,
        int pending,
        int claimed,
        int engineRequested,
        int verifying,
        int reconciling,
        int unknown,
        int manualReview,
        int bindingConflict,
        int blockedStale,
        int terminalFailed,
        int exactSuccess,
        int unresolved
    ) {
        if (invalid) {
            return AggregateState.INVALID_OR_INCOMPLETE_EVIDENCE;
        }
        if (input.pauseReason() != PauseReason.NONE) {
            return switch (input.pauseReason()) {
                case KILL_SWITCH -> AggregateState.PAUSED_KILL_SWITCH;
                case UNKNOWN -> AggregateState.PAUSED_UNKNOWN;
                case RECONCILIATION -> AggregateState.PAUSED_RECONCILIATION;
                case MANUAL_REVIEW -> AggregateState.PAUSED_MANUAL_REVIEW;
                case BINDING_CONFLICT -> AggregateState.PAUSED_BINDING_CONFLICT;
                case STALE_AUTHORITY -> AggregateState.PAUSED_STALE_AUTHORITY;
                case INCOMPLETE_EVIDENCE -> AggregateState.INVALID_OR_INCOMPLETE_EVIDENCE;
                case NONE -> throw new IllegalStateException("unreachable pause reason");
            };
        }
        if (unresolved == 0 && terminalFailed == 0 && exactSuccess == input.selectedCount()) {
            return AggregateState.COMPLETED_SUCCEEDED;
        }
        if (unresolved == 0 && terminalFailed > 0
            && exactSuccess + terminalFailed == input.selectedCount()) {
            return AggregateState.COMPLETED_WITH_TERMINAL_FAILURE;
        }
        if (exactSuccess > 0) {
            return AggregateState.PARTIALLY_COMPLETED;
        }
        if (terminalFailed > 0) {
            return AggregateState.TERMINAL_FAILURE_PRESENT;
        }
        if (unknown > 0) {
            return AggregateState.PAUSED_UNKNOWN;
        }
        if (reconciling > 0) {
            return AggregateState.PAUSED_RECONCILIATION;
        }
        if (manualReview > 0) {
            return AggregateState.PAUSED_MANUAL_REVIEW;
        }
        if (bindingConflict > 0) {
            return AggregateState.PAUSED_BINDING_CONFLICT;
        }
        if (blockedStale > 0) {
            return AggregateState.PAUSED_STALE_AUTHORITY;
        }
        if (input.orchestrationStatus() == OrchestrationStatus.BOUNDED_IN_PROGRESS
            || claimed > 0 || engineRequested > 0 || verifying > 0) {
            return AggregateState.BOUNDED_EXECUTION_IN_PROGRESS;
        }
        if (input.canaryStatus() == CanaryStatus.IN_PROGRESS
            || input.orchestrationStatus() == OrchestrationStatus.CANARY_IN_PROGRESS) {
            return AggregateState.CANARY_IN_PROGRESS;
        }
        if (input.canaryStatus() == CanaryStatus.PENDING) {
            return AggregateState.CANARY_PENDING;
        }
        if (provisioned == 0 && pending == input.selectedCount()) {
            return AggregateState.NOT_STARTED;
        }
        return AggregateState.UNRESOLVED;
    }

    private static boolean validateSequence(List<InstanceEvidence> instances, int selectedCount) {
        if (instances.size() != selectedCount) {
            return false;
        }
        Set<UUID> selected = new HashSet<>();
        Set<UUID> attempts = new HashSet<>();
        for (int index = 0; index < instances.size(); index++) {
            InstanceEvidence instance = instances.get(index);
            if (instance.sequence() != index + 1 || !selected.add(instance.approvalInstanceId())) {
                return false;
            }
            if (instance.attemptId() != null && !attempts.add(instance.attemptId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean terminal(AggregateState state) {
        return state == AggregateState.COMPLETED_SUCCEEDED
            || state == AggregateState.COMPLETED_WITH_TERMINAL_FAILURE;
    }

    private static void validateTerminalEvidence(
        AggregateState state,
        TerminalOutcome outcome,
        String completionHash,
        int unresolved,
        int terminalFailed
    ) {
        if (terminal(state)) {
            if (completionHash == null || unresolved != 0) {
                throw new IllegalArgumentException("terminal aggregate requires complete evidence");
            }
            if (state == AggregateState.COMPLETED_SUCCEEDED
                && (outcome != TerminalOutcome.SUCCEEDED || terminalFailed != 0)) {
                throw new IllegalArgumentException("successful aggregate contains failure evidence");
            }
            if (state == AggregateState.COMPLETED_WITH_TERMINAL_FAILURE
                && (outcome != TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE
                    || terminalFailed < 1)) {
                throw new IllegalArgumentException("terminal-failure aggregate is inconsistent");
            }
        } else if (completionHash != null) {
            throw new IllegalArgumentException("non-terminal aggregate cannot carry completion evidence");
        }
    }

    private static void requireCount(int value, String name) {
        if (value < 0 || value > 5_000) {
            throw new IllegalArgumentException(name + " must be between 0 and 5000");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String optionalHash(String value, String name) {
        return value == null ? null : requireHash(value, name);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum " + maximum);
        }
        return normalized;
    }

    private static String hashValues(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                if (value == null) {
                    digest.update("-1:".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(bytes);
                    digest.update((byte) '|');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Classification(
        int provisioned,
        int pending,
        int claimed,
        int engineRequested,
        int verifying,
        int reconciling,
        int unknown,
        int manualReview,
        int bindingConflict,
        int blockedStale,
        int terminalFailed,
        int exactSuccess,
        int unresolved,
        AggregateState state,
        TerminalOutcome outcome
    ) {
    }
}
