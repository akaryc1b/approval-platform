package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Atomic D8 plan aggregation from immutable server-owned evidence only. */
public interface ApprovalMigrationPlanAggregationStore {

    AggregationResult aggregate(AggregationRequest request);

    record AggregationRequest(
        RequestContext context,
        UUID planId,
        long expectedAggregateRevision,
        String reason,
        Instant happenedAt
    ) {
        public AggregationRequest {
            context = Objects.requireNonNull(context, "context must not be null");
            planId = Objects.requireNonNull(planId, "planId must not be null");
            if (expectedAggregateRevision < 1) {
                throw new IllegalArgumentException(
                    "expectedAggregateRevision must be positive"
                );
            }
            reason = requireText(reason, "reason", 1000);
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        }

        public String tenantId() {
            return context.tenantId();
        }

        public String operatorId() {
            return context.operatorId();
        }

        public String requestId() {
            return context.requestId();
        }

        public String idempotencyKey() {
            return context.idempotencyKey();
        }

        public String traceId() {
            return context.traceId();
        }
    }

    record AggregationResult(
        PlanAggregate aggregate,
        PlanAggregateEvent event,
        PlanCompletion completion,
        boolean replayed
    ) {
        public AggregationResult {
            aggregate = Objects.requireNonNull(aggregate, "aggregate must not be null");
            event = Objects.requireNonNull(event, "event must not be null");
            if (completion != null
                && (!completion.aggregateId().equals(aggregate.aggregateId())
                || !completion.aggregateHash().equals(aggregate.aggregateHash()))) {
                throw new IllegalArgumentException("completion does not match aggregate");
            }
        }
    }

    final class AggregationConflictException extends RuntimeException {
        public AggregationConflictException(String message) {
            super(message);
        }

        public AggregationConflictException(String message, Throwable cause) {
            super(message, cause);
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
