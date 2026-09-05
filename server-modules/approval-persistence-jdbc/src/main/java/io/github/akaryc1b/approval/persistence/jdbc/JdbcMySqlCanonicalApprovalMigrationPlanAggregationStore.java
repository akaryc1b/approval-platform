package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;

import java.util.Objects;

/** Canonicalizes MySQL D8 evidence times before hashes and JSON are produced. */
final class JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore
    implements ApprovalMigrationPlanAggregationStore {

    private final ApprovalMigrationPlanAggregationStore delegate;

    JdbcMySqlCanonicalApprovalMigrationPlanAggregationStore(
        ApprovalMigrationPlanAggregationStore delegate
    ) {
        this.delegate = Objects.requireNonNull(
            delegate,
            "delegate must not be null"
        );
    }

    @Override
    public AggregationResult aggregate(AggregationRequest request) {
        AggregationRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        return delegate.aggregate(new AggregationRequest(
            exact.context(),
            exact.planId(),
            exact.expectedAggregateRevision(),
            exact.reason(),
            AuditHashCanonicalizer.canonicalInstant(exact.happenedAt())
        ));
    }
}
