package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;

import java.util.Objects;

/** Canonicalizes MySQL D7 evidence times before hashes and JSON are produced. */
final class JdbcMySqlCanonicalApprovalMigrationOrchestrationStore
    implements ApprovalMigrationOrchestrationStore {

    private final ApprovalMigrationOrchestrationStore delegate;

    JdbcMySqlCanonicalApprovalMigrationOrchestrationStore(
        ApprovalMigrationOrchestrationStore delegate
    ) {
        this.delegate = Objects.requireNonNull(
            delegate,
            "delegate must not be null"
        );
    }

    @Override
    public PreparedOrchestration prepare(PrepareRequest request) {
        PrepareRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        return delegate.prepare(new PrepareRequest(
            exact.tenantId(),
            exact.intentId(),
            exact.requestedLimit(),
            exact.expectedRunRevision(),
            exact.killSwitch(),
            AuditHashCanonicalizer.canonicalInstant(exact.happenedAt()),
            exact.requestId(),
            exact.traceId()
        ));
    }

    @Override
    public DispatchAuthorization authorizeDispatch(DispatchRequest request) {
        DispatchRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        return delegate.authorizeDispatch(new DispatchRequest(
            exact.run(),
            exact.attemptId(),
            exact.expectedRunRevision(),
            exact.expectedKillSwitchRevision(),
            exact.observedKillSwitch(),
            AuditHashCanonicalizer.canonicalInstant(exact.happenedAt()),
            exact.requestId(),
            exact.traceId()
        ));
    }

    @Override
    public FinalizedOrchestration finalizeRun(FinalizeRequest request) {
        FinalizeRequest exact = Objects.requireNonNull(
            request,
            "request must not be null"
        );
        return delegate.finalizeRun(new FinalizeRequest(
            exact.prepared(),
            exact.claimBatch(),
            exact.processedAttemptIds(),
            AuditHashCanonicalizer.canonicalInstant(exact.happenedAt()),
            exact.requestId(),
            exact.traceId()
        ));
    }
}
