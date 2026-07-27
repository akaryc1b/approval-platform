package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.StateCounts;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed deterministic D8 classification from the immutable selected-instance sequence. */
public final class ApprovalMigrationPlanAggregationRules {

    private ApprovalMigrationPlanAggregationRules() {
    }

    public static Summary summarize(List<InstanceFact> suppliedFacts, PlanSignals suppliedSignals) {
        Objects.requireNonNull(suppliedFacts, "facts must not be null");
        Objects.requireNonNull(suppliedSignals, "signals must not be null");
        List<InstanceFact> facts = suppliedFacts.stream()
            .map(value -> Objects.requireNonNull(value, "instance fact must not be null"))
            .sorted(Comparator.comparingInt(InstanceFact::sequenceNo))
            .toList();

        boolean invalid = suppliedSignals.incompleteEvidence() || invalidSequence(facts);
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
        for (InstanceFact fact : facts) {
            if (fact.attemptId() != null) {
                provisioned++;
            }
            switch (fact.status()) {
                case UNPROVISIONED, PENDING -> pending++;
                case CLAIMED -> claimed++;
                case ENGINE_REQUESTED -> engineRequested++;
                case VERIFYING -> verifying++;
                case RECONCILING -> reconciling++;
                case UNKNOWN -> unknown++;
                case MANUAL_REVIEW_REQUIRED -> manualReview++;
                case BINDING_CONFLICT -> bindingConflict++;
                case BLOCKED_STALE -> blockedStale++;
                case TERMINAL_FAILURE -> terminalFailed++;
                case EXACTLY_COMPLETED -> exactSuccess++;
                case INVALID_INCOMPLETE_EVIDENCE -> {
                    pending++;
                    invalid = true;
                }
            }
        }
        int unresolved = facts.size() - exactSuccess - terminalFailed;
        StateCounts counts = new StateCounts(
            facts.size(),
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
        PlanSignals signals = effectiveSignals(
            suppliedSignals,
            invalid,
            unknown,
            reconciling,
            manualReview,
            bindingConflict,
            blockedStale,
            terminalFailed
        );
        AggregateStatus status = status(counts, signals, invalid);
        TerminalOutcome outcome = outcome(status, counts);
        return new Summary(status, outcome, counts, facts, signals);
    }

    private static AggregateStatus status(
        StateCounts counts,
        PlanSignals signals,
        boolean invalid
    ) {
        if (invalid
            || signals.canaryStatus() == CanaryStatus.INVALID
            || signals.orchestrationStatus() == OrchestrationStatus.INVALID
            || signals.pauseReason() == PauseReason.INCOMPLETE_EVIDENCE) {
            return AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE;
        }
        if (counts.unresolvedCount() == 0
            && counts.exactSuccessCount() == counts.selectedCount()
            && counts.terminalFailedCount() == 0
            && !signals.paused()) {
            return AggregateStatus.COMPLETED_SUCCEEDED;
        }
        if (counts.unresolvedCount() == 0
            && counts.terminalFailedCount() > 0
            && counts.exactSuccessCount() + counts.terminalFailedCount()
                == counts.selectedCount()) {
            return AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE;
        }
        if (signals.paused()) {
            return AggregateStatus.PAUSED;
        }
        if (counts.exactSuccessCount() > 0) {
            return AggregateStatus.PARTIALLY_COMPLETED;
        }
        if (counts.terminalFailedCount() > 0) {
            return AggregateStatus.TERMINAL_FAILURE_PRESENT;
        }
        if (signals.orchestrationStatus() == OrchestrationStatus.BOUNDED_IN_PROGRESS
            || counts.claimedCount() > 0
            || counts.engineRequestedCount() > 0
            || counts.verifyingCount() > 0) {
            return AggregateStatus.BOUNDED_EXECUTION_IN_PROGRESS;
        }
        if (signals.canaryStatus() == CanaryStatus.IN_PROGRESS
            || signals.orchestrationStatus() == OrchestrationStatus.CANARY_IN_PROGRESS) {
            return AggregateStatus.CANARY_IN_PROGRESS;
        }
        if (signals.canaryStatus() == CanaryStatus.PENDING) {
            return AggregateStatus.CANARY_PENDING;
        }
        if (counts.provisionedAttemptCount() == 0
            && counts.pendingCount() == counts.selectedCount()) {
            return AggregateStatus.NOT_STARTED;
        }
        return AggregateStatus.UNRESOLVED;
    }

    private static TerminalOutcome outcome(AggregateStatus status, StateCounts counts) {
        return switch (status) {
            case COMPLETED_SUCCEEDED -> TerminalOutcome.SUCCEEDED;
            case COMPLETED_WITH_TERMINAL_FAILURE ->
                TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE;
            case INVALID_OR_INCOMPLETE_EVIDENCE -> TerminalOutcome.INVALID_EVIDENCE;
            default -> counts.unresolvedCount() > 0 || counts.terminalFailedCount() > 0
                ? TerminalOutcome.UNRESOLVED
                : TerminalOutcome.NONE;
        };
    }

    private static PlanSignals effectiveSignals(
        PlanSignals supplied,
        boolean invalid,
        int unknown,
        int reconciling,
        int manualReview,
        int bindingConflict,
        int blockedStale,
        int terminalFailed
    ) {
        PauseReason reason = supplied.pauseReason();
        if (invalid) {
            reason = PauseReason.INCOMPLETE_EVIDENCE;
        } else if (reason == PauseReason.NONE && unknown > 0) {
            reason = PauseReason.UNKNOWN;
        } else if (reason == PauseReason.NONE && reconciling > 0) {
            reason = PauseReason.RECONCILIATION;
        } else if (reason == PauseReason.NONE && manualReview > 0) {
            reason = PauseReason.MANUAL_REVIEW;
        } else if (reason == PauseReason.NONE && bindingConflict > 0) {
            reason = PauseReason.BINDING_CONFLICT;
        } else if (reason == PauseReason.NONE && blockedStale > 0) {
            reason = PauseReason.STALE_AUTHORITY;
        } else if (reason == PauseReason.NONE && terminalFailed > 0) {
            reason = PauseReason.TERMINAL_FAILURE;
        }
        OrchestrationStatus orchestration = supplied.orchestrationStatus();
        CanaryStatus canary = supplied.canaryStatus();
        if (reason != PauseReason.NONE) {
            orchestration = OrchestrationStatus.PAUSED;
            if (canary == CanaryStatus.IN_PROGRESS || canary == CanaryStatus.PENDING) {
                canary = CanaryStatus.PAUSED;
            }
        }
        return new PlanSignals(
            canary,
            orchestration,
            reason,
            supplied.killSwitchObserved(),
            invalid || supplied.incompleteEvidence(),
            supplied.evidenceHash()
        );
    }

    private static boolean invalidSequence(List<InstanceFact> facts) {
        Set<UUID> instances = new HashSet<>();
        Set<UUID> attempts = new HashSet<>();
        for (int index = 0; index < facts.size(); index++) {
            InstanceFact fact = facts.get(index);
            if (fact.sequenceNo() != index + 1 || !instances.add(fact.approvalInstanceId())) {
                return true;
            }
            if (fact.attemptId() != null && !attempts.add(fact.attemptId())) {
                return true;
            }
        }
        return false;
    }
}
