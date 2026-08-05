package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ProjectionProvenance;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.ResultLimits;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.OrchestrationControl;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.Outcome;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceSynchronousOrchestratorServiceBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
    private static final String KILL_SWITCH_HASH = "b".repeat(64);
    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    @Test
    void auditBoundaryExceptionBecomesOneUnknownAttemptWithoutRetry() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AiAdvisoryService service = new AiAdvisoryService(
            executor,
            ignored -> {
                throw new IllegalStateException("deterministic audit sink failure");
            },
            AiAdvisoryMetrics.noop(),
            true
        )) {
            AtomicLong nanos = new AtomicLong();
            AiProviderCircuitBreaker circuitBreaker = new AiProviderCircuitBreaker(
                new AiProviderCircuitBreaker.Configuration(1, Duration.ofMinutes(1))
            );
            ApprovalAssistanceSynchronousOrchestrator orchestrator =
                new ApprovalAssistanceSynchronousOrchestrator(
                    new AiProviderRegistry(List.of(provider)),
                    service,
                    circuitBreaker,
                    AiProviderRoutingMetrics.noop(),
                    evidence::add,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> nanos.getAndAdd(1_000_000L)
                );

            Outcome outcome = assertDoesNotThrow(() -> orchestrator.orchestrate(
                request(versions),
                routingPolicy(versions),
                killSwitch(versions.provider()),
                OrchestrationControl.deterministicTestOnly(
                    Duration.ofSeconds(1),
                    1,
                    KILL_SWITCH_HASH
                )
            ));

            assertEquals(1, provider.invocations());
            assertEquals(1, outcome.providerAttempts());
            assertTrue(outcome.coordinated().providerInvocationStarted());
            assertFalse(outcome.retryAttempted());
            assertFalse(outcome.coordinated().postInvocationFallbackAttempted());
            assertEquals(AiOutcomeClassification.UNKNOWN, outcome.coordinated()
                .outcome().classification());
            assertEquals("AI_ASSISTANCE_SERVICE_BOUNDARY_EXCEPTION", outcome.coordinated()
                .outcome().failure().code());
            assertNull(outcome.acceptedResult());
            assertEquals(AiProviderCircuitBreaker.State.OPEN, outcome.coordinated()
                .circuitStateAfter());
            assertEquals(1, evidence.size());
            assertEquals(AiOutcomeClassification.UNKNOWN, evidence.get(0)
                .resultClassification());
            assertTrue(evidence.get(0).providerInvocationStarted());
            assertFalse(evidence.get(0).postInvocationFallbackAttempted());
        }
    }

    private static Request request(AiVersionReferences versions) {
        ApprovalAssistanceContextProjection projection = projection(versions);
        return new Request(
            projection,
            UseCase.SUMMARY,
            versions,
            ResultLimits.conservativeDefaults(),
            ProjectionProvenance.from(projection),
            NOW
        );
    }

    private static ApprovalAssistanceContextProjection projection(
        AiVersionReferences versions
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(
                "tenant-a",
                "operator-a",
                "request-service-boundary",
                "trace-service-boundary"
            ),
            new AiAuthorizedResource(
                "tenant-a",
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-1",
                "authz-ref",
                Set.of("amount")
            ),
            new ProcessSnapshot(
                "purchase-payment",
                2,
                "compiler-1.1.0",
                "definition-hash-v2",
                "purchase-form",
                3,
                5,
                "release-package-hash-v5"
            ),
            new ResourceStateSnapshot(
                "tenant-a",
                "instance-1",
                "task-1",
                "managerApproval",
                ResourceState.TASK_PENDING,
                7,
                NOW.minusSeconds(1)
            ),
            new FormSnapshot(
                "purchase-form",
                3,
                "1.0",
                "form-hash-v3",
                1,
                2,
                "ui-hash-v2",
                "managerApproval",
                4
            ),
            List.of(new AiProviderRequest.InputField(
                "amount",
                "NUMBER",
                "1000.00",
                AiProviderRequest.MaskingDisposition.INCLUDED
            )),
            new ProviderRequirements(
                Set.of(AiCapability.APPROVAL_SUMMARY),
                8,
                1_000,
                4_000,
                8,
                3,
                true,
                true
            ),
            versions.policy(),
            new ProjectionEvidence(1, 1, 0, 0, 0, false)
        );
    }

    private static AiProviderRoutingPolicy routingPolicy(
        AiVersionReferences versions
    ) {
        return new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(new AiProviderRoute(
                "route-service-boundary",
                0,
                true,
                Set.of(AiCapability.APPROVAL_SUMMARY),
                versions,
                new AiInvocationBudget(
                    Duration.ofMillis(100),
                    4_000,
                    8,
                    0.5d
                )
            ))
        );
    }

    private static AiProviderKillSwitch killSwitch(
        ProviderVersion providerVersion
    ) {
        return new AiProviderKillSwitch(
            "p3-service-boundary-switch",
            providerVersion,
            1,
            AiProviderKillSwitch.State.FAULT_DRILL_ONLY,
            "P3_SERVICE_BOUNDARY_TEST",
            KILL_SWITCH_HASH,
            false,
            false,
            false
        );
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new ProviderVersion("provider-a", "v1"),
            new ModelVersion("provider-a", "model-a", "v1"),
            new PromptTemplateVersion(
                "approval-summary",
                "v1",
                "prompt-hash-v1"
            ),
            KnowledgeSourceVersion.none(),
            POLICY,
            new OutputSchemaVersion("approval-assistance", 1)
        );
    }
}
