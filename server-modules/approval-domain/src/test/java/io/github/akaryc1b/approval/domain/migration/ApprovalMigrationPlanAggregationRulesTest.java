package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ApprovalMigrationPlanAggregationRulesTest {

    @Test
    void derivesAllExactlyCompletedAndManualCompletion() {
        Summary exact = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.EXACTLY_COMPLETED)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED, exact.status());
        assertEquals(2, exact.terminalCount());
        assertEquals(2, exact.succeededCount());
        assertEquals(0, exact.unresolvedCount());

        Summary manual = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.MANUALLY_DISPOSED)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.COMPLETED_WITH_MANUAL_DISPOSITION, manual.status());
        assertEquals(2, manual.terminalCount());
        assertEquals(1, manual.succeededCount());
    }

    @Test
    void unresolvedEvidenceAlwaysPrecedesCompletion() {
        assertStatus(InstanceStatus.UNKNOWN, AggregateStatus.UNKNOWN_PRESENT);
        assertStatus(InstanceStatus.RECONCILING, AggregateStatus.RECONCILIATION_PRESENT);
        assertStatus(
            InstanceStatus.MANUAL_REVIEW_REQUIRED,
            AggregateStatus.MANUAL_REVIEW_PRESENT
        );
        assertStatus(
            InstanceStatus.TERMINAL_FAILURE,
            AggregateStatus.TERMINAL_FAILURE_PRESENT
        );
        assertStatus(
            InstanceStatus.COMPLETION_CONFLICT,
            AggregateStatus.COMPLETION_CONFLICT
        );
        assertStatus(
            InstanceStatus.INVALID_INCOMPLETE_EVIDENCE,
            AggregateStatus.INVALID_INCOMPLETE_EVIDENCE
        );
    }

    @Test
    void derivesProgressAndCanaryStates() {
        Summary partial = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.NOT_STARTED)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.PARTIALLY_COMPLETED, partial.status());

        Summary canaryPending = summarize(List.of(
            fact(1, true, InstanceStatus.NOT_STARTED),
            fact(2, false, InstanceStatus.NOT_STARTED)
        ), signals(true, false, false, false, false, false));
        assertEquals(AggregateStatus.CANARY_PENDING, canaryPending.status());

        Summary canaryRunning = summarize(List.of(
            fact(1, true, InstanceStatus.IN_FLIGHT),
            fact(2, false, InstanceStatus.NOT_STARTED)
        ), signals(true, true, false, false, false, false));
        assertEquals(AggregateStatus.CANARY_RUNNING, canaryRunning.status());

        Summary bounded = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.IN_FLIGHT)
        ), signals(true, false, true, false, false, false));
        assertEquals(AggregateStatus.BOUNDED_EXECUTION_RUNNING, bounded.status());
    }

    @Test
    void killSwitchPauseAndIncompleteSignalsFailClosed() {
        List<InstanceFact> facts = List.of(
            fact(1, true, InstanceStatus.NOT_STARTED),
            fact(2, false, InstanceStatus.NOT_STARTED)
        );
        assertEquals(
            AggregateStatus.KILL_SWITCH_BLOCKED,
            summarize(facts, signals(true, false, false, true, true, false)).status()
        );
        assertEquals(
            AggregateStatus.PAUSED,
            summarize(facts, signals(true, false, false, true, false, false)).status()
        );
        assertEquals(
            AggregateStatus.INVALID_INCOMPLETE_EVIDENCE,
            summarize(facts, signals(true, false, false, false, false, true)).status()
        );
    }

    @Test
    void canonicalOrderingIsStableAndSequenceGapsFailClosed() {
        List<InstanceFact> reversed = new ArrayList<>(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.NOT_STARTED)
        ));
        Collections.reverse(reversed);
        Summary summary = summarize(reversed, PlanSignals.none());
        assertEquals(AggregateStatus.PARTIALLY_COMPLETED, summary.status());
        assertEquals(1, summary.canonicalFacts().get(0).sequenceNo());

        Summary invalid = summarize(List.of(
            fact(1, true, InstanceStatus.NOT_STARTED),
            new InstanceFact(
                3,
                new UUID(0, 3),
                false,
                InstanceStatus.NOT_STARTED,
                hash('3')
            )
        ), PlanSignals.none());
        assertEquals(AggregateStatus.INVALID_INCOMPLETE_EVIDENCE, invalid.status());
    }

    @Test
    void fiveThousandFactsRemainDeterministicAndBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<InstanceFact> facts = new ArrayList<>(5_000);
            for (int sequence = 1; sequence <= 5_000; sequence++) {
                facts.add(fact(sequence, sequence == 1, InstanceStatus.EXACTLY_COMPLETED));
            }
            Summary first = summarize(facts, PlanSignals.none());
            List<InstanceFact> reversed = new ArrayList<>(facts);
            Collections.reverse(reversed);
            Summary replay = summarize(reversed, PlanSignals.none());
            assertEquals(first, replay);
            assertEquals(AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED, first.status());
            assertEquals(5_000, first.succeededCount());
        });
    }

    private static void assertStatus(
        InstanceStatus instanceStatus,
        AggregateStatus aggregateStatus
    ) {
        Summary summary = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, instanceStatus)
        ), PlanSignals.none());
        assertEquals(aggregateStatus, summary.status());
    }

    private static Summary summarize(List<InstanceFact> facts, PlanSignals signals) {
        return ApprovalMigrationPlanAggregationRules.summarize(facts, signals);
    }

    private static InstanceFact fact(int sequence, boolean canary, InstanceStatus status) {
        return new InstanceFact(
            sequence,
            new UUID(0, sequence),
            canary,
            status,
            hash((char) ('a' + sequence % 6))
        );
    }

    private static PlanSignals signals(
        boolean canarySelected,
        boolean canaryRunning,
        boolean boundedRunning,
        boolean paused,
        boolean killSwitchBlocked,
        boolean incomplete
    ) {
        return new PlanSignals(
            canarySelected,
            canaryRunning,
            boundedRunning,
            paused,
            killSwitchBlocked,
            incomplete,
            hash('f')
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
