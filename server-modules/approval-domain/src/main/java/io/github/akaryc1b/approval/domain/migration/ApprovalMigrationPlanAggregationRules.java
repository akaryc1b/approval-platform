package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Closed deterministic D8 status and count derivation from immutable per-instance facts. */
public final class ApprovalMigrationPlanAggregationRules {

    private ApprovalMigrationPlanAggregationRules() {
    }

    public static Summary summarize(List<InstanceFact> suppliedFacts, PlanSignals signals) {
        Objects.requireNonNull(suppliedFacts, "facts must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
        List<InstanceFact> facts = suppliedFacts.stream()
            .map(value -> Objects.requireNonNull(value, "instance fact must not be null"))
            .sorted(Comparator.comparingInt(InstanceFact::sequenceNo))
            .toList();
        for (int index = 0; index < facts.size(); index++) {
            if (facts.get(index).sequenceNo() != index + 1) {
                return summary(AggregateStatus.INVALID_INCOMPLETE_EVIDENCE, facts, signals);
            }
        }
        return summary(status(facts, signals), facts, signals);
    }

    private static AggregateStatus status(List<InstanceFact> facts, PlanSignals signals) {
        if (signals.incompleteEvidence()
            || facts.stream().anyMatch(fact -> fact.status()
            == InstanceStatus.INVALID_INCOMPLETE_EVIDENCE)) {
            return AggregateStatus.INVALID_INCOMPLETE_EVIDENCE;
        }
        if (facts.stream().anyMatch(fact -> fact.status() == InstanceStatus.COMPLETION_CONFLICT)) {
            return AggregateStatus.COMPLETION_CONFLICT;
        }
        if (facts.stream().anyMatch(fact -> fact.status() == InstanceStatus.UNKNOWN)) {
            return AggregateStatus.UNKNOWN_PRESENT;
        }
        if (facts.stream().anyMatch(fact -> fact.status()
            == InstanceStatus.MANUAL_REVIEW_REQUIRED)) {
            return AggregateStatus.MANUAL_REVIEW_PRESENT;
        }
        if (facts.stream().anyMatch(fact -> fact.status() == InstanceStatus.RECONCILING)) {
            return AggregateStatus.RECONCILIATION_PRESENT;
        }
        if (facts.stream().anyMatch(fact -> fact.status() == InstanceStatus.TERMINAL_FAILURE)) {
            return AggregateStatus.TERMINAL_FAILURE_PRESENT;
        }
        if (signals.killSwitchBlocked()) {
            return AggregateStatus.KILL_SWITCH_BLOCKED;
        }
        if (signals.paused()) {
            return AggregateStatus.PAUSED;
        }
        if (!facts.isEmpty()
            && facts.stream().allMatch(fact -> fact.status() == InstanceStatus.EXACTLY_COMPLETED)) {
            return AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED;
        }
        boolean allTerminal = !facts.isEmpty() && facts.stream().allMatch(
            fact -> fact.status() == InstanceStatus.EXACTLY_COMPLETED
                || fact.status() == InstanceStatus.MANUALLY_DISPOSED
        );
        boolean manual = facts.stream().anyMatch(
            fact -> fact.status() == InstanceStatus.MANUALLY_DISPOSED
        );
        if (allTerminal && manual) {
            return AggregateStatus.COMPLETED_WITH_MANUAL_DISPOSITION;
        }
        boolean nonCanaryInFlight = facts.stream().anyMatch(
            fact -> !fact.canary() && fact.status() == InstanceStatus.IN_FLIGHT
        );
        if (signals.boundedRunning() || nonCanaryInFlight) {
            return AggregateStatus.BOUNDED_EXECUTION_RUNNING;
        }
        boolean canaryInFlight = facts.stream().anyMatch(
            fact -> fact.canary() && fact.status() == InstanceStatus.IN_FLIGHT
        );
        if (canaryInFlight) {
            return AggregateStatus.CANARY_RUNNING;
        }
        if (signals.canaryRunning()) {
            return AggregateStatus.CANARY_PENDING;
        }
        if (facts.stream().anyMatch(fact -> fact.status() == InstanceStatus.EXACTLY_COMPLETED)) {
            return AggregateStatus.PARTIALLY_COMPLETED;
        }
        if (signals.canarySelected()) {
            return AggregateStatus.CANARY_PENDING;
        }
        return AggregateStatus.NOT_STARTED;
    }

    private static Summary summary(
        AggregateStatus status,
        List<InstanceFact> facts,
        PlanSignals signals
    ) {
        int succeeded = (int) facts.stream()
            .filter(fact -> fact.status() == InstanceStatus.EXACTLY_COMPLETED)
            .count();
        int terminal = (int) facts.stream()
            .filter(fact -> fact.status() == InstanceStatus.EXACTLY_COMPLETED
                || fact.status() == InstanceStatus.MANUALLY_DISPOSED
                || fact.status() == InstanceStatus.TERMINAL_FAILURE)
            .count();
        return new Summary(
            status,
            facts.size(),
            terminal,
            succeeded,
            facts.size() - terminal,
            facts,
            signals
        );
    }
}
