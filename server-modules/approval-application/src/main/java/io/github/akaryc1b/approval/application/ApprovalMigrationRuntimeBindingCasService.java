package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.CompletionRequest;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Completes one exact target verification through one platform binding-CAS transaction. */
public final class ApprovalMigrationRuntimeBindingCasService {

    private final ApprovalMigrationRuntimeBindingCasStore store;
    private final Clock clock;

    public ApprovalMigrationRuntimeBindingCasService(
        ApprovalMigrationRuntimeBindingCasStore store,
        Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public BindingCasResult complete(CompletionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        return store.complete(new CompletionRequest(
            command.tenantId(),
            command.attemptId(),
            command.verificationId(),
            command.workerId(),
            command.expectedAttemptRevision(),
            command.expectedFenceRevision(),
            command.expectedBindingRevision(),
            clock.instant(),
            command.requestId(),
            command.traceId()
        ));
    }

    public record CompletionCommand(
        String tenantId,
        UUID attemptId,
        UUID verificationId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        long expectedBindingRevision,
        String requestId,
        String traceId
    ) {
        public CompletionCommand {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            verificationId = Objects.requireNonNull(
                verificationId,
                "verificationId must not be null"
            );
            workerId = requireText(workerId, "workerId", 200);
            requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
            requirePositive(expectedFenceRevision, "expectedFenceRevision");
            requirePositive(expectedBindingRevision, "expectedBindingRevision");
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    /** Internal one-shot gate. It contains no loop, scheduler, engine call or retry. */
    public static final class OneShotRunner {
        private final boolean executionEnabled;
        private final boolean workerEnabled;
        private final ApprovalMigrationRuntimeBindingCasService service;

        public OneShotRunner(
            boolean executionEnabled,
            boolean workerEnabled,
            ApprovalMigrationRuntimeBindingCasService service
        ) {
            this.executionEnabled = executionEnabled;
            this.workerEnabled = workerEnabled;
            this.service = Objects.requireNonNull(service, "service must not be null");
        }

        public BindingCasResult runOnce(CompletionCommand command) {
            if (!executionEnabled || !workerEnabled) {
                throw new IllegalStateException(
                    "migration execution and worker must be explicitly enabled"
                );
            }
            return service.complete(command);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
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
