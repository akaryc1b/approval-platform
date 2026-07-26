package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.PreparedVerification;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.StoredVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort.VerificationReadException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Reads and classifies one bounded snapshot without mutating runtime binding. */
public final class ApprovalMigrationExactVerificationService {

    private final ApprovalMigrationExactVerificationStore verificationStore;
    private final ProcessInstanceVerificationPort engineVerification;
    private final Clock clock;

    public ApprovalMigrationExactVerificationService(
        ApprovalMigrationExactVerificationStore verificationStore,
        ProcessInstanceVerificationPort engineVerification,
        Clock clock
    ) {
        this.verificationStore = Objects.requireNonNull(
            verificationStore,
            "verificationStore must not be null"
        );
        this.engineVerification = Objects.requireNonNull(
            engineVerification,
            "engineVerification must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public StoredVerification verify(VerificationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        PreparedVerification prepared = verificationStore.prepare(new PrepareRequest(
            request.tenantId(),
            request.attemptId(),
            request.workerId(),
            request.expectedAttemptRevision(),
            request.expectedFenceRevision(),
            clock.instant(),
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
                sha256("m5-verification-read-failure-v1|"
                    + prepared.requestHash() + '|' + exception.stableCode())
            );
        } catch (RuntimeException exception) {
            snapshot = ApprovalMigrationEngineSnapshot.readFailure(
                "ENGINE_READ_UNEXPECTED",
                sha256("m5-verification-read-failure-v1|"
                    + prepared.requestHash() + "|ENGINE_READ_UNEXPECTED")
            );
        }
        ExactClassification classification = ApprovalMigrationExactVerification.classify(
            snapshot,
            prepared.attempt().sourceEngineDefinitionId(),
            prepared.attempt().targetEngineDefinitionId()
        );
        return verificationStore.finalizeVerification(new FinalizeRequest(
            prepared,
            snapshot,
            classification,
            clock.instant()
        ));
    }

    public record VerificationRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        String requestId,
        String traceId
    ) {
        public VerificationRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            if (expectedAttemptRevision < 1 || expectedFenceRevision < 1) {
                throw new IllegalArgumentException("verification revisions must be positive");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    /** Internal one-shot gate. No scheduler, loop or verification-result input is exposed. */
    public static final class OneShotRunner {
        private final boolean executionEnabled;
        private final boolean workerEnabled;
        private final ApprovalMigrationExactVerificationService service;

        public OneShotRunner(
            boolean executionEnabled,
            boolean workerEnabled,
            ApprovalMigrationExactVerificationService service
        ) {
            this.executionEnabled = executionEnabled;
            this.workerEnabled = workerEnabled;
            this.service = Objects.requireNonNull(service, "service must not be null");
        }

        public StoredVerification runOnce(VerificationRequest request) {
            if (!executionEnabled || !workerEnabled) {
                throw new IllegalStateException("migration verification requires explicit execution and worker enablement");
            }
            return service.verify(request);
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
