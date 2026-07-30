package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationBindingRevisionReader;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore.StoredVerification;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore.BindingCasResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry.Event;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.AttemptDisposition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;

import java.util.Objects;

/** Reuses the accepted D3, D4 and D5 services for one claimed instance. */
public final class ApprovalMigrationAttemptPipelineService
    implements ApprovalMigrationAttemptPipeline {

    private final ApprovalMigrationSingleInstanceExecutor executor;
    private final ApprovalMigrationExactVerificationService verifier;
    private final ApprovalMigrationRuntimeBindingCasService bindingCas;
    private final ApprovalMigrationBindingRevisionReader bindingRevisions;
    private final ApprovalMigrationSafetyTelemetry telemetry;

    public ApprovalMigrationAttemptPipelineService(
        ApprovalMigrationSingleInstanceExecutor executor,
        ApprovalMigrationExactVerificationService verifier,
        ApprovalMigrationRuntimeBindingCasService bindingCas,
        ApprovalMigrationBindingRevisionReader bindingRevisions
    ) {
        this(
            executor,
            verifier,
            bindingCas,
            bindingRevisions,
            ApprovalMigrationSafetyTelemetry.NOOP
        );
    }

    public ApprovalMigrationAttemptPipelineService(
        ApprovalMigrationSingleInstanceExecutor executor,
        ApprovalMigrationExactVerificationService verifier,
        ApprovalMigrationRuntimeBindingCasService bindingCas,
        ApprovalMigrationBindingRevisionReader bindingRevisions,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.bindingCas = Objects.requireNonNull(bindingCas, "bindingCas must not be null");
        this.bindingRevisions = Objects.requireNonNull(
            bindingRevisions,
            "bindingRevisions must not be null"
        );
        this.telemetry = ApprovalMigrationSafetyTelemetry.require(telemetry);
    }

    @Override
    public PipelineResult process(PipelineRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ApprovalMigrationAttempt executed = executor.execute(
            new ApprovalMigrationSingleInstanceExecutor.ExecutionRequest(
                request.attempt().tenantId(),
                request.attempt().attemptId(),
                request.workerId(),
                request.attempt().revision(),
                request.fence().revision(),
                stageRequestId(request.requestId(), "d3"),
                request.traceId()
            )
        ).attempt();
        if (executed.status() != AttemptStatus.VERIFYING) {
            if (executed.status() == AttemptStatus.BLOCKED_STALE) {
                ApprovalMigrationSafetyTelemetry.safeRecord(
                    telemetry,
                    Event.STALE_OWNERSHIP_REJECTED
                );
            }
            return new PipelineResult(
                executed.attemptId(),
                disposition(executed.status()),
                null,
                null,
                null
            );
        }

        StoredVerification verified = verifier.verify(
            new ApprovalMigrationExactVerificationService.VerificationRequest(
                executed.tenantId(),
                executed.attemptId(),
                request.workerId(),
                executed.revision(),
                request.fence().revision(),
                stageRequestId(request.requestId(), "d4"),
                request.traceId()
            )
        );
        if (verified.evidence().classification() != ExactClassification.EXACT_TARGET_RUNTIME
            || !verified.evidence().exactTargetRuntime()) {
            ApprovalMigrationSafetyTelemetry.safeRecord(
                telemetry,
                Event.VERIFICATION_MISMATCH
            );
            return new PipelineResult(
                executed.attemptId(),
                AttemptDisposition.RECONCILING,
                verified.evidence().verificationId(),
                null,
                null
            );
        }

        long bindingRevision = bindingRevisions.currentRevision(
            verified.attempt().tenantId(),
            verified.attempt().attemptId()
        );
        BindingCasResult result;
        try {
            result = bindingCas.complete(
                new ApprovalMigrationRuntimeBindingCasService.CompletionCommand(
                    verified.attempt().tenantId(),
                    verified.attempt().attemptId(),
                    verified.evidence().verificationId(),
                    request.workerId(),
                    verified.attempt().revision(),
                    request.fence().revision(),
                    bindingRevision,
                    stageRequestId(request.requestId(), "d5"),
                    request.traceId()
                )
            );
        } catch (BindingCasException exception) {
            ApprovalMigrationSafetyTelemetry.safeRecord(
                telemetry,
                Event.COMPLETION_EVIDENCE_FAILED
            );
            throw exception;
        }
        if (result.completed()) {
            return new PipelineResult(
                executed.attemptId(),
                AttemptDisposition.EXACTLY_COMPLETED,
                verified.evidence().verificationId(),
                result.completionEvidence().completionId(),
                null
            );
        }
        ApprovalMigrationSafetyTelemetry.safeRecord(
            telemetry,
            Event.RUNTIME_BINDING_CAS_FAILED
        );
        return new PipelineResult(
            executed.attemptId(),
            AttemptDisposition.BINDING_CONFLICT,
            verified.evidence().verificationId(),
            null,
            result.conflictEvidence().conflictId()
        );
    }

    private static AttemptDisposition disposition(AttemptStatus status) {
        return switch (status) {
            case UNKNOWN -> AttemptDisposition.UNKNOWN;
            case RECONCILING -> AttemptDisposition.RECONCILING;
            case FAILED_TERMINAL, BLOCKED_STALE, FAILED_RETRYABLE, CANCELLED ->
                AttemptDisposition.TERMINAL_FAILURE;
            case SUCCEEDED -> AttemptDisposition.EXACTLY_COMPLETED;
            case PENDING, CLAIMED, ENGINE_REQUESTED, VERIFYING -> AttemptDisposition.IN_FLIGHT;
        };
    }

    private static String stageRequestId(String requestId, String stage) {
        String value = requestId + ':' + stage;
        if (value.length() <= 256) {
            return value;
        }
        return requestId.substring(0, Math.max(1, 255 - stage.length())) + ':' + stage;
    }
}
