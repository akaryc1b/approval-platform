package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Internal D8 one-shot aggregation. It contains no Flowable call or runtime-binding mutation. */
public final class ApprovalMigrationPlanAggregationService {

    private final ApprovalMigrationPlanAggregationStore store;
    private final Clock clock;

    public ApprovalMigrationPlanAggregationService(
        ApprovalMigrationPlanAggregationStore store,
        Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AggregationResult aggregateOnce(AggregateCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return store.aggregate(new AggregationRequest(
            command.tenantId(),
            command.intentId(),
            command.expectedAggregateRevision(),
            clock.instant(),
            command.requestId(),
            command.traceId()
        ));
    }

    public record AggregateCommand(
        String tenantId,
        UUID intentId,
        long expectedAggregateRevision,
        String requestId,
        String traceId
    ) {
        public AggregateCommand {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            if (expectedAggregateRevision < 1) {
                throw new IllegalArgumentException("expectedAggregateRevision must be positive");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    /** Default-disabled internal gate. */
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
