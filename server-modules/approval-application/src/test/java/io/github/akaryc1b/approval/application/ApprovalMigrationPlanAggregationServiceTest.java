package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationPlanAggregationService.AggregateCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.StateCounts;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationPlanAggregationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T12:00:00Z");
    private static final UUID PLAN = UUID.fromString(
        "88000000-0000-0000-0000-000000000001"
    );
    private static final UUID INTENT = UUID.fromString(
        "88000000-0000-0000-0000-000000000002"
    );
    private static final RequestContext CONTEXT = new RequestContext(
        "tenant-a",
        "operator-a",
        "request-aggregation",
        "idempotency-aggregation",
        "trace-aggregation"
    );

    @Test
    void oneShotRunnerIsDefaultDisabled() {
        ApprovalMigrationPlanAggregationService service = service(new AtomicReference<>());
        ApprovalMigrationPlanAggregationService.OneShotRunner runner =
            new ApprovalMigrationPlanAggregationService.OneShotRunner(false, service);

        assertThrows(IllegalStateException.class, () -> runner.runOnce(command()));
    }

    @Test
    void enabledRunnerDelegatesOnlyServerOwnedContextAndExactPlan() {
        AtomicReference<AggregationRequest> observed = new AtomicReference<>();
        ApprovalMigrationPlanAggregationService.OneShotRunner runner =
            new ApprovalMigrationPlanAggregationService.OneShotRunner(
                true,
                service(observed)
            );

        AggregationResult result = runner.runOnce(command());

        assertEquals(AggregateStatus.NOT_STARTED, result.aggregate().status());
        assertEquals(CONTEXT, observed.get().context());
        assertEquals(PLAN, observed.get().planId());
        assertEquals(1, observed.get().expectedAggregateRevision());
        assertEquals(NOW, observed.get().happenedAt());
        assertEquals("Aggregate exact consumed plan", observed.get().reason());
    }

    @Test
    void commandRejectsMissingContextPlanAndStaleRevision() {
        assertThrows(NullPointerException.class, () -> new AggregateCommand(
            null,
            PLAN,
            1,
            "reason"
        ));
        assertThrows(NullPointerException.class, () -> new AggregateCommand(
            CONTEXT,
            null,
            1,
            "reason"
        ));
        assertThrows(IllegalArgumentException.class, () -> new AggregateCommand(
            CONTEXT,
            PLAN,
            0,
            "reason"
        ));
    }

    private static ApprovalMigrationPlanAggregationService service(
        AtomicReference<AggregationRequest> observed
    ) {
        return new ApprovalMigrationPlanAggregationService(request -> {
            observed.set(request);
            PlanAggregate aggregate = aggregate(request);
            PlanAggregateEvent event = new PlanAggregateEvent(
                UUID.fromString("88000000-0000-0000-0000-000000000004"),
                request.tenantId(),
                aggregate.aggregateId(),
                aggregate.planId(),
                aggregate.intentId(),
                aggregate.aggregateRevision(),
                aggregate.status(),
                aggregate.terminalOutcome(),
                aggregate.pauseReason(),
                aggregate.predecessorHash(),
                aggregate.aggregateHash(),
                hash('e'),
                request.happenedAt(),
                request.requestId(),
                request.traceId(),
                aggregate.auditReference()
            );
            return new AggregationResult(aggregate, event, null, false);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PlanAggregate aggregate(AggregationRequest request) {
        StateCounts counts = new StateCounts(
            2,
            0,
            2,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            2
        );
        return new PlanAggregate(
            UUID.fromString("88000000-0000-0000-0000-000000000003"),
            request.tenantId(),
            request.operatorId(),
            request.planId(),
            INTENT,
            hash('a'),
            request.expectedAggregateRevision(),
            AggregateStatus.NOT_STARTED,
            TerminalOutcome.UNRESOLVED,
            counts,
            CanaryStatus.NOT_SELECTED,
            OrchestrationStatus.NOT_STARTED,
            false,
            PauseReason.NONE,
            false,
            hash('b'),
            hash('0'),
            request.idempotencyKey(),
            hash('c'),
            hash('d'),
            request.happenedAt(),
            request.reason(),
            request.requestId(),
            request.traceId(),
            "audit-event:88000000-0000-0000-0000-000000000005"
        );
    }

    private static AggregateCommand command() {
        return new AggregateCommand(
            CONTEXT,
            PLAN,
            1,
            "Aggregate exact consumed plan"
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
