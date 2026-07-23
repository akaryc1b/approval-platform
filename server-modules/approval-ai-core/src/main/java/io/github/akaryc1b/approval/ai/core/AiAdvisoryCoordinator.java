package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Selects one exact server-owned route, applies circuit and budget gates, and invokes at most one
 * provider. Provider failures never trigger post-invocation fallback in the M6-D safe foundation.
 */
public final class AiAdvisoryCoordinator {

    private final AiProviderRegistry registry;
    private final AiAdvisoryRequestFactory requestFactory;
    private final AiAdvisoryService advisoryService;
    private final AiProviderCircuitBreaker circuitBreaker;
    private final AiProviderRoutingMetrics routingMetrics;
    private final AiAdvisoryExecutionEvidenceSink evidenceSink;
    private final Clock clock;
    private final LongSupplier nanoTime;

    public AiAdvisoryCoordinator(
        AiProviderRegistry registry,
        AiAdvisoryRequestFactory requestFactory,
        AiAdvisoryService advisoryService,
        AiProviderCircuitBreaker circuitBreaker,
        AiProviderRoutingMetrics routingMetrics,
        AiAdvisoryExecutionEvidenceSink evidenceSink,
        Clock clock,
        LongSupplier nanoTime
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.requestFactory = Objects.requireNonNull(
            requestFactory,
            "requestFactory must not be null"
        );
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

    public AiCoordinatedAdvisoryOutcome advise(
        AiAdvisoryIntent intent,
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        List<AiSourceField> fields,
        AiDataMinimizationPolicy dataPolicy,
        AiProviderRoutingPolicy routingPolicy
    ) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(dataPolicy, "dataPolicy must not be null");
        Objects.requireNonNull(routingPolicy, "routingPolicy must not be null");

        if (!routingPolicy.enabled()) {
            return selectionFailure(
                intent.capability(),
                context,
                resource,
                AiOutcomeClassification.DISABLED,
                "AI_ROUTING_DISABLED",
                "AI provider routing is disabled by server policy",
                AiProviderRoutingMetrics.RoutingResult.DISABLED,
                AiProviderCircuitBreaker.State.CLOSED,
                0
            );
        }

        List<AiProviderRoute> candidates = routingPolicy.orderedRoutes(intent.capability());
        if (candidates.isEmpty()) {
            return selectionFailure(
                intent.capability(),
                context,
                resource,
                AiOutcomeClassification.UNSUPPORTED,
                "AI_ROUTE_UNSUPPORTED",
                "no server-authorized AI route supports the requested capability",
                AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                AiProviderCircuitBreaker.State.CLOSED,
                0
            );
        }

        int skippedCandidates = 0;
        boolean circuitBlocked = false;
        for (AiProviderRoute route : candidates) {
            AiAdvisoryProvider provider = registry.find(route.versions().provider()).orElse(null);
            if (provider == null || !registry.matches(provider, route)) {
                skippedCandidates++;
                if (!routingPolicy.allowPreInvocationCandidateFallback()) {
                    return selectionFailure(
                        intent.capability(),
                        context,
                        resource,
                        AiOutcomeClassification.UNSUPPORTED,
                        "AI_ROUTE_PROVIDER_UNAVAILABLE",
                        "the selected AI route has no exact registered provider",
                        AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
                        AiProviderCircuitBreaker.State.CLOSED,
                        skippedCandidates
                    );
                }
                continue;
            }

            Instant beforeCall = clock.instant();
            AiProviderCircuitBreaker.Permit permit = circuitBreaker.tryAcquire(
                route.versions().provider(),
                beforeCall
            );
            if (!permit.allowed()) {
                skippedCandidates++;
                circuitBlocked = true;
                if (!routingPolicy.allowPreInvocationCandidateFallback()) {
                    return selectionFailure(
                        intent.capability(),
                        context,
                        resource,
                        AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                        "AI_PROVIDER_CIRCUIT_OPEN",
                        "the selected AI provider circuit is open",
                        AiProviderRoutingMetrics.RoutingResult.CIRCUIT_OPEN,
                        permit.stateBefore(),
                        skippedCandidates
                    );
                }
                continue;
            }

            AiProviderRequest request;
            try {
                request = requestFactory.create(
                    intent,
                    context,
                    resource,
                    fields,
                    route.versions(),
                    dataPolicy,
                    route.budget().timeout()
                );
            } catch (RuntimeException exception) {
                circuitBreaker.release(permit);
                throw exception;
            }

            int inputCharacters = inputCharacters(request.inputFields());
            if (request.inputFields().size() > route.budget().maximumInputFields()
                || inputCharacters > route.budget().maximumInputCharacters()) {
                circuitBreaker.release(permit);
                return budgetBlocked(
                    intent.capability(),
                    context,
                    resource,
                    route,
                    inputCharacters,
                    skippedCandidates,
                    permit.stateBefore()
                );
            }

            AiProviderExecutionPolicy executionPolicy = new AiProviderExecutionPolicy(
                true,
                Set.of(route.versions().provider().providerId()),
                Set.of(route.versions().model().authorizationKey()),
                Set.of(intent.capability()),
                route.budget().timeout(),
                route.budget().minimumConfidence()
            );

            routingMetrics.record(new AiProviderRoutingMetrics.RoutingMetricEvent(
                intent.capability(),
                AiProviderRoutingMetrics.RoutingResult.SELECTED,
                AiOutcomeClassification.SUCCESS,
                permit.stateBefore()
            ));
            long startedNanos = nanoTime.getAsLong();
            AiProviderOutcome outcome = advisoryService.advise(provider, request, executionPolicy);
            long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedNanos);
            long observedLatencyMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            AiProviderCircuitBreaker.State stateAfter = circuitBreaker.record(
                permit,
                outcome.classification(),
                clock.instant()
            );
            AiUsageEvidence usage = AiUsageEvidence.platformObserved(
                inputCharacters,
                observedLatencyMillis
            );
            AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
                route,
                outcome,
                usage,
                skippedCandidates,
                true,
                false,
                permit.stateBefore(),
                stateAfter
            );
            recordEvidence(context, resource, intent.capability(), coordinated);
            return coordinated;
        }

        return selectionFailure(
            intent.capability(),
            context,
            resource,
            circuitBlocked
                ? AiOutcomeClassification.PROVIDER_UNAVAILABLE
                : AiOutcomeClassification.UNSUPPORTED,
            circuitBlocked ? "AI_PROVIDER_CIRCUIT_OPEN" : "AI_ROUTE_UNSUPPORTED",
            circuitBlocked
                ? "all server-authorized AI provider circuits are unavailable"
                : "no exact registered AI provider matches the authorized routes",
            circuitBlocked
                ? AiProviderRoutingMetrics.RoutingResult.CIRCUIT_OPEN
                : AiProviderRoutingMetrics.RoutingResult.UNSUPPORTED,
            circuitBlocked
                ? AiProviderCircuitBreaker.State.OPEN
                : AiProviderCircuitBreaker.State.CLOSED,
            skippedCandidates
        );
    }

    private AiCoordinatedAdvisoryOutcome budgetBlocked(
        AiCapability capability,
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        AiProviderRoute route,
        int inputCharacters,
        int skippedCandidates,
        AiProviderCircuitBreaker.State circuitState
    ) {
        AiProviderOutcome outcome = AiProviderOutcome.failure(
            AiOutcomeClassification.POLICY_BLOCKED,
            "AI_INVOCATION_BUDGET_EXCEEDED",
            "AI advisory input exceeds the selected server-owned route budget",
            false
        );
        routingMetrics.record(new AiProviderRoutingMetrics.RoutingMetricEvent(
            capability,
            AiProviderRoutingMetrics.RoutingResult.BUDGET_BLOCKED,
            AiOutcomeClassification.POLICY_BLOCKED,
            circuitState
        ));
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            outcome,
            AiUsageEvidence.platformObserved(inputCharacters, 0L),
            skippedCandidates,
            false,
            false,
            circuitState,
            circuitState
        );
        recordEvidence(context, resource, capability, coordinated);
        return coordinated;
    }

    private AiCoordinatedAdvisoryOutcome selectionFailure(
        AiCapability capability,
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        AiOutcomeClassification classification,
        String code,
        String message,
        AiProviderRoutingMetrics.RoutingResult routingResult,
        AiProviderCircuitBreaker.State circuitState,
        int skippedCandidates
    ) {
        AiProviderOutcome outcome = AiProviderOutcome.failure(
            classification,
            code,
            message,
            false
        );
        routingMetrics.record(new AiProviderRoutingMetrics.RoutingMetricEvent(
            capability,
            routingResult,
            classification,
            circuitState
        ));
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            null,
            outcome,
            AiUsageEvidence.unavailable(),
            skippedCandidates,
            false,
            false,
            circuitState,
            circuitState
        );
        recordEvidence(context, resource, capability, coordinated);
        return coordinated;
    }

    private void recordEvidence(
        AiServerRequestContext context,
        AiAuthorizedResource resource,
        AiCapability capability,
        AiCoordinatedAdvisoryOutcome coordinated
    ) {
        AiProviderRoute route = coordinated.selectedRoute();
        evidenceSink.record(new AiAdvisoryExecutionEvidence(
            context.requestId(),
            context.traceId(),
            context.tenantId(),
            context.operatorId(),
            resource.resourceType().name(),
            resource.resourceId(),
            resource.authorizationReference(),
            capability,
            route == null ? null : route.routeId(),
            route == null ? null : route.versions(),
            coordinated.outcome().classification(),
            coordinated.usageEvidence(),
            coordinated.circuitStateBefore(),
            coordinated.circuitStateAfter(),
            coordinated.skippedCandidates(),
            coordinated.providerInvocationStarted(),
            coordinated.postInvocationFallbackAttempted()
        ));
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
}
