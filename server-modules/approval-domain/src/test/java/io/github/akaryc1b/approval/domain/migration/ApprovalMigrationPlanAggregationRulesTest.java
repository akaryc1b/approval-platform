package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;
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
    void exactSuccessAndTerminalFailureUseDifferentTerminalOutcomes() {
        Summary success = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.EXACTLY_COMPLETED)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.COMPLETED_SUCCEEDED, success.status());
        assertEquals(TerminalOutcome.SUCCEEDED, success.terminalOutcome());
        assertEquals(2, success.counts().exactSuccessCount());
        assertEquals(0, success.counts().unresolvedCount());

        Summary failed = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.TERMINAL_FAILURE)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE, failed.status());
        assertEquals(
            TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE,
            failed.terminalOutcome()
        );
        assertEquals(1, failed.counts().terminalFailedCount());
    }

    @Test
    void unresolvedEvidenceAlwaysFailsClosed() {
        assertPaused(InstanceStatus.UNKNOWN, PauseReason.UNKNOWN);
        assertPaused(InstanceStatus.RECONCILING, PauseReason.RECONCILIATION);
        assertPaused(InstanceStatus.MANUAL_REVIEW_REQUIRED, PauseReason.MANUAL_REVIEW);
        assertPaused(InstanceStatus.BINDING_CONFLICT, PauseReason.BINDING_CONFLICT);
        assertPaused(InstanceStatus.BLOCKED_STALE, PauseReason.STALE_AUTHORITY);

        Summary invalid = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.INVALID_INCOMPLETE_EVIDENCE)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE, invalid.status());
        assertEquals(TerminalOutcome.INVALID_EVIDENCE, invalid.terminalOutcome());
    }

    @Test
    void derivesProgressCountsAndClosedSignals() {
        Summary pending = summarize(List.of(
            fact(1, true, InstanceStatus.UNPROVISIONED),
            fact(2, false, InstanceStatus.UNPROVISIONED)
        ), signals(
            CanaryStatus.PENDING,
            OrchestrationStatus.NOT_STARTED,
            PauseReason.NONE,
            false,
            false
        ));
        assertEquals(AggregateStatus.CANARY_PENDING, pending.status());
        assertEquals(2, pending.counts().pendingCount());
        assertEquals(0, pending.counts().provisionedAttemptCount());

        Summary canary = summarize(List.of(
            fact(1, true, InstanceStatus.ENGINE_REQUESTED),
            fact(2, false, InstanceStatus.PENDING)
        ), signals(
            CanaryStatus.IN_PROGRESS,
            OrchestrationStatus.CANARY_IN_PROGRESS,
            PauseReason.NONE,
            true,
            false
        ));
        assertEquals(AggregateStatus.CANARY_IN_PROGRESS, canary.status());
        assertEquals(1, canary.counts().engineRequestedCount());

        Summary bounded = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.VERIFYING)
        ), signals(
            CanaryStatus.COMPLETED,
            OrchestrationStatus.BOUNDED_IN_PROGRESS,
            PauseReason.NONE,
            true,
            false
        ));
        assertEquals(AggregateStatus.PARTIALLY_COMPLETED, bounded.status());
        assertEquals(1, bounded.counts().verifyingCount());
    }

    @Test
    void killSwitchAndIncompleteSignalsCannotBecomeSuccess() {
        List<InstanceFact> facts = List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.EXACTLY_COMPLETED)
        );
        Summary killSwitch = summarize(facts, signals(
            CanaryStatus.PAUSED,
            OrchestrationStatus.PAUSED,
            PauseReason.KILL_SWITCH,
            true,
            false
        ));
        assertEquals(AggregateStatus.PAUSED, killSwitch.status());
        assertEquals(PauseReason.KILL_SWITCH, killSwitch.signals().pauseReason());

        Summary incomplete = summarize(facts, signals(
            CanaryStatus.INVALID,
            OrchestrationStatus.INVALID,
            PauseReason.INCOMPLETE_EVIDENCE,
            false,
            true
        ));
        assertEquals(AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE, incomplete.status());
    }

    @Test
    void canonicalOrderingIsStableAndSequenceGapsFailClosed() {
        List<InstanceFact> reversed = new ArrayList<>(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, InstanceStatus.PENDING)
        ));
        Collections.reverse(reversed);
        Summary summary = summarize(reversed, PlanSignals.none());
        assertEquals(AggregateStatus.PARTIALLY_COMPLETED, summary.status());
        assertEquals(1, summary.canonicalFacts().get(0).sequenceNo());

        Summary invalid = summarize(List.of(
            fact(1, true, InstanceStatus.PENDING),
            fact(3, false, InstanceStatus.PENDING)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.INVALID_OR_INCOMPLETE_EVIDENCE, invalid.status());
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
            assertEquals(AggregateStatus.COMPLETED_SUCCEEDED, first.status());
            assertEquals(5_000, first.counts().exactSuccessCount());
        });
    }

    private static void assertPaused(InstanceStatus status, PauseReason reason) {
        Summary summary = summarize(List.of(
            fact(1, true, InstanceStatus.EXACTLY_COMPLETED),
            fact(2, false, status)
        ), PlanSignals.none());
        assertEquals(AggregateStatus.PAUSED, summary.status());
        assertEquals(reason, summary.signals().pauseReason());
        assertEquals(1, summary.counts().unresolvedCount());
    }

    private static Summary summarize(List<InstanceFact> facts, PlanSignals signals) {
        return ApprovalMigrationPlanAggregationRules.summarize(facts, signals);
    }

    private static InstanceFact fact(int sequence, boolean canary, InstanceStatus status) {
        UUID attemptId = status == InstanceStatus.UNPROVISIONED
            ? null
            : new UUID(1, sequence);
        return new InstanceFact(
            sequence,
            new UUID(0, sequence),
            canary,
            attemptId,
            status,
            hash((char) ('a' + sequence % 6)),
            hash((char) ('f' - sequence % 6))
        );
    }

    private static PlanSignals signals(
        CanaryStatus canaryStatus,
        OrchestrationStatus orchestrationStatus,
        PauseReason pauseReason,
        boolean killSwitchObserved,
        boolean incomplete
    ) {
        return new PlanSignals(
            canaryStatus,
            orchestrationStatus,
            pauseReason,
            killSwitchObserved,
            incomplete,
            hash('f')
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
