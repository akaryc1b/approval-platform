package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Result;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Separate P6-E production orchestration boundary.
 *
 * <p>The accepted P3 deterministic orchestrator remains unchanged. This production path requires
 * an exact remote descriptor, exact server-owned route and affirmative pre-invocation controls,
 * then starts at most one Provider invocation with no retry or fallback.</p>
 */
public final class ApprovalAssistanceProductionOrchestrator {

    private final AiAdvisoryService advisoryService;
    private final AiAdvisoryProvider provider;
    private final AiProviderRoute route;
    private final AiProviderExecutionPolicy policy;
    private final ProductionControl control;

    public ApprovalAssistanceProductionOrchestrator(
        AiAdvisoryService advisoryService,
        AiAdvisoryProvider provider,
        AiProviderRoute route,
        AiProviderExecutionPolicy policy,
        ProductionControl control
    ) {
        this.advisoryService = Objects.requireNonNull(
            advisoryService,
            "advisoryService must not be null"
        );
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.route = Objects.requireNonNull(route, "route must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.control = Objects.requireNonNull(control, "control must not be null");
        validateStaticBinding();
    }

    public Outcome execute(Request request) {
        Objects.requireNonNull(request, "request must not be null");
        requirePreInvocationControl(request);
        AiProviderRequest providerRequest = providerRequest(request);
        long started = System.nanoTime();
        AiProviderOutcome providerOutcome = advisoryService.advise(
            provider,
            providerRequest,
            policy
        );
        long elapsedMillis = Math.max(
            0L,
            Duration.ofNanos(System.nanoTime() - started).toMillis()
        );
        Result accepted = null;
        if (providerOutcome.hasAdvisoryResult()) {
            try {
                accepted = new Result(request, providerOutcome.result());
            } catch (RuntimeException invalid) {
                providerOutcome = AiProviderOutcome.failure(
                    AiOutcomeClassification.INVALID_OUTPUT,
                    "AI_PRODUCTION_P2_REVALIDATION_FAILED",
                    "AI advisory generation did not produce trusted output",
                    false
                );
            }
        }
        AiCoordinatedAdvisoryOutcome coordinated = new AiCoordinatedAdvisoryOutcome(
            route,
            providerOutcome,
            AiUsageEvidence.platformObserved(
                inputCharacters(providerRequest.inputFields()),
                elapsedMillis
            ),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.CLOSED
        );
        AiAdvisoryExecutionEvidence executionEvidence = AiAdvisoryExecutionEvidence.create(
            request.projection().requestContext(),
            request.projection().authorizedResource(),
            request.useCase().capability(),
            coordinated
        );
        return new Outcome(
            request,
            coordinated,
            accepted,
            executionEvidence,
            1,
            false,
            control.killSwitchGeneration()
        );
    }

    private void validateStaticBinding() {
        AiProviderDescriptor descriptor = provider.descriptor();
        if (descriptor.providerType() != AiProviderType.REMOTE
            || !descriptor.providerId().equals(control.providerId())
            || !descriptor.providerVersion().version().equals(control.providerVersion())
            || !descriptor.models().stream().allMatch(model ->
                model.providerId().equals(control.providerId())
                    && model.modelId().equals(control.modelId())
                    && model.version().equals(control.modelVersion()))
            || descriptor.models().size() != 1) {
            throw new IllegalArgumentException(
                "production Provider descriptor must match the exact server-owned profile"
            );
        }
        if (!route.enabled()
            || !route.routeId().equals(control.routeId())
            || !route.versions().provider().equals(descriptor.providerVersion())
            || !descriptor.models().contains(route.versions().model())
            || route.budget().timeout().compareTo(control.maximumTimeout()) > 0
            || !policy.enabled()
            || !policy.allowedProviderIds().equals(Set.of(control.providerId()))
            || !policy.allowedModelAuthorizationKeys().equals(Set.of(
                route.versions().model().authorizationKey()
            ))) {
            throw new IllegalArgumentException(
                "production route and policy must match the exact Provider profile"
            );
        }
    }

    private void requirePreInvocationControl(Request request) {
        if (!control.enabled()
            || control.maximumProviderAttempts() != 1
            || !control.killSwitchEnabled()
            || !control.circuitPermit()
            || !control.tenantRatePermit()
            || !control.globalRatePermit()
            || !control.costPermit()
            || !request.expectedVersions().equals(route.versions())
            || !route.supports(request.useCase().capability())
            || request.projection().providerFields().isEmpty()
            || request.projection().providerFields().size()
                > route.budget().maximumInputFields()
            || inputCharacters(request.projection().providerFields())
                > route.budget().maximumInputCharacters()) {
            throw new ProductionAdmissionException("AI_PRODUCTION_ADMISSION_REJECTED");
        }
    }

    private AiProviderRequest providerRequest(Request request) {
        ApprovalAssistanceContextProjection projection = request.projection();
        AiServerRequestContext context = projection.requestContext();
        AiAuthorizedResource resource = projection.authorizedResource();
        Set<String> fieldKeys = projection.providerFields().stream()
            .map(AiProviderRequest.InputField::key)
            .collect(Collectors.toUnmodifiableSet());
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
            fieldKeys,
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

    public record ProductionControl(
        boolean enabled,
        String providerId,
        String providerVersion,
        String modelId,
        String modelVersion,
        String routeId,
        int maximumProviderAttempts,
        Duration maximumTimeout,
        boolean killSwitchEnabled,
        long killSwitchGeneration,
        String killSwitchEvidenceHash,
        boolean circuitPermit,
        boolean tenantRatePermit,
        boolean globalRatePermit,
        boolean costPermit,
        String costPolicyEvidenceHash,
        String secretVersionEvidenceHash,
        int maximumOutputTokens
    ) {
        private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

        public ProductionControl {
            providerId = requireText(providerId, "providerId", 120);
            providerVersion = requireText(providerVersion, "providerVersion", 120);
            modelId = requireText(modelId, "modelId", 160);
            modelVersion = requireText(modelVersion, "modelVersion", 160);
            routeId = requireText(routeId, "routeId", 120);
            maximumTimeout = Objects.requireNonNull(
                maximumTimeout,
                "maximumTimeout must not be null"
            );
            if (!enabled || maximumProviderAttempts != 1
                || maximumTimeout.isZero() || maximumTimeout.isNegative()
                || maximumTimeout.compareTo(Duration.ofSeconds(15)) > 0
                || !killSwitchEnabled || killSwitchGeneration < 1
                || !circuitPermit || !tenantRatePermit || !globalRatePermit || !costPermit
                || maximumOutputTokens < 1 || maximumOutputTokens > 16_384) {
                throw new IllegalArgumentException(
                    "production control requires all exact fail-closed admissions"
                );
            }
            killSwitchEvidenceHash = requireSha256(
                killSwitchEvidenceHash,
                "killSwitchEvidenceHash"
            );
            costPolicyEvidenceHash = requireSha256(
                costPolicyEvidenceHash,
                "costPolicyEvidenceHash"
            );
            secretVersionEvidenceHash = requireSha256(
                secretVersionEvidenceHash,
                "secretVersionEvidenceHash"
            );
        }

        private static String requireText(String value, String name, int maximumLength) {
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(name + " must be non-blank and bounded");
            }
            return normalized;
        }

        private static String requireSha256(String value, String name) {
            String normalized = requireText(value, name, 64).toLowerCase();
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
        AiAdvisoryExecutionEvidence executionEvidence,
        int providerAttempts,
        boolean retryAttempted,
        long killSwitchGeneration
    ) {
        public Outcome {
            request = Objects.requireNonNull(request, "request must not be null");
            coordinated = Objects.requireNonNull(coordinated, "coordinated must not be null");
            executionEvidence = Objects.requireNonNull(
                executionEvidence,
                "executionEvidence must not be null"
            );
            if (providerAttempts != 1 || !coordinated.providerInvocationStarted()) {
                throw new IllegalArgumentException(
                    "production outcome requires exactly one Provider invocation"
                );
            }
            if (retryAttempted || coordinated.postInvocationFallbackAttempted()) {
                throw new IllegalArgumentException(
                    "production outcome cannot contain retry or fallback"
                );
            }
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
            }
            if (coordinated.outcome().hasAdvisoryResult() != (acceptedResult != null)) {
                throw new IllegalArgumentException(
                    "accepted result must match the final Provider outcome"
                );
            }
            if (acceptedResult != null && !acceptedResult.request().equals(request)) {
                throw new IllegalArgumentException(
                    "accepted result must bind the exact P2 request"
                );
            }
            if (executionEvidence.resultClassification()
                != coordinated.outcome().classification()) {
                throw new IllegalArgumentException(
                    "execution evidence must match the final classification"
                );
            }
        }
    }

    public static final class ProductionAdmissionException extends RuntimeException {

        public ProductionAdmissionException(String code) {
            super(Objects.requireNonNull(code, "code must not be null"));
        }
    }
}
