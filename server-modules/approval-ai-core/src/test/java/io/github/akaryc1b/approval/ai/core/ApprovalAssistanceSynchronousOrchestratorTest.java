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
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCancellation;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceSynchronousOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
    private static final String KILL_SWITCH_HASH = "a".repeat(64);
    private static final PolicyVersion POLICY = new PolicyVersion(
        "approval-assistance",
        "v1",
        "policy-hash-v1"
    );

    @Test
    void invokesExactlyOneDeterministicProviderAndReturnsAnAcceptedP2Result() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            "hidden-field"
        );
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(1, provider.invocations());
            assertEquals(1, outcome.providerAttempts());
            assertFalse(outcome.retryAttempted());
            assertTrue(outcome.coordinated().providerInvocationStarted());
            assertFalse(outcome.coordinated().postInvocationFallbackAttempted());
            assertEquals(AiOutcomeClassification.SUCCESS, outcome.coordinated()
                .outcome().classification());
            assertNotNull(outcome.acceptedResult());
            assertEquals(1, fixture.evidence.size());
            assertEquals(AiOutcomeClassification.SUCCESS, fixture.evidence.get(0)
                .resultClassification());
        }
    }

    @Test
    void mapsOnlyTheExactP1ProviderSafeProjectionIntoTheProviderRequest() {
        AiVersionReferences versions = versions();
        CapturingProvider provider = new CapturingProvider(versions, false);
        Request request = request(versions);
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request,
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertNotNull(outcome.acceptedResult());
            assertNotNull(provider.request);
            assertEquals(request.projection().providerFields(), provider.request.inputFields());
            assertEquals(Set.of("amount"), provider.request.allowedFields());
            assertEquals(request.expectedVersions(), provider.request.versions());
            assertEquals(request.projection().requestContext().tenantId(),
                provider.request.context().tenantId());
            assertEquals(request.projection().authorizedResource().resourceId(),
                provider.request.resource().resourceId());
        }
    }

    @Test
    void disabledControlStartsNoProviderAttempt() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                OrchestrationControl.disabled(
                    Duration.ofSeconds(1),
                    1,
                    KILL_SWITCH_HASH
                )
            );

            assertEquals(0, provider.invocations());
            assertEquals(0, outcome.providerAttempts());
            assertEquals(AiOutcomeClassification.DISABLED, outcome.coordinated()
                .outcome().classification());
        }
    }

    @Test
    void disabledKillSwitchStartsNoProviderAttempt() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                disabledSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.DISABLED, outcome.coordinated()
                .outcome().classification());
            assertEquals("AI_ASSISTANCE_KILL_SWITCH_DISABLED", outcome.coordinated()
                .outcome().failure().code());
        }
    }

    @Test
    void killSwitchGenerationMismatchStartsNoProviderAttempt() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        try (Fixture fixture = fixture(provider, route(versions))) {
            OrchestrationControl stale = OrchestrationControl.deterministicTestOnly(
                Duration.ofSeconds(1),
                2,
                KILL_SWITCH_HASH
            );
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                stale
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.REJECTED, outcome.coordinated()
                .outcome().classification());
        }
    }

    @Test
    void anyFallbackConfigurationFailsClosedBeforeInvocation() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        try (Fixture fixture = fixture(provider, route(versions))) {
            AiProviderRoutingPolicy unsafePolicy = new AiProviderRoutingPolicy(
                true,
                true,
                false,
                List.of(fixture.route)
            );
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                unsafePolicy,
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.POLICY_BLOCKED, outcome.coordinated()
                .outcome().classification());
            assertFalse(outcome.coordinated().postInvocationFallbackAttempted());
        }
    }

    @Test
    void multipleCandidateRoutesFailClosedBeforeInvocation() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        AiProviderRoute first = route("route-1", versions, budget());
        AiProviderRoute second = route("route-2", versions, budget());
        try (Fixture fixture = fixture(provider, first)) {
            AiProviderRoutingPolicy routingPolicy = new AiProviderRoutingPolicy(
                true,
                false,
                false,
                List.of(first, second)
            );
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy,
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.UNSUPPORTED, outcome.coordinated()
                .outcome().classification());
        }
    }

    @Test
    void routeVersionMismatchFailsClosedBeforeInvocation() {
        AiVersionReferences versions = versions();
        AiVersionReferences otherVersions = new AiVersionReferences(
            versions.provider(),
            new ModelVersion("provider-a", "model-a", "v2"),
            versions.promptTemplate(),
            versions.knowledgeSource(),
            versions.policy(),
            versions.outputSchema()
        );
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        AiProviderRoute mismatchedRoute = route(otherVersions);
        try (Fixture fixture = fixture(provider, mismatchedRoute)) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(mismatchedRoute),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.REJECTED, outcome.coordinated()
                .outcome().classification());
        }
    }

    @Test
    void timeoutControlAndInputBudgetsFailClosedBeforeInvocation() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        AiProviderRoute longTimeout = route(
            "route-timeout",
            versions,
            new AiInvocationBudget(Duration.ofSeconds(2), 4_000, 8, 0.5d)
        );
        try (Fixture fixture = fixture(provider, longTimeout)) {
            Outcome timeoutBlocked = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(longTimeout),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );
            assertEquals(AiOutcomeClassification.POLICY_BLOCKED, timeoutBlocked
                .coordinated().outcome().classification());
            assertEquals(0, provider.invocations());
        }

        AiProviderRoute tinyInput = route(
            "route-input",
            versions,
            new AiInvocationBudget(Duration.ofMillis(100), 1, 1, 0.5d)
        );
        try (Fixture fixture = fixture(provider, tinyInput)) {
            Outcome inputBlocked = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(tinyInput),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );
            assertEquals(AiOutcomeClassification.POLICY_BLOCKED, inputBlocked
                .coordinated().outcome().classification());
            assertEquals(0, provider.invocations());
        }
    }

    @Test
    void openCircuitStartsNoProviderAttempt() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(versions, DeterministicMockAiProvider.Mode.SUCCESS);
        try (Fixture fixture = fixture(provider, route(versions))) {
            AiProviderCircuitBreaker.Permit permit = fixture.circuitBreaker.tryAcquire(
                versions.provider(),
                NOW
            );
            fixture.circuitBreaker.record(
                permit,
                AiOutcomeClassification.TIMEOUT,
                NOW
            );

            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(0, provider.invocations());
            assertEquals(AiOutcomeClassification.PROVIDER_UNAVAILABLE, outcome
                .coordinated().outcome().classification());
            assertEquals(AiProviderCircuitBreaker.State.OPEN, outcome.coordinated()
                .circuitStateBefore());
        }
    }

    @Test
    void providerFailureIsNotRetried() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(
            versions,
            DeterministicMockAiProvider.Mode.UNKNOWN
        );
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(1, provider.invocations());
            assertEquals(1, outcome.providerAttempts());
            assertFalse(outcome.retryAttempted());
            assertEquals(AiOutcomeClassification.UNKNOWN, outcome.coordinated()
                .outcome().classification());
            assertNull(outcome.acceptedResult());
        }
    }

    @Test
    void timeoutIsNotRetried() {
        AiVersionReferences versions = versions();
        DeterministicMockAiProvider provider = provider(
            versions,
            DeterministicMockAiProvider.Mode.TIMEOUT
        );
        AiProviderRoute route = route(
            "route-timeout-test",
            versions,
            new AiInvocationBudget(Duration.ofMillis(25), 4_000, 8, 0.5d)
        );
        try (Fixture fixture = fixture(provider, route)) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(1, provider.invocations());
            assertEquals(1, outcome.providerAttempts());
            assertFalse(outcome.retryAttempted());
            assertEquals(AiOutcomeClassification.TIMEOUT, outcome.coordinated()
                .outcome().classification());
        }
    }

    @Test
    void P2ContractFailureConvertsProviderSuccessToInvalidOutput() {
        AiVersionReferences versions = versions();
        CapturingProvider provider = new CapturingProvider(versions, true);
        try (Fixture fixture = fixture(provider, route(versions))) {
            Outcome outcome = fixture.orchestrator.orchestrate(
                request(versions),
                routingPolicy(fixture.route),
                faultDrillSwitch(versions.provider()),
                enabledControl()
            );

            assertEquals(1, provider.invocations);
            assertEquals(AiOutcomeClassification.INVALID_OUTPUT, outcome.coordinated()
                .outcome().classification());
            assertEquals("AI_ASSISTANCE_CONTRACT_INVALID", outcome.coordinated()
                .outcome().failure().code());
            assertNull(outcome.acceptedResult());
            assertEquals(AiOutcomeClassification.INVALID_OUTPUT, fixture.evidence.get(0)
                .resultClassification());
        }
    }

    private static Fixture fixture(AiAdvisoryProvider provider, AiProviderRoute route) {
        return new Fixture(provider, route);
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
                "request-1",
                "trace-1"
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

    private static AiProviderRoute route(AiVersionReferences versions) {
        return route("route-1", versions, budget());
    }

    private static AiProviderRoute route(
        String routeId,
        AiVersionReferences versions,
        AiInvocationBudget budget
    ) {
        return new AiProviderRoute(
            routeId,
            0,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            budget
        );
    }

    private static AiInvocationBudget budget() {
        return new AiInvocationBudget(Duration.ofMillis(100), 4_000, 8, 0.5d);
    }

    private static AiProviderRoutingPolicy routingPolicy(AiProviderRoute route) {
        return new AiProviderRoutingPolicy(true, false, false, List.of(route));
    }

    private static AiProviderKillSwitch faultDrillSwitch(
        ProviderVersion providerVersion
    ) {
        return killSwitch(providerVersion, AiProviderKillSwitch.State.FAULT_DRILL_ONLY);
    }

    private static AiProviderKillSwitch disabledSwitch(
        ProviderVersion providerVersion
    ) {
        return killSwitch(providerVersion, AiProviderKillSwitch.State.DISABLED);
    }

    private static AiProviderKillSwitch killSwitch(
        ProviderVersion providerVersion,
        AiProviderKillSwitch.State state
    ) {
        return new AiProviderKillSwitch(
            "p3-switch",
            providerVersion,
            1,
            state,
            "P3_TEST_ONLY",
            KILL_SWITCH_HASH,
            false,
            false,
            false
        );
    }

    private static OrchestrationControl enabledControl() {
        return OrchestrationControl.deterministicTestOnly(
            Duration.ofSeconds(1),
            1,
            KILL_SWITCH_HASH
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

    private static AiAdvisoryResult result(
        AiProviderRequest request,
        boolean unusedEvidence
    ) {
        AiAdvisoryResult.EvidenceReference used = new AiAdvisoryResult.EvidenceReference(
            "evidence-1",
            "amount",
            "Authorized amount"
        );
        List<AiAdvisoryResult.EvidenceReference> evidence = unusedEvidence
            ? List.of(
                used,
                new AiAdvisoryResult.EvidenceReference(
                    "evidence-2",
                    "amount",
                    "Unused evidence"
                )
            )
            : List.of(used);
        return new AiAdvisoryResult(
            "Bounded summary",
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "Authorized amount is present",
                List.of("evidence-1")
            )),
            List.of(),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "recommendation-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                "Verify the amount evidence",
                List.of("evidence-1")
            )),
            evidence,
            new AiAdvisoryResult.Confidence(
                0.90d,
                AiAdvisoryResult.ConfidenceBand.HIGH
            ),
            List.of("Human review is required"),
            true,
            request.versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private static final class Fixture implements AutoCloseable {

        private final AiProviderRoute route;
        private final AiProviderCircuitBreaker circuitBreaker;
        private final List<AiAdvisoryExecutionEvidence> evidence = new ArrayList<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AiAdvisoryService service;
        private final ApprovalAssistanceSynchronousOrchestrator orchestrator;

        private Fixture(AiAdvisoryProvider provider, AiProviderRoute route) {
            this.route = route;
            this.circuitBreaker = new AiProviderCircuitBreaker(
                new AiProviderCircuitBreaker.Configuration(1, Duration.ofMinutes(1))
            );
            this.service = new AiAdvisoryService(
                executor,
                AiAdvisoryAuditSink.noop(),
                AiAdvisoryMetrics.noop(),
                true
            );
            AtomicLong nanos = new AtomicLong();
            this.orchestrator = new ApprovalAssistanceSynchronousOrchestrator(
                new AiProviderRegistry(List.of(provider)),
                service,
                circuitBreaker,
                AiProviderRoutingMetrics.noop(),
                evidence::add,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> nanos.getAndAdd(1_000_000L)
            );
        }

        @Override
        public void close() {
            service.close();
        }
    }

    private static final class CapturingProvider implements AiAdvisoryProvider {

        private final AiProviderDescriptor descriptor;
        private final boolean unusedEvidence;
        private AiProviderRequest request;
        private int invocations;

        private CapturingProvider(
            AiVersionReferences versions,
            boolean unusedEvidence
        ) {
            this.unusedEvidence = unusedEvidence;
            this.descriptor = new AiProviderDescriptor(
                versions.provider().providerId(),
                AiProviderType.DETERMINISTIC_MOCK,
                versions.provider(),
                Set.of(new AiProviderDescriptor.CapabilityDescriptor(
                    AiCapability.APPROVAL_SUMMARY,
                    true,
                    16_000,
                    50,
                    4,
                    false,
                    false
                )),
                Set.of(versions.model())
            );
        }

        @Override
        public AiProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public AiProviderOutcome advise(
            AiProviderRequest request,
            AiCancellation cancellation
        ) {
            this.request = request;
            invocations++;
            return AiProviderOutcome.success(result(request, unusedEvidence));
        }
    }
}
