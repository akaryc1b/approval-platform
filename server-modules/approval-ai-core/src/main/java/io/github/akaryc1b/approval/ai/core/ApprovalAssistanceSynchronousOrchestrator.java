package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Result;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * P3 synchronous approval-assistance orchestration.
 *
 * <p>The orchestrator consumes only the P1 Provider-safe projection and P2 bounded contract. P3
 * permits one deterministic test Provider attempt for CI and fault-drill verification. Production
 * Provider types remain blocked until the independent P6 gate.</p>
 */
public final class ApprovalAssistanceSynchronousOrchestrator {

    private final AiProviderRegistry registry;
    private final AiAdvisoryService advisoryService;
    private final AiProviderCircuitBreaker circuitBreaker;
    private final AiProviderRoutingMetrics routingMetrics;
    private final AiAdvisoryExecutionEvidenceSink evidenceSink;
    private final Clock clock;
    private final LongSupplier nanoTime;

    public ApprovalAssistanceSynchronousOrchestrator(
        AiProviderRegistry registry,
        AiAdvisoryService advisoryService,
        AiProviderCircuitBreaker circuitBreaker,
        AiProviderRoutingMetrics routingMetrics,
        AiAdvisoryExecutionEvidenceSink evidenceSink,
        Clock clock,
        LongSupplier nanoTime
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.advisoryService = Objects.requireNonNull(
            advisoryService,
            "advisoryService must not be null"
        );
        this.circuitBreaker = Objects.requireNonNull(
            circuitBreaker,
            "circuitBreaker must not be null"
        );
        this.routingMetrics = Objects.requireNonNull(
            routingMetrics,
            "routingMetrics must not be null"
        );
        this.evidenceSink = Objects.requireNonNull(
            evidenceSink,
            "evidenceSink must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    public Outcome orchestrate(
        Request request,
        AiProviderRoutingPolicy routingPolicy,
        AiProviderKillSwitch killSwitch,
        OrchestrationControl control
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");
        Objects.requireNonNull(killSwitch, "killSwitch must not be null");
        Objects.requireNonNull(control, "control must not be null");

        AiCapability capability = request.useCase().capability();
        ApprovalAssistanceContextProjection projection = request.projection();

        if (!control.enabled()) {
            return blocked(
                request,
                null,
                control,
                killSwitch,
                AiOutcomeClassification.DISABLED,
                "AI_ASSISTANCE_ORCHESTRATION_DISABLED",
                "approval-assistance orchestration is disabled by server policy",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        if (!routingPolicy.enabled()) {
            return blocked(
                request,
                null,
                control,
                killSwitch,
                AiOutcomeClassification.DISABLED,
                "AI_ASSISTANCE_ROUTING_DISABLED",
                "approval-assistance routing is disabled by server policy",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        if (routingPolicy.allowPreInvocationCandidateFallback()
            || routingPolicy.allowPostInvocationFallback()) {
            return blocked(
                request,
                null,
                control,
                killSwitch,
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_ASSISTANCE_FALLBACK_PROHIBITED",
                "P3 approval assistance permits no Provider fallback",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }

        List<AiProviderRoute> candidates = routingPolicy.orderedRoutes(capability);
        if (candidates.size() != 1) {
            return blocked(
                request,
                null,
                control,
                killSwitch,
                AiOutcomeClassification.UNSUPPORTED,
                "AI_ASSISTANCE_ROUTE_NOT_EXACT",
                "P3 approval assistance requires exactly one authorized route",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }

        AiProviderRoute route = candidates.get(0);
        if (!route.capabilities().equals(Set.of(capability))
            || !route.versions().equals(request.expectedVersions())) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.REJECTED,
                "AI_ASSISTANCE_ROUTE_VERSION_MISMATCH",
                "route capability and exact versions must match the P2 request",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        if (!killSwitch.providerVersion().equals(route.versions().provider())
            || killSwitch.generation() != control.expectedKillSwitchGeneration()
            || !killSwitch.evidenceHash().equals(control.expectedKillSwitchEvidenceHash())) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.REJECTED,
                "AI_ASSISTANCE_KILL_SWITCH_MISMATCH",
                "kill-switch evidence does not match the exact selected route",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        if (!killSwitch.permitsReviewOnly()) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.DISABLED,
                "AI_ASSISTANCE_KILL_SWITCH_DISABLED",
                "kill switch blocks the P3 deterministic fault-drill invocation",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        if (route.budget().timeout().compareTo(control.maximumTimeout()) > 0) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_ASSISTANCE_TIMEOUT_BUDGET_EXCEEDED",
                "selected route timeout exceeds the P3 control maximum",
                AiProviderRoutingMetrics.RoutingResult.BUDGET_BLOCKED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }

        AiAdvisoryProvider provider = registry.find(route.versions().provider()).orElse(null);
        if (provider == null || !registry.matches(provider, route)) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.UNSUPPORTED,
                "AI_ASSISTANCE_PROVIDER_UNAVAILABLE",
                "the exact selected route has no matching registered Provider",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }

        AiProviderDescriptor descriptor = Objects.requireNonNull(
            provider.descriptor(),
            "provider descriptor must not be null"
        );
        if (control.mode() != InvocationMode.DETERMINISTIC_TEST_ONLY
            || descriptor.providerType() != AiProviderType.DETERMINISTIC_MOCK) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_ASSISTANCE_PRODUCTION_PROVIDER_BLOCKED",
                "P3 permits deterministic test Providers only; production waits for P6",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }
        AiProviderDescriptor.CapabilityDescriptor capabilityDescriptor = descriptor
            .capabilityDescriptor(capability)
            .filter(AiProviderDescriptor.CapabilityDescriptor::enabled)
            .orElse(null);
        if (capabilityDescriptor == null
            || projection.providerRequirements().maximumCollectionSize()
                > capabilityDescriptor.maximumCollectionSize()
            || projection.providerRequirements().maximumDepth()
                > capabilityDescriptor.maximumDepth()) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.UNSUPPORTED,
                "AI_ASSISTANCE_PROVIDER_LIMIT_MISMATCH",
                "Provider capability limits do not cover the P1 projection limits",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.unavailable()
            );
        }

        AiProviderRequest providerRequest = providerRequest(request, route);
        int inputCharacters = inputCharacters(providerRequest.inputFields());
        if (providerRequest.inputFields().size() > route.budget().maximumInputFields()
            || inputCharacters > route.budget().maximumInputCharacters()) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_ASSISTANCE_INVOCATION_BUDGET_EXCEEDED",
                "Provider-safe projection exceeds the selected route budget",
                AiProviderRoutingMetrics.RoutingResult.BUDGET_BLOCKED,
                AiProviderCircuitBreaker.State.CLOSED,
                AiUsageEvidence.platformObserved(inputCharacters, 0L)
            );
        }

        Instant beforeCall = clock.instant();
        AiProviderCircuitBreaker.Permit permit = circuitBreaker.tryAcquire(
            route.versions().provider(),
            beforeCall
        );
        if (!permit.allowed()) {
            return blocked(
                request,
                route,
                control,
                killSwitch,
                AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                "AI_ASSISTANCE_PROVIDER_CIRCUIT_OPEN",
                "selected Provider circuit is unavailable",
                AiProviderRoutingMetrics.RoutingResult.CIRCUIT_OPEN,
                permit.stateBefore(),
                AiUsageEvidence.unavailable()
            );
        }

        AiProviderExecutionPolicy executionPolicy = new AiProviderExecutionPolicy(
            true,
            Set.of(route.versions().provider().providerId()),
            Set.of(route.versions().model().authorizationKey()),
            Set.of(capability),
            route.budget().timeout(),
            route.budget().minimumConfidence()
        );

        routingMetrics.record(new AiProviderRoutingMetrics.RoutingMetricEvent(
            capability,
            AiProviderRoutingMetrics.RoutingResult.SELECTED,
            AiOutcomeClassification.SUCCESS,
            permit.stateBefore()
        ));

        long startedNanos = nanoTime.getAsLong();
        AiProviderOutcome providerOutcome;
        try {
            providerOutcome = advisoryService.advise(
                provider,
                providerRequest,
                executionPolicy
            );
        } catch (RuntimeException exception) {
            providerOutcome = AiProviderOutcome.failure(
                AiOutcomeClassification.UNKNOWN,
                "AI_ASSISTANCE_SERVICE_BOUNDARY_EXCEPTION",
                "approval-assistance service boundary failed without trusted output",
                false
            );
        }
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedNanos);
        long observedLatencyMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        Result acceptedResult = null;
        AiProviderOutcome finalOutcome = providerOutcome;
        if (providerOutcome.hasAdvisoryResult()) {
            try {
                acceptedResult = new Result(request, providerOutcome.result());
            } catch (RuntimeException exception) {
                finalOutcome = AiProviderOutcome.failure(
                    AiOutcomeClassification.INVALID_OUTPUT,
                    "AI_ASSISTANCE_CONTRACT_INVALID",
                    "Provider output failed the bounded P2 approval-assistance contract",
                    false
                );
            }
        }

