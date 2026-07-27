package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.AggregateInput;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.AggregateState;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.InstanceEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.InstanceState;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregation.TerminalOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationPlanAggregationTest {

    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final Instant NOW = Instant.parse("2026-07-27T13:00:00Z");

    @Test
    void marksOnlyExactCompletedInstancesAsSucceeded() {
        PlanAggregate aggregate = aggregate(input(
            PauseReason.NONE,
            InstanceState.EXACT_SUCCESS,
            InstanceState.EXACT_SUCCESS
        ));

        assertEquals(AggregateState.COMPLETED_SUCCEEDED, aggregate.aggregateState());
        assertEquals(TerminalOutcome.SUCCEEDED, aggregate.terminalOutcome());
        assertEquals(2, aggregate.exactSuccessCount());
        assertEquals(0, aggregate.unresolvedCount());
        assertTrue(aggregate.completionEvidenceHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void partialCompletionCannotBecomeSuccess() {
        PlanAggregate aggregate = aggregate(input(
            PauseReason.NONE,
            InstanceState.EXACT_SUCCESS,
            InstanceState.PENDING
        ));

        assertEquals(AggregateState.PARTIALLY_COMPLETED, aggregate.aggregateState());
        assertEquals(TerminalOutcome.UNRESOLVED, aggregate.terminalOutcome());
        assertEquals(1, aggregate.unresolvedCount());
        assertNull(aggregate.completionEvidenceHash());
    }

    @Test
    void unknownRemainsPausedAndUnresolved() {
        PlanAggregate aggregate = aggregate(input(
            PauseReason.UNKNOWN,
            InstanceState.UNKNOWN,
            InstanceState.EXACT_SUCCESS
        ));

        assertEquals(AggregateState.PAUSED_UNKNOWN, aggregate.aggregateState());
        assertEquals(1, aggregate.unknownCount());
        assertEquals(1, aggregate.unresolvedCount());
        assertTrue(aggregate.paused());
    }

    @Test
    void reconciliationManualReviewBindingConflictAndStaleStayClosed() {
        assertEquals(
            AggregateState.PAUSED_RECONCILIATION,
            aggregate(input(PauseReason.RECONCILIATION, InstanceState.RECONCILING)).aggregateState()
        );
        assertEquals(
            AggregateState.PAUSED_MANUAL_REVIEW,
            aggregate(input(PauseReason.MANUAL_REVIEW, InstanceState.MANUAL_REVIEW)).aggregateState()
        );
        assertEquals(
            AggregateState.PAUSED_BINDING_CONFLICT,
            aggregate(input(PauseReason.BINDING_CONFLICT, InstanceState.BINDING_CONFLICT))
                .aggregateState()
        );
        assertEquals(
            AggregateState.PAUSED_STALE_AUTHORITY,
            aggregate(input(PauseReason.STALE_AUTHORITY, InstanceState.BLOCKED_STALE))
                .aggregateState()
        );
    }

    @Test
    void immutableTerminalFailuresCompleteOnlyAsNonSuccess() {
        PlanAggregate aggregate = aggregate(input(
            PauseReason.NONE,
            InstanceState.EXACT_SUCCESS,
            InstanceState.TERMINAL_FAILED
        ));

        assertEquals(
            AggregateState.COMPLETED_WITH_TERMINAL_FAILURE,
            aggregate.aggregateState()
        );
        assertEquals(
            TerminalOutcome.COMPLETED_WITH_TERMINAL_FAILURE,
            aggregate.terminalOutcome()
        );
        assertEquals(1, aggregate.terminalFailedCount());
        assertNotEquals(TerminalOutcome.SUCCEEDED, aggregate.terminalOutcome());
    }

    @Test
    void incompleteOrDuplicateEvidenceFailsClosed() {
        List<InstanceEvidence> duplicated = List.of(
            evidence(1, 1, InstanceState.EXACT_SUCCESS),
            new InstanceEvidence(
                1,
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                "b".repeat(64),
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                InstanceState.EXACT_SUCCESS,
                "d".repeat(64)
            )
        );
        AggregateInput input = new AggregateInput(
            PLAN_ID,
            INTENT_ID,
            "a".repeat(64),
            2,
            CanaryStatus.COMPLETED,
            OrchestrationStatus.COMPLETED,
            PauseReason.NONE,
            true,
            duplicated
        );

        PlanAggregate aggregate = aggregate(input);

        assertEquals(
            AggregateState.INVALID_OR_INCOMPLETE_EVIDENCE,
            aggregate.aggregateState()
        );
        assertEquals(TerminalOutcome.INVALID_EVIDENCE, aggregate.terminalOutcome());
    }

    @Test
    void businessHashesIgnoreUuidClockAndAuditWriteMetadata() {
        AggregateInput input = input(
            PauseReason.NONE,
            InstanceState.EXACT_SUCCESS,
            InstanceState.EXACT_SUCCESS
        );
        PlanAggregate first = ApprovalMigrationPlanAggregation.aggregate(
            UUID.fromString("00000000-0000-0000-0000-000000000401"),
            input,
            1,
            ApprovalMigrationPlanAggregation.ZERO_HASH,
            NOW,
            "audit-event:first"
        );
        PlanAggregate second = ApprovalMigrationPlanAggregation.aggregate(
            UUID.fromString("00000000-0000-0000-0000-000000000402"),
            input,
            1,
            ApprovalMigrationPlanAggregation.ZERO_HASH,
            NOW.plusSeconds(30),
            "audit-event:second"
        );

        assertEquals(first.inputEvidenceSetHash(), second.inputEvidenceSetHash());
        assertEquals(first.aggregateHash(), second.aggregateHash());
        assertEquals(first.completionEvidenceHash(), second.completionEvidenceHash());
    }

    @Test
    void changedEvidenceChangesHash() {
        PlanAggregate first = aggregate(input(PauseReason.NONE, InstanceState.EXACT_SUCCESS));
        PlanAggregate second = aggregate(input(PauseReason.UNKNOWN, InstanceState.UNKNOWN));

        assertNotEquals(first.inputEvidenceSetHash(), second.inputEvidenceSetHash());
        assertNotEquals(first.aggregateHash(), second.aggregateHash());
    }

    @Test
    void deterministicallyClassifiesFiveThousandBoundedFacts() {
        List<InstanceEvidence> instances = new ArrayList<>(5_000);
        for (int index = 1; index <= 5_000; index++) {
            instances.add(evidence(index, index, InstanceState.EXACT_SUCCESS));
        }
        AggregateInput input = new AggregateInput(
            PLAN_ID,
            INTENT_ID,
            "a".repeat(64),
            instances.size(),
            CanaryStatus.COMPLETED,
            OrchestrationStatus.COMPLETED,
            PauseReason.NONE,
            true,
            instances
        );

        PlanAggregate first = aggregate(input);
        PlanAggregate second = aggregate(input);

        assertEquals(5_000, first.exactSuccessCount());
        assertEquals(AggregateState.COMPLETED_SUCCEEDED, first.aggregateState());
        assertEquals(first.inputEvidenceSetHash(), second.inputEvidenceSetHash());
        assertEquals(first.aggregateHash(), second.aggregateHash());
    }

    private static PlanAggregate aggregate(AggregateInput input) {
        return ApprovalMigrationPlanAggregation.aggregate(
            UUID.fromString("00000000-0000-0000-0000-000000000400"),
            input,
            1,
            ApprovalMigrationPlanAggregation.ZERO_HASH,
            NOW,
            "audit-event:00000000-0000-0000-0000-000000000500"
        );
    }

    private static AggregateInput input(PauseReason reason, InstanceState... states) {
        List<InstanceEvidence> instances = new ArrayList<>(states.length);
        for (int index = 0; index < states.length; index++) {
            instances.add(evidence(index + 1, index + 1, states[index]));
        }
        return new AggregateInput(
            PLAN_ID,
            INTENT_ID,
            "a".repeat(64),
            states.length,
            CanaryStatus.COMPLETED,
            reason == PauseReason.NONE
                ? OrchestrationStatus.COMPLETED
                : OrchestrationStatus.PAUSED,
            reason,
            reason == PauseReason.KILL_SWITCH,
            instances
        );
    }

    private static InstanceEvidence evidence(int sequence, int suffix, InstanceState state) {
        UUID approvalInstanceId = UUID.fromString(String.format(
            "00000000-0000-0000-0000-%012d",
            200L + suffix
        ));
        UUID attemptId = state == InstanceState.UNPROVISIONED
            ? null
            : UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012d",
                300L + suffix
            ));
        return new InstanceEvidence(
            sequence,
            approvalInstanceId,
            "b".repeat(64),
            attemptId,
            state,
            state == InstanceState.EXACT_SUCCESS ? "c".repeat(64) : "d".repeat(64)
        );
    }
}
