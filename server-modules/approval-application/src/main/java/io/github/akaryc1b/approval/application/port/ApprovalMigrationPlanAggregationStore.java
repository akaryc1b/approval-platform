package io.github.akaryc1b.approval.application.port;

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
        String tenantId,
        UUID intentId,
        long expectedAggregateRevision,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public AggregationRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            if (expectedAggregateRevision < 1) {
                throw new IllegalArgumentException("expectedAggregateRevision must be positive");
            }
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
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
