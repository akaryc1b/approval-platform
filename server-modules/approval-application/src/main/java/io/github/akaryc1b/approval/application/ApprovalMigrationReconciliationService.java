package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.PreparedReconciliation;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore.StoredReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort.VerificationReadException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Reconciles one durable ambiguous attempt through one read-only engine observation. */
public final class ApprovalMigrationReconciliationService {

    private final ApprovalMigrationReconciliationStore reconciliationStore;
    private final ProcessInstanceVerificationPort engineVerification;
    private final Clock clock;
    private final Duration leaseDuration;

    public ApprovalMigrationReconciliationService(
        ApprovalMigrationReconciliationStore reconciliationStore,
        ProcessInstanceVerificationPort engineVerification,
        Clock clock,
        Duration leaseDuration
    ) {
        this.reconciliationStore = Objects.requireNonNull(
            reconciliationStore,
            "reconciliationStore must not be null"
        );
        this.engineVerification = Objects.requireNonNull(
            engineVerification,
            "engineVerification must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isZero() || leaseDuration.isNegative()
            || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be positive and at most 15 minutes");
        }
    }

    public StoredReconciliation reconcile(ReconciliationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        var preparedAt = clock.instant();
        PreparedReconciliation prepared = reconciliationStore.prepare(new PrepareRequest(
            request.tenantId(),
            request.attemptId(),
            request.workerId(),
            request.expectedAttemptRevision(),
            preparedAt,
            preparedAt.plus(leaseDuration),
            request.requestId(),
            request.traceId()
        ));
        if (prepared.replayed()) {
            return prepared.replay();
        }

        ApprovalMigrationEngineSnapshot snapshot;
        try {
            snapshot = engineVerification.readOne(prepared.engineCommand());
        } catch (VerificationReadException exception) {
            snapshot = ApprovalMigrationEngineSnapshot.readFailure(
                exception.stableCode(),
                sha256("m5-reconciliation-read-failure-v1|"
                    + prepared.requestHash() + '|' + exception.stableCode())
            );
        } catch (RuntimeException exception) {
            snapshot = ApprovalMigrationEngineSnapshot.readFailure(
                "ENGINE_RECONCILIATION_READ_UNEXPECTED",
                sha256("m5-reconciliation-read-failure-v1|"
                    + prepared.requestHash() + "|ENGINE_RECONCILIATION_READ_UNEXPECTED")
            );
        }
        var classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            prepared.attempt().sourceEngineDefinitionId(),
            prepared.attempt().targetEngineDefinitionId()
        );
        return reconciliationStore.finalizeObservation(new FinalizeRequest(
            prepared,
            snapshot,
            classification,
            clock.instant()
        ));
    }

    public record ReconciliationRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        String requestId,
        String traceId
    ) {
        public ReconciliationRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            if (expectedAttemptRevision < 1) {
                throw new IllegalArgumentException("expectedAttemptRevision must be positive");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    /** Internal one-shot gate. There is no polling loop, scheduler or migration redispatch. */
    public static final class OneShotRunner {
        private final boolean executionEnabled;
        private final boolean workerEnabled;
        private final boolean automaticReconciliationEnabled;
        private final ApprovalMigrationReconciliationService service;

        public OneShotRunner(
            boolean executionEnabled,
            boolean workerEnabled,
            boolean automaticReconciliationEnabled,
            ApprovalMigrationReconciliationService service
        ) {
            this.executionEnabled = executionEnabled;
            this.workerEnabled = workerEnabled;
            this.automaticReconciliationEnabled = automaticReconciliationEnabled;
            this.service = Objects.requireNonNull(service, "service must not be null");
        }

        public StoredReconciliation runOnce(ReconciliationRequest request) {
            if (!executionEnabled || !workerEnabled || !automaticReconciliationEnabled) {
                throw new IllegalStateException(
                    "migration reconciliation requires explicit execution, worker and reconciliation enablement"
                );
            }
            return service.reconcile(request);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
