package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationPlanAggregationService.AggregateCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
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
    private static final UUID INTENT = UUID.fromString(
        "88000000-0000-0000-0000-000000000001"
    );

    @Test
    void oneShotRunnerIsDefaultDisabled() {
        ApprovalMigrationPlanAggregationService service = service(new AtomicReference<>());
        ApprovalMigrationPlanAggregationService.OneShotRunner runner =
            new ApprovalMigrationPlanAggregationService.OneShotRunner(false, service);

        assertThrows(IllegalStateException.class, () -> runner.runOnce(command()));
    }

    @Test
    void enabledRunnerDelegatesOnlyServerOwnedRequestMaterial() {
        AtomicReference<AggregationRequest> observed = new AtomicReference<>();
        ApprovalMigrationPlanAggregationService.OneShotRunner runner =
            new ApprovalMigrationPlanAggregationService.OneShotRunner(
                true,
                service(observed)
            );

        AggregationResult result = runner.runOnce(command());

        assertEquals(AggregateStatus.NOT_STARTED, result.aggregate().status());
        assertEquals("tenant-a", observed.get().tenantId());
        assertEquals(INTENT, observed.get().intentId());
        assertEquals(1, observed.get().expectedAggregateRevision());
        assertEquals(NOW, observed.get().happenedAt());
        assertEquals("request-aggregation", observed.get().requestId());
    }

    @Test
    void commandRejectsMissingAuthorityAndStaleRevision() {
        assertThrows(NullPointerException.class, () -> new AggregateCommand(
            null,
            INTENT,
            1,
            "request",
            null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AggregateCommand(
            "tenant-a",
            INTENT,
            0,
            "request",
            null
        ));
    }

    private static ApprovalMigrationPlanAggregationService service(
        AtomicReference<AggregationRequest> observed
    ) {
        return new ApprovalMigrationPlanAggregationService(request -> {
            observed.set(request);
            PlanAggregate aggregate = aggregate(request);
            PlanAggregateEvent event = new PlanAggregateEvent(
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                request.tenantId(),
                aggregate.aggregateId(),
                aggregate.planId(),
                aggregate.intentId(),
                aggregate.aggregateRevision(),
                aggregate.status(),
                aggregate.aggregateHash(),
                hash('e'),
                request.happenedAt(),
                request.requestId(),
                request.traceId()
            );
            return new AggregationResult(aggregate, event, null, false);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PlanAggregate aggregate(AggregationRequest request) {
        return new PlanAggregate(
            UUID.fromString("88000000-0000-0000-0000-000000000002"),
            request.tenantId(),
            UUID.fromString("88000000-0000-0000-0000-000000000004"),
            request.intentId(),
            request.expectedAggregateRevision(),
            AggregateStatus.NOT_STARTED,
            2,
            0,
            0,
            2,
            hash('a'),
            hash('0'),
            hash('b'),
            hash('c'),
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
    }

    private static AggregateCommand command() {
        return new AggregateCommand(
            "tenant-a",
            INTENT,
            1,
            "request-aggregation",
            "trace-aggregation"
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
