package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalDisposition;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore.PreparedDispatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.AmbiguousMigrationDispatchException;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort.MigrationDispatchResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Executes one exact migration request with no platform transaction around the engine call. */
public final class ApprovalMigrationSingleInstanceExecutor {

    private final ApprovalMigrationEngineExecutionStore executionStore;
    private final ProcessInstanceMigrationPort engineMigration;
    private final Clock clock;

    public ApprovalMigrationSingleInstanceExecutor(
        ApprovalMigrationEngineExecutionStore executionStore,
        ProcessInstanceMigrationPort engineMigration,
        Clock clock
    ) {
        this.executionStore = Objects.requireNonNull(
            executionStore,
            "executionStore must not be null"
        );
        this.engineMigration = Objects.requireNonNull(
            engineMigration,
            "engineMigration must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        PreparedDispatch prepared = executionStore.prepare(new PrepareRequest(
            request.tenantId(),
            request.attemptId(),
            request.workerId(),
            request.expectedAttemptRevision(),
            request.expectedFenceRevision(),
            clock.instant(),
            request.requestId(),
            request.traceId()
        ));

        try {
            MigrationDispatchResult result = engineMigration.migrateOne(prepared.engineCommand());
            ApprovalMigrationAttempt stored = executionStore.finalizeOutcome(new FinalizeRequest(
                prepared,
                disposition(result),
                result.engineCallAttempted(),
                result.engineCallReturned(),
                false,
                stableCode(result),
                result.boundedSummary(),
                result.preDispatchSnapshot().snapshotHash(),
                clock.instant()
            ));
            return new ExecutionResult(stored, prepared.engineRequestId(), result.disposition().name());
        } catch (AmbiguousMigrationDispatchException exception) {
            ApprovalMigrationAttempt stored = persistUnknown(
                prepared,
                exception.stableCode(),
                exception.getMessage(),
                exception.engineCallMayHaveOccurred()
            );
            return new ExecutionResult(stored, prepared.engineRequestId(), "AMBIGUOUS_UNKNOWN");
        } catch (RuntimeException exception) {
            ApprovalMigrationAttempt stored = persistUnknown(
                prepared,
                "ENGINE_PORT_UNEXPECTED",
                "engine dispatch ended without authoritative completion evidence",
                true
            );
            return new ExecutionResult(stored, prepared.engineRequestId(), "AMBIGUOUS_UNKNOWN");
        }
    }

    private ApprovalMigrationAttempt persistUnknown(
        PreparedDispatch prepared,
        String stableCode,
        String summary,
        boolean callMayHaveOccurred
    ) {
        return executionStore.finalizeOutcome(new FinalizeRequest(
            prepared,
            FinalDisposition.AMBIGUOUS_UNKNOWN,
            callMayHaveOccurred,
            false,
            callMayHaveOccurred,
            stableCode,
            summary,
            sha256("unknown-pre-dispatch|" + prepared.requestEvidenceHash()),
            clock.instant()
        ));
    }

    private static FinalDisposition disposition(MigrationDispatchResult result) {
        return switch (result.disposition()) {
            case PRE_DISPATCH_REJECTED -> FinalDisposition.PRE_DISPATCH_REJECTED;
            case ENGINE_REJECTED -> FinalDisposition.ENGINE_REJECTED;
            case CALL_RETURNED_AWAITING_VERIFICATION ->
                FinalDisposition.CALL_RETURNED_AWAITING_VERIFICATION;
        };
    }

    private static String stableCode(MigrationDispatchResult result) {
        if (!result.validationCodes().isEmpty()) {
            return result.validationCodes().getFirst();
        }
        return switch (result.disposition()) {
            case PRE_DISPATCH_REJECTED -> "PRE_DISPATCH_REJECTED";
            case ENGINE_REJECTED -> "ENGINE_REJECTED";
            case CALL_RETURNED_AWAITING_VERIFICATION -> "ENGINE_CALL_RETURNED";
        };
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

    public record ExecutionRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        String requestId,
        String traceId
    ) {
        public ExecutionRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            if (expectedAttemptRevision < 1 || expectedFenceRevision < 1) {
                throw new IllegalArgumentException("execution revisions must be positive");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    public record ExecutionResult(
        ApprovalMigrationAttempt attempt,
        UUID engineRequestId,
        String disposition
    ) {
        public ExecutionResult {
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            engineRequestId = Objects.requireNonNull(
                engineRequestId,
                "engineRequestId must not be null"
            );
            disposition = requireText(disposition, "disposition", 64);
        }
    }

    /** Internal one-shot gate. It contains no loop, scheduler or retry. */
    public static final class OneShotRunner {
        private final boolean executionEnabled;
        private final boolean workerEnabled;
        private final ApprovalMigrationSingleInstanceExecutor executor;

        public OneShotRunner(
            boolean executionEnabled,
            boolean workerEnabled,
            ApprovalMigrationSingleInstanceExecutor executor
        ) {
            this.executionEnabled = executionEnabled;
            this.workerEnabled = workerEnabled;
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
        }

        public ExecutionResult runOnce(ExecutionRequest request) {
            if (!executionEnabled || !workerEnabled) {
                throw new IllegalStateException("migration execution and worker must be explicitly enabled");
            }
            return executor.execute(request);
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
