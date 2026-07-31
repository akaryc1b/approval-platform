package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiAdvisoryCoordinatorProviderNestingLimitTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void invocationSelectionRejectsPolicyBeyondProviderNestingContract() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        AtomicLong nanos = new AtomicLong();
        AiAdvisoryCoordinator coordinator = new AiAdvisoryCoordinator(
            new AiProviderRegistry(List.of(provider)),
            new AiAdvisoryRequestFactory(new AiDataMinimizer()),
            new AiAdvisoryService(executor, record -> { }, AiAdvisoryMetrics.noop()),
            new AiProviderCircuitBreaker(new AiProviderCircuitBreaker.Configuration(
                2,
                Duration.ofSeconds(30)
            )),
            AiProviderRoutingMetrics.noop(),
            evidence::add,
            Clock.systemUTC(),
            () -> nanos.getAndAdd(2_000_000L)
        );
        AiProviderRoute route = new AiProviderRoute(
            "route-a",
            1,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(200), 16_000, 8, 0.60d)
        );
        AiProviderRoutingPolicy routing = new AiProviderRoutingPolicy(
            true,
            false,
            false,
            List.of(route)
        );
        AiDataMinimizationPolicy dataPolicy = new AiDataMinimizationPolicy(
            versions.policy(),
            Map.of(),
            new AiDataMinimizationPolicy.InputLimits(8, 1_000, 2_000, 51, 4),
            true
        );

        AiCoordinatedAdvisoryOutcome outcome = coordinator.advise(
            new AiAdvisoryIntent(AiCapability.APPROVAL_SUMMARY, "task-a"),
            new AiServerRequestContext(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a"
            ),
            new AiAuthorizedResource(
                "tenant-a",
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-a",
                "authorization-a",
                Set.of("description")
            ),
            List.of(new AiSourceField(
                "description",
                FormDefinition.FieldType.TEXT,
                UiSchemaDefinition.FieldAccess.EDITABLE,
                true,
                false,
                "bounded input"
            )),
            dataPolicy,
            routing
        );

        assertEquals(AiOutcomeClassification.UNSUPPORTED, outcome.outcome().classification());
        assertNull(outcome.selectedRoute());
        assertFalse(outcome.providerInvocationStarted());
        assertEquals(0, provider.invocations());
        assertEquals(1, evidence.size());
        assertFalse(evidence.get(0).providerInvocationStarted());
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion("provider-a", "1.0.0"),
            new AiVersionReferences.ModelVersion(
                "provider-a",
                "model-a",
                "2026-07-23"
            ),
            new AiVersionReferences.PromptTemplateVersion(
                "test-template",
                "2",
                "test-template-hash-2"
            ),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "m6-d-second-slice",
                "2",
                "policy-hash-2"
            ),
            new AiVersionReferences.OutputSchemaVersion("approval.ai.advisory", 1)
        );
    }
}
