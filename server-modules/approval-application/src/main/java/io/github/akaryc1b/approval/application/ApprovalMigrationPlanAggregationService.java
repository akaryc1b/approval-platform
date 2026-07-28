package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry.Event;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Internal D8 one-shot aggregation. It contains no Flowable call or runtime-binding mutation. */
public final class ApprovalMigrationPlanAggregationService {

    private final ApprovalMigrationPlanAggregationStore store;
    private final Clock clock;
    private final ApprovalMigrationSafetyTelemetry telemetry;

    public ApprovalMigrationPlanAggregationService(
        ApprovalMigrationPlanAggregationStore store,
        Clock clock
    ) {
        this(store, clock, ApprovalMigrationSafetyTelemetry.NOOP);
    }

    public ApprovalMigrationPlanAggregationService(
        ApprovalMigrationPlanAggregationStore store,
        Clock clock,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.telemetry = ApprovalMigrationSafetyTelemetry.require(telemetry);
    }

    public AggregationResult aggregateOnce(AggregateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AggregationResult result = store.aggregate(new AggregationRequest(
            command.context(),
            command.planId(),
            command.expectedAggregateRevision(),
            command.reason(),
            clock.instant()
        ));
        ApprovalMigrationSafetyTelemetry.safeRecord(
            telemetry,
            Event.PLAN_AGGREGATION_COMPLETED
        );
        return result;
    }

    public record AggregateCommand(
        RequestContext context,
        UUID planId,
        long expectedAggregateRevision,
        String reason
    ) {
        public AggregateCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            if (expectedAggregateRevision < 1) {
                throw new IllegalArgumentException(
                    "expectedAggregateRevision must be positive"
                );
            }
            reason = requireText(reason, "reason", 1000);
        }
    }

    /** Default-disabled internal one-shot gate. It never loops or scans tenants. */
    public static final class OneShotRunner {
        private final boolean aggregationEnabled;
        private final ApprovalMigrationPlanAggregationService service;

        public OneShotRunner(
            boolean aggregationEnabled,
            ApprovalMigrationPlanAggregationService service
        ) {
            this.aggregationEnabled = aggregationEnabled;
            this.service = Objects.requireNonNull(service, "service must not be null");
        }

        public AggregationResult runOnce(AggregateCommand command) {
            if (!aggregationEnabled) {
                throw new IllegalStateException(
                    "migration plan aggregation must be explicitly enabled"
                );
            }
            return service.aggregateOnce(command);
        }
    }

    private static String requireText(String value, String name, int maximum) {
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
