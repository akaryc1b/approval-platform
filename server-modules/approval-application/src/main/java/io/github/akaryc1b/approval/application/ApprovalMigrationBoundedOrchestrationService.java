package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline.PipelineRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline.PipelineResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationBoundedClaimCoordinator;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.DispatchAuthorization;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.DispatchRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.FinalizeRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.FinalizedOrchestration;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PrepareRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore.PreparedOrchestration;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry.Event;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.AttemptDisposition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationPhase;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal one-shot D7 orchestration. The only loop is bounded by the accepted D2 limit. */
public final class ApprovalMigrationBoundedOrchestrationService {

    private final ApprovalMigrationOrchestrationStore store;
    private final ApprovalMigrationBoundedClaimCoordinator claims;
    private final ApprovalMigrationAttemptPipeline pipeline;
    private final ApprovalMigrationKillSwitch killSwitch;
    private final Clock clock;
    private final ApprovalMigrationSafetyTelemetry telemetry;

    public ApprovalMigrationBoundedOrchestrationService(
        ApprovalMigrationOrchestrationStore store,
        ApprovalMigrationBoundedClaimCoordinator claims,
        ApprovalMigrationAttemptPipeline pipeline,
        ApprovalMigrationKillSwitch killSwitch,
        Clock clock
    ) {
        this(
            store,
            claims,
            pipeline,
            killSwitch,
            clock,
            ApprovalMigrationSafetyTelemetry.NOOP
        );
    }

    public ApprovalMigrationBoundedOrchestrationService(
        ApprovalMigrationOrchestrationStore store,
        ApprovalMigrationBoundedClaimCoordinator claims,
        ApprovalMigrationAttemptPipeline pipeline,
        ApprovalMigrationKillSwitch killSwitch,
        Clock clock,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.claims = Objects.requireNonNull(claims, "claims must not be null");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.telemetry = ApprovalMigrationSafetyTelemetry.require(telemetry);
    }

    public RunResult runOnce(RunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ApprovalMigrationKillSwitch.Snapshot initialSwitch = killSwitch.snapshot();
        if (command.expectedKillSwitchRevision() != initialSwitch.revision()) {
            throw new ApprovalMigrationOrchestrationStore.OrchestrationConflictException(
                "kill-switch revision is stale before orchestration preparation"
            );
        }
        PreparedOrchestration prepared = store.prepare(new PrepareRequest(
            command.tenantId(),
            command.intentId(),
            command.limit(),
            command.expectedRunRevision(),
            initialSwitch,
            clock.instant(),
            command.requestId(),
            command.traceId()
        ));
        if (prepared.replayed() && !prepared.finalized()) {
            throw new ApprovalMigrationOrchestrationStore.OrchestrationConflictException(
                "exact orchestration request is already in progress"
            );
        }
        if (prepared.replayed() || prepared.finalized() || !prepared.dispatchEligible()) {
            if (!prepared.dispatchEligible() && initialSwitch.enabled()) {
                ApprovalMigrationSafetyTelemetry.safeRecord(
                    telemetry,
                    Event.KILL_SWITCH_BLOCKED
                );
            }
            FinalizedOrchestration finalized = store.finalizeRun(new FinalizeRequest(
                prepared,
                null,
                List.of(),
                clock.instant(),
                stageRequestId(command.requestId(), "finalize"),
                command.traceId()
            ));
            return new RunResult(prepared, finalized, List.of());
        }

        int claimLimit = prepared.run().phase() == OrchestrationPhase.CANARY
            ? 1
            : command.limit();
        ClaimResult claimed = claims.claim(
            command.tenantId(),
            command.intentId(),
            claimLimit,
            stageRequestId(command.requestId(), "claim"),
            command.traceId()
        );
        requireCanonicalCanary(prepared, claimed);
        if (prepared.run().phase() == OrchestrationPhase.CANARY
            && claimed.attempts().size() == 1) {
            ApprovalMigrationSafetyTelemetry.safeRecord(
                telemetry,
                Event.CANARY_LIMIT_REACHED
            );
        }

        List<PipelineResult> results = new ArrayList<>();
        List<UUID> processed = new ArrayList<>();
        boolean boundedStop = false;
        for (int index = 0; index < claimed.attempts().size(); index++) {
            ApprovalMigrationAttempt attempt = claimed.attempts().get(index);
            ApprovalMigrationCommandFence fence = claimed.fences().get(index);
            ApprovalMigrationKillSwitch.Snapshot observedSwitch = killSwitch.snapshot();
            DispatchAuthorization authorization = store.authorizeDispatch(new DispatchRequest(
                prepared.run(),
                attempt.attemptId(),
                command.expectedRunRevision(),
                command.expectedKillSwitchRevision(),
                observedSwitch,
                clock.instant(),
                stageRequestId(command.requestId(), "dispatch-" + index),
                command.traceId()
            ));
            if (!authorization.allowed()) {
                boundedStop = true;
                if (observedSwitch.enabled()) {
                    ApprovalMigrationSafetyTelemetry.safeRecord(
                        telemetry,
                        Event.KILL_SWITCH_BLOCKED
                    );
                }
                break;
            }
            PipelineResult result = pipeline.process(new PipelineRequest(
                attempt,
                fence,
                claimed.batch().workerId(),
                stageRequestId(command.requestId(), "attempt-" + index),
                command.traceId()
            ));
            results.add(result);
            processed.add(attempt.attemptId());
            if (result.disposition() != AttemptDisposition.EXACTLY_COMPLETED) {
                boundedStop = true;
                break;
            }
        }
        if (processed.size() >= claimLimit && !claimed.attempts().isEmpty()) {
            boundedStop = true;
        }
        if (boundedStop) {
            ApprovalMigrationSafetyTelemetry.safeRecord(
                telemetry,
                Event.ORCHESTRATION_BOUNDED_STOP
            );
        }

        FinalizedOrchestration finalized = store.finalizeRun(new FinalizeRequest(
            prepared,
            claimed.batch(),
            processed,
            clock.instant(),
            stageRequestId(command.requestId(), "finalize"),
            command.traceId()
        ));
        return new RunResult(prepared, finalized, results);
    }

