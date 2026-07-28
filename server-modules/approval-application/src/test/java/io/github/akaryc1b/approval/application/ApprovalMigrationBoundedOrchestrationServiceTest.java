package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationBoundedClaimCoordinator;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.AttemptDisposition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanaryGate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.KillSwitchObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationPhase;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationRun;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationBoundedOrchestrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @Test
    void canonicalCanaryUsesOneD2ClaimAndOnePipeline() {
        Fixture fixture = new Fixture(false, AttemptDisposition.EXACTLY_COMPLETED);
        ApprovalMigrationBoundedOrchestrationService.RunResult result = fixture.service.runOnce(
            fixture.command()
        );

        assertEquals(1, fixture.claimLimit.get());
        assertEquals(1, fixture.pipelineCalls.get());
        assertEquals(RunEventType.CANARY_COMPLETED, result.finalized().event().eventType());
    }

    @Test
    void killSwitchBlocksBeforeClaimAndCannotFabricateCancellation() {
        Fixture fixture = new Fixture(true, AttemptDisposition.EXACTLY_COMPLETED);
        ApprovalMigrationBoundedOrchestrationService.RunResult result = fixture.service.runOnce(
            fixture.command()
        );

        assertEquals(0, fixture.claimLimit.get());
        assertEquals(0, fixture.pipelineCalls.get());
        assertEquals(RunEventType.KILL_SWITCH_BLOCKED, result.finalized().event().eventType());
    }

    @Test
    void ambiguousOutcomeStopsTheBoundedBatchWithoutRetry() {
        Fixture fixture = new Fixture(false, AttemptDisposition.UNKNOWN);
        ApprovalMigrationBoundedOrchestrationService.RunResult result = fixture.service.runOnce(
            fixture.command()
        );

        assertEquals(1, fixture.pipelineCalls.get());
        assertEquals(1, result.attempts().size());
        assertEquals(AttemptDisposition.UNKNOWN, result.attempts().getFirst().disposition());
    }

    @Test
    void runnerIsDefaultDisabled() {
        Fixture fixture = new Fixture(false, AttemptDisposition.EXACTLY_COMPLETED);
        ApprovalMigrationBoundedOrchestrationService.OneShotRunner runner =
            new ApprovalMigrationBoundedOrchestrationService.OneShotRunner(
                false,
                false,
                false,
                fixture.service
            );
        assertThrows(IllegalStateException.class, () -> runner.runOnce(fixture.command()));
    }

    private static final class Fixture {
        private final UUID planId = UUID.randomUUID();
        private final UUID intentId = UUID.randomUUID();
        private final UUID instanceId = UUID.randomUUID();
        private final UUID attemptId = UUID.randomUUID();
        private final UUID fenceId = UUID.randomUUID();
        private final UUID runId = UUID.randomUUID();
        private final UUID selectionId = UUID.randomUUID();
        private final AtomicInteger claimLimit = new AtomicInteger();
        private final AtomicInteger pipelineCalls = new AtomicInteger();
        private final ApprovalMigrationBoundedOrchestrationService service;

        private Fixture(boolean killEnabled, AttemptDisposition disposition) {
            ApprovalMigrationKillSwitch killSwitch = () -> new ApprovalMigrationKillSwitch.Snapshot(
                1,
                killEnabled,
                killEnabled ? "EMERGENCY_STOP" : "CONFIGURED_OFF",
                HASH_A
            );
            ApprovalMigrationBoundedClaimCoordinator claims = (
                tenantId,
                suppliedIntent,
                limit,
                requestId,
                traceId
            ) -> {
                claimLimit.set(limit);
                return claimResult();
            };
            ApprovalMigrationAttemptPipeline pipeline = request -> {
                pipelineCalls.incrementAndGet();
                return new ApprovalMigrationAttemptPipeline.PipelineResult(
                    request.attempt().attemptId(),
                    disposition,
                    disposition == AttemptDisposition.EXACTLY_COMPLETED ? UUID.randomUUID() : null,
                    disposition == AttemptDisposition.EXACTLY_COMPLETED ? UUID.randomUUID() : null,
                    null
                );
            };
            service = new ApprovalMigrationBoundedOrchestrationService(
                new FakeStore(killEnabled, disposition),
                claims,
                pipeline,
                killSwitch,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        }

        private ApprovalMigrationBoundedOrchestrationService.RunCommand command() {
            return new ApprovalMigrationBoundedOrchestrationService.RunCommand(
                "tenant-a",
                intentId,
                25,
                1,
                1,
                "request-1",
                "trace-1"
            );
        }

        private ClaimResult claimResult() {
            ApprovalMigrationAttempt attempt = new ApprovalMigrationAttempt(
                attemptId,
                "tenant-a",
                intentId,
                instanceId,
                "engine-instance-1",
                1,
                null,
                HASH_A,
                "source-definition",
                "target-definition",
                AttemptStatus.CLAIMED,
                EngineOutcome.NOT_REQUESTED,
                2,
                "worker-1",
                NOW.plusSeconds(300),
                null,
                FailureClass.NONE,
                null,
                NOW.minusSeconds(1),
                NOW,
                "claim-request",
                "trace-1"
            );
            ApprovalMigrationCommandFence fence = new ApprovalMigrationCommandFence(
                fenceId,
                "tenant-a",
                instanceId,
                attemptId,
                ApprovalCommandOperation.MIGRATION,
                ApprovalMigrationCommandFence.FenceStatus.ACTIVE,
                1,
                "worker-1",
                NOW.plusSeconds(300),
                "claim-request:" + attemptId,
                HASH_B,
                NOW,
                NOW,
                null,
                "claim-request",
                "trace-1"
            );
            ApprovalMigrationClaimBatch batch = new ApprovalMigrationClaimBatch(
                UUID.randomUUID(),
                "tenant-a",
                intentId,
                "worker-1",
                1,
                List.of(attemptId),
                List.of(fenceId),
                HASH_C,
                NOW,
                "claim-request",
                "trace-1"
            );
            return new ClaimResult(batch, List.of(attempt), List.of(fence), false);
        }

        private final class FakeStore implements ApprovalMigrationOrchestrationStore {
            private final boolean blocked;
            private final AttemptDisposition disposition;
            private final OrchestrationRun run;
            private final CanarySelection canary;
            private final OrchestrationEvent initial;

            private FakeStore(boolean blocked, AttemptDisposition disposition) {
                this.blocked = blocked;
                this.disposition = disposition;
                run = new OrchestrationRun(
                    runId,
                    "tenant-a",
                    planId,
                    intentId,
                    1,
                    OrchestrationPhase.CANARY,
                    25,
                    selectionId,
                    1,
                    ApprovalMigrationOrchestrationEvidence.ZERO_HASH,
                    HASH_A,
                    HASH_B,
                    NOW,
                    "request-1",
                    "trace-1"
                );
                canary = new CanarySelection(
                    selectionId,
                    "tenant-a",
                    planId,
                    intentId,
                    ApprovalMigrationOrchestrationEvidence.CANARY_ALGORITHM_VERSION,
                    1,
                    instanceId,
                    HASH_A,
                    HASH_B,
                    HASH_C,
                    NOW,
                    "request-1",
                    "trace-1"
                );
                initial = event(
                    blocked ? RunEventType.KILL_SWITCH_BLOCKED : RunEventType.PREPARED,
                    blocked ? PauseReason.KILL_SWITCH_ACTIVE : PauseReason.NONE,
                    null
                );
            }

            @Override
            public PreparedOrchestration prepare(PrepareRequest request) {
                return new PreparedOrchestration(
                    run,
                    canary,
                    CanaryGate.PENDING,
                    blocked ? PauseReason.KILL_SWITCH_ACTIVE : PauseReason.NONE,
                    initial,
                    !blocked,
                    false,
                    blocked
                );
            }

            @Override
            public DispatchAuthorization authorizeDispatch(DispatchRequest request) {
                KillSwitchObservation observation = new KillSwitchObservation(
                    UUID.randomUUID(),
                    "tenant-a",
                    runId,
                    attemptId,
                    1,
                    1,
                    false,
                    true,
                    "DISPATCH_ALLOWED",
                    HASH_A,
                    HASH_B,
                    NOW,
                    request.requestId(),
                    request.traceId()
                );
                return new DispatchAuthorization(
                    observation,
                    event(RunEventType.DISPATCH_ALLOWED, PauseReason.NONE, attemptId),
                    true,
                    PauseReason.NONE,
                    false
                );
            }

            @Override
            public FinalizedOrchestration finalizeRun(FinalizeRequest request) {
                if (blocked) {
                    return new FinalizedOrchestration(
                        run,
                        initial,
                        null,
                        PauseReason.KILL_SWITCH_ACTIVE,
                        false,
                        true
                    );
                }
                RunEventType type = disposition == AttemptDisposition.EXACTLY_COMPLETED
                    ? RunEventType.CANARY_COMPLETED
                    : RunEventType.PAUSED;
                PauseReason reason = disposition == AttemptDisposition.UNKNOWN
                    ? PauseReason.CANARY_UNKNOWN
                    : PauseReason.NONE;
                return new FinalizedOrchestration(
                    run,
                    event(type, reason, null),
                    null,
                    reason,
                    false,
                    false
                );
            }

            private OrchestrationEvent event(
                RunEventType type,
                PauseReason reason,
                UUID eventAttemptId
            ) {
                return new OrchestrationEvent(
                    UUID.randomUUID(),
                    "tenant-a",
                    runId,
                    1,
                    type,
                    reason,
                    eventAttemptId,
                    HASH_A,
                    HASH_B,
                    NOW,
                    "request-1",
                    "trace-1"
                );
            }
        }
    }
}