        AiProviderCircuitBreaker.State stateAfter = circuitBreaker.record(
            permit,
            finalOutcome.classification(),
            clock.instant()
        );
        AiUsageEvidence usage = AiUsageEvidence.platformObserved(
            inputCharacters,
            observedLatencyMillis
        );
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            finalOutcome,
            usage,
            0,
            true,
            false,
            permit.stateBefore(),
            stateAfter
        );
        recordEvidence(request, coordinated);
        return new Outcome(
            request,
            coordinated,
            acceptedResult,
            control.mode(),
            1,
            false,
            killSwitch.generation()
        );
    }

    private Outcome blocked(
        Request request,
        AiProviderRoute route,
        OrchestrationControl control,
        AiProviderKillSwitch killSwitch,
        AiOutcomeClassification classification,
        String code,
        String message,
        AiProviderRoutingMetrics.RoutingResult routingResult,
        AiProviderCircuitBreaker.State circuitState,
        AiUsageEvidence usageEvidence
    ) {
        AiProviderOutcome providerOutcome = AiProviderOutcome.failure(
            classification,
            code,
            message,
            false
        );
        routingMetrics.record(new AiProviderRoutingMetrics.RoutingMetricEvent(
            request.useCase().capability(),
            routingResult,
            classification,
            circuitState
        ));
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            providerOutcome,
            usageEvidence,
            0,
            false,
            false,
            circuitState,
            circuitState
        );
        recordEvidence(request, coordinated);
        return new Outcome(
            request,
            coordinated,
            null,
            control.mode(),
            0,
            false,
            killSwitch.generation()
        );
    }

    private void recordEvidence(
        Request request,
        AiCoordinatedAdvisoryOutcome coordinated
    ) {
        ApprovalAssistanceContextProjection projection = request.projection();
        evidenceSink.record(AiAdvisoryExecutionEvidence.create(
            projection.requestContext(),
            projection.authorizedResource(),
            request.useCase().capability(),
            coordinated
        ));
    }

    private static AiProviderRequest providerRequest(
        Request request,
        AiProviderRoute route
    ) {
        ApprovalAssistanceContextProjection projection = request.projection();
        Set<String> includedFieldKeys = projection.providerFields().stream()
            .map(AiProviderRequest.InputField::key)
            .collect(Collectors.toUnmodifiableSet());
        AiServerRequestContext context = projection.requestContext();
        AiAuthorizedResource resource = projection.authorizedResource();
        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                context.tenantId(),
                context.operatorId(),
                context.requestId(),
                context.traceId()
            ),
            new AiProviderRequest.AuthorizedResource(
                resource.tenantId(),
                resource.resourceType().name(),
                resource.resourceId(),
                resource.authorizationReference()
            ),
            request.useCase().capability(),
            includedFieldKeys,
            projection.providerFields(),
            request.expectedVersions(),
            route.budget().timeout()
        );
    }

    private static int inputCharacters(Collection<AiProviderRequest.InputField> fields) {
        long total = 0L;
        for (AiProviderRequest.InputField field : fields) {
            total += field.key().length();
            total += field.type().length();
            total += valueCharacters(field.value());
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private static long valueCharacters(Object value) {
        if (value instanceof String text) {
            return text.length();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value).length();
        }
        if (value instanceof Map<?, ?> map) {
            long total = 0L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += String.valueOf(entry.getKey()).length();
                total += valueCharacters(entry.getValue());
            }
            return total;
        }
        if (value instanceof Collection<?> collection) {
            long total = 0L;
            for (Object item : collection) {
                total += valueCharacters(item);
            }
            return total;
        }
        return String.valueOf(value).length();
    }

    public enum InvocationMode {
        DISABLED,
        DETERMINISTIC_TEST_ONLY
    }

    public record OrchestrationControl(
        boolean enabled,
        InvocationMode mode,
        int maximumProviderAttempts,
        Duration maximumTimeout,
        long expectedKillSwitchGeneration,
        String expectedKillSwitchEvidenceHash,
        boolean preInvocationFallbackAuthorized,
        boolean postInvocationFallbackAuthorized
    ) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public OrchestrationControl {
            mode = Objects.requireNonNull(mode, "mode must not be null");
            maximumTimeout = Objects.requireNonNull(
                maximumTimeout,
                "maximumTimeout must not be null"
            );
            if (maximumTimeout.isZero() || maximumTimeout.isNegative()) {
                throw new IllegalArgumentException("maximumTimeout must be positive");
            }
            if (expectedKillSwitchGeneration < 1) {
                throw new IllegalArgumentException(
                    "expectedKillSwitchGeneration must be positive"
                );
            }
            expectedKillSwitchEvidenceHash = requireSha256(
                expectedKillSwitchEvidenceHash,
                "expectedKillSwitchEvidenceHash"
            );
            if (preInvocationFallbackAuthorized || postInvocationFallbackAuthorized) {
                throw new IllegalArgumentException(
                    "P3 approval assistance permits no Provider fallback"
                );
            }
            if (enabled) {
                if (mode != InvocationMode.DETERMINISTIC_TEST_ONLY
                    || maximumProviderAttempts != 1) {
                    throw new IllegalArgumentException(
                        "enabled P3 orchestration permits one deterministic test attempt only"
                    );
                }
            } else if (mode != InvocationMode.DISABLED || maximumProviderAttempts != 0) {
                throw new IllegalArgumentException(
                    "disabled P3 orchestration must permit zero Provider attempts"
                );
            }
        }

        public static OrchestrationControl disabled(
            Duration maximumTimeout,
            long killSwitchGeneration,
            String killSwitchEvidenceHash
        ) {
            return new OrchestrationControl(
                false,
                InvocationMode.DISABLED,
                0,
                maximumTimeout,
                killSwitchGeneration,
                killSwitchEvidenceHash,
                false,
                false
            );
        }

        public static OrchestrationControl deterministicTestOnly(
            Duration maximumTimeout,
            long killSwitchGeneration,
            String killSwitchEvidenceHash
        ) {
            return new OrchestrationControl(
                true,
                InvocationMode.DETERMINISTIC_TEST_ONLY,
                1,
                maximumTimeout,
                killSwitchGeneration,
                killSwitchEvidenceHash,
                false,
                false
            );
        }

        private static String requireSha256(String value, String name) {
            String normalized = Objects.requireNonNull(value, name + " must not be null")
                .trim()
                .toLowerCase();
            if (!SHA256.matcher(normalized).matches()) {
                throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
            }
            return normalized;
        }
    }

    public record Outcome(
        Request request,
        AiCoordinatedAdvisoryOutcome coordinated,
        Result acceptedResult,
        InvocationMode mode,
        int providerAttempts,
        boolean retryAttempted,
        long killSwitchGeneration
    ) {
        public Outcome {
            request = Objects.requireNonNull(request, "request must not be null");
            coordinated = Objects.requireNonNull(coordinated, "coordinated must not be null");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            if (providerAttempts < 0 || providerAttempts > 1) {
                throw new IllegalArgumentException("providerAttempts must be zero or one");
            }
            int expectedAttempts = coordinated.providerInvocationStarted() ? 1 : 0;
            if (providerAttempts != expectedAttempts) {
                throw new IllegalArgumentException(
                    "providerAttempts must match invocation-started evidence"
                );
            }
            if (retryAttempted) {
                throw new IllegalArgumentException("P3 Provider retry is prohibited");
            }
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            if (coordinated.outcome().hasAdvisoryResult() != (acceptedResult != null)) {
                throw new IllegalArgumentException(
                    "accepted result must match the final coordinated outcome"
                );
            }
            if (acceptedResult != null && !acceptedResult.request().equals(request)) {
                throw new IllegalArgumentException(
                    "accepted result must bind the exact P2 request"
                );
            }
            if (coordinated.selectedRoute() != null
                && !coordinated.selectedRoute().versions().equals(request.expectedVersions())) {
                throw new IllegalArgumentException(
                    "selected route must bind the exact P2 versions"
                );
            }
            if (coordinated.providerInvocationStarted()
                && mode != InvocationMode.DETERMINISTIC_TEST_ONLY) {
                throw new IllegalArgumentException(
                    "P3 invocation is limited to deterministic test mode"
                );
            }
        }
    }
}