    private static void requireCanonicalCanary(
        PreparedOrchestration prepared,
        ClaimResult claimed
    ) {
        if (prepared.run().phase() != OrchestrationPhase.CANARY
            || claimed.attempts().isEmpty()) {
            return;
        }
        ApprovalMigrationAttempt first = claimed.attempts().getFirst();
        if (!first.approvalInstanceId().equals(prepared.canary().approvalInstanceId())
            || claimed.attempts().size() != 1) {
            throw new ApprovalMigrationOrchestrationStore.OrchestrationConflictException(
                "D2 claim did not return the exact deterministic canary"
            );
        }
    }

    public record RunCommand(
        String tenantId,
        UUID intentId,
        int limit,
        long expectedRunRevision,
        long expectedKillSwitchRevision,
        String requestId,
        String traceId
    ) {
        public RunCommand {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            if (expectedRunRevision < 1 || expectedKillSwitchRevision < 1) {
                throw new IllegalArgumentException("orchestration revisions must be positive");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = traceId == null || traceId.isBlank()
                ? null
                : requireText(traceId, "traceId", 256);
        }
    }

    public record RunResult(
        PreparedOrchestration prepared,
        FinalizedOrchestration finalized,
        List<PipelineResult> attempts
    ) {
        public RunResult {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            finalized = Objects.requireNonNull(finalized, "finalized must not be null");
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    /** Default-disabled gate. It has no scheduler, polling loop or retry operation. */
    public static final class OneShotRunner {
        private final boolean executionEnabled;
        private final boolean workerEnabled;
        private final boolean orchestrationEnabled;
        private final ApprovalMigrationBoundedOrchestrationService service;

        public OneShotRunner(
            boolean executionEnabled,
            boolean workerEnabled,
            boolean orchestrationEnabled,
            ApprovalMigrationBoundedOrchestrationService service
        ) {
            this.executionEnabled = executionEnabled;
            this.workerEnabled = workerEnabled;
            this.orchestrationEnabled = orchestrationEnabled;
            this.service = Objects.requireNonNull(service, "service must not be null");
        }

        public RunResult runOnce(RunCommand command) {
            if (!executionEnabled || !workerEnabled || !orchestrationEnabled) {
                throw new IllegalStateException(
                    "migration execution, worker and orchestration must be explicitly enabled"
                );
            }
            return service.runOnce(command);
        }
    }

    private static String stageRequestId(String requestId, String stage) {
        String value = requestId + ':' + stage;
        if (value.length() <= 256) {
            return value;
        }
        return requestId.substring(0, Math.max(1, 255 - stage.length())) + ':' + stage;
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
