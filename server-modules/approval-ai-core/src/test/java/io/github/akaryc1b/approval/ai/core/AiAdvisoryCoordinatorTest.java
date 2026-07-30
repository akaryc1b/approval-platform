package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryCoordinatorTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void skipsCircuitOpenCandidateBeforeInvocationAndCallsExactlyOneProvider() {
        AiVersionReferences firstVersions = versions("provider-first", "model-first");
        AiVersionReferences secondVersions = versions("provider-second", "model-second");
        DeterministicMockAiProvider first = provider(firstVersions, DeterministicMockAiProvider.Mode.SUCCESS);
        DeterministicMockAiProvider second = provider(secondVersions, DeterministicMockAiProvider.Mode.SUCCESS);
        AiProviderCircuitBreaker breaker = breaker(1);
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        AiProviderCircuitBreaker.Permit failedPermit = breaker.tryAcquire(
            firstVersions.provider(),
            now
        );
        breaker.record(failedPermit, AiOutcomeClassification.TIMEOUT, now);

        List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        AiAdvisoryCoordinator coordinator = coordinator(
            List.of(first, second),
            breaker,
            Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
            evidence
        );
        AiProviderRoutingPolicy routing = routing(
            true,
            route("first", 1, firstVersions, Duration.ofMillis(200), 16_000),
            route("second", 2, secondVersions, Duration.ofMillis(200), 16_000)
        );

        AiCoordinatedAdvisoryOutcome outcome = coordinator.advise(
            intent(),
            context(),
            resource(),
            sourceFields("bounded input"),
            dataPolicy(firstVersions.policy()),
            routing
        );

        assertEquals("second", outcome.selectedRoute().routeId());
        assertEquals(AiOutcomeClassification.SUCCESS, outcome.outcome().classification());
        assertEquals(1, outcome.skippedCandidates());
        assertEquals(0, first.invocations());
        assertEquals(1, second.invocations());
        assertTrue(outcome.providerInvocationStarted());
        assertFalse(outcome.postInvocationFallbackAttempted());
        assertEquals(AiUsageEvidence.Source.PLATFORM_OBSERVED, outcome.usageEvidence().source());
        assertEquals(1, evidence.size());
        assertEquals(secondVersions, evidence.get(0).versions());
    }

    @Test
    void timeoutNeverFallsBackAfterInvocationStarted() {
        AiVersionReferences firstVersions = versions("provider-timeout", "model-timeout");
        AiVersionReferences secondVersions = versions("provider-backup", "model-backup");
        DeterministicMockAiProvider first = provider(firstVersions, DeterministicMockAiProvider.Mode.TIMEOUT);
        DeterministicMockAiProvider second = provider(secondVersions, DeterministicMockAiProvider.Mode.SUCCESS);
        AiProviderCircuitBreaker breaker = breaker(1);
        AiAdvisoryCoordinator coordinator = coordinator(
            List.of(first, second),
            breaker,
            Clock.systemUTC(),
            new ArrayList<>()
        );
        AiProviderRoutingPolicy routing = routing(
            true,
            route("timeout", 1, firstVersions, Duration.ofMillis(25), 16_000),
            route("backup", 2, secondVersions, Duration.ofMillis(200), 16_000)
        );

        AiCoordinatedAdvisoryOutcome outcome = coordinator.advise(
            intent(),
            context(),
            resource(),
            sourceFields("bounded input"),
            dataPolicy(firstVersions.policy()),
            routing
        );

        assertEquals(AiOutcomeClassification.TIMEOUT, outcome.outcome().classification());
        assertEquals("timeout", outcome.selectedRoute().routeId());
        assertEquals(1, first.invocations());
        assertEquals(0, second.invocations());
        assertFalse(outcome.postInvocationFallbackAttempted());
        assertEquals(
            AiProviderCircuitBreaker.State.OPEN,
            breaker.state(firstVersions.provider())
        );
    }

    @Test
    void routeBudgetBlocksBeforeProviderInvocation() {
        AiVersionReferences versions = versions("provider-budget", "model-budget");
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        AiAdvisoryCoordinator coordinator = coordinator(
            List.of(provider),
            breaker(2),
            Clock.systemUTC(),
            evidence
        );
        AiProviderRoutingPolicy routing = routing(
            false,
            route("budget", 1, versions, Duration.ofMillis(200), 8)
        );

        AiCoordinatedAdvisoryOutcome outcome = coordinator.advise(
            intent(),
            context(),
            resource(),
            sourceFields("input longer than the route budget"),
            dataPolicy(versions.policy()),
            routing
        );

        assertEquals(AiOutcomeClassification.POLICY_BLOCKED, outcome.outcome().classification());
        assertFalse(outcome.providerInvocationStarted());
        assertEquals(0, provider.invocations());
        assertTrue(outcome.usageEvidence().inputCharacters() > 8);
        assertEquals(1, evidence.size());
        assertFalse(evidence.get(0).providerInvocationStarted());
    }

    @Test
    void disabledRoutingProducesEvidenceWithoutSelectingOrCallingProvider() {
        AiVersionReferences versions = versions("provider-disabled", "model-disabled");
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        AiAdvisoryCoordinator coordinator = coordinator(
            List.of(provider),
            breaker(2),
            Clock.systemUTC(),
            evidence
        );

        AiCoordinatedAdvisoryOutcome outcome = coordinator.advise(
            intent(),
            context(),
            resource(),
            sourceFields("bounded input"),
            dataPolicy(versions.policy()),
            AiProviderRoutingPolicy.disabled()
        );

        assertEquals(AiOutcomeClassification.DISABLED, outcome.outcome().classification());
        assertNull(outcome.selectedRoute());
        assertEquals(0, provider.invocations());
        assertEquals(AiUsageEvidence.Source.UNAVAILABLE, outcome.usageEvidence().source());
        assertEquals(1, evidence.size());
        assertNull(evidence.get(0).versions());
    }

    @Test
    void policyCannotEnablePostInvocationFallback() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AiProviderRoutingPolicy(true, true, true, List.of(
                route(
                    "prohibited",
                    1,
                    versions("provider", "model"),
                    Duration.ofMillis(100),
                    100
                )
            ))
        );
    }

    private AiAdvisoryCoordinator coordinator(
        List<DeterministicMockAiProvider> providers,
        AiProviderCircuitBreaker breaker,
        Clock clock,
        List<AiAdvisoryExecutionEvidence> evidence
    ) {
        AtomicLong nanos = new AtomicLong();
        return new AiAdvisoryCoordinator(
            new AiProviderRegistry(providers),
            new AiAdvisoryRequestFactory(new AiDataMinimizer()),
            new AiAdvisoryService(executor, record -> { }, AiAdvisoryMetrics.noop()),
            breaker,
            AiProviderRoutingMetrics.noop(),
            evidence::add,
            clock,
            () -> nanos.getAndAdd(2_000_000L)
        );
    }

    private static AiProviderCircuitBreaker breaker(int threshold) {
        return new AiProviderCircuitBreaker(new AiProviderCircuitBreaker.Configuration(
            threshold,
            Duration.ofSeconds(30)
        ));
    }

    private static DeterministicMockAiProvider provider(
        AiVersionReferences versions,
        DeterministicMockAiProvider.Mode mode
    ) {
        return new DeterministicMockAiProvider(
            mode,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
    }

    private static AiProviderRoutingPolicy routing(
        boolean allowPreInvocationFallback,
        AiProviderRoute... routes
    ) {
        return new AiProviderRoutingPolicy(
            true,
            allowPreInvocationFallback,
            false,
            List.of(routes)
        );
    }

    private static AiProviderRoute route(
        String routeId,
        int priority,
        AiVersionReferences versions,
        Duration timeout,
        int maximumInputCharacters
    ) {
        return new AiProviderRoute(
            routeId,
            priority,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(timeout, maximumInputCharacters, 8, 0.60d)
        );
    }

    private static AiAdvisoryIntent intent() {
        return new AiAdvisoryIntent(AiCapability.APPROVAL_SUMMARY, "task-a");
    }

    private static AiServerRequestContext context() {
        return new AiServerRequestContext(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a"
        );
    }

    private static AiAuthorizedResource resource() {
        return new AiAuthorizedResource(
            "tenant-a",
            AiAuthorizedResource.ResourceType.APPROVAL_TASK,
            "task-a",
            "authorization-a",
            Set.of("description")
        );
    }

    private static List<AiSourceField> sourceFields(String value) {
        return List.of(new AiSourceField(
            "description",
            FormDefinition.FieldType.TEXT,
            UiSchemaDefinition.FieldAccess.EDITABLE,
            true,
            false,
            value
        ));
    }

    private static AiDataMinimizationPolicy dataPolicy(
        AiVersionReferences.PolicyVersion version
    ) {
        return new AiDataMinimizationPolicy(
            version,
            Map.of(),
            new AiDataMinimizationPolicy.InputLimits(8, 1_000, 2_000, 20, 3),
            true
        );
    }

    private static AiVersionReferences versions(String providerId, String modelId) {
        return new AiVersionReferences(
            new AiVersionReferences.ProviderVersion(providerId, "1.0.0"),
            new AiVersionReferences.ModelVersion(providerId, modelId, "2026-07-23"),
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
