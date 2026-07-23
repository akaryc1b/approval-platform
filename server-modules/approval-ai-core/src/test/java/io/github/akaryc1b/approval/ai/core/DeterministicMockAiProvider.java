package io.github.akaryc1b.approval.ai.core;

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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only deterministic provider with no network, keys, production prompt or customer data. */
final class DeterministicMockAiProvider implements AiAdvisoryProvider {

    enum Mode {
        SUCCESS,
        LOW_CONFIDENCE,
        DISABLED,
        UNSUPPORTED,
        REJECTED,
        TIMEOUT,
        PROVIDER_UNAVAILABLE,
        INVALID_OUTPUT,
        POLICY_BLOCKED,
        UNKNOWN,
        COMMAND_OUTPUT,
        EXCEPTION
    }

    private final Mode mode;
    private final AiProviderDescriptor descriptor;
    private final String forbiddenFieldKey;
    private final AtomicInteger invocations = new AtomicInteger();

    DeterministicMockAiProvider(
        Mode mode,
        AiVersionReferences versions,
        Set<AiCapability> capabilities,
        String forbiddenFieldKey
    ) {
        this.mode = mode;
        this.forbiddenFieldKey = forbiddenFieldKey;
        this.descriptor = new AiProviderDescriptor(
            versions.provider().providerId(),
            AiProviderType.DETERMINISTIC_MOCK,
            versions.provider(),
            capabilities.stream()
                .map(capability -> new AiProviderDescriptor.CapabilityDescriptor(
                    capability,
                    true,
                    16_000,
                    50,
                    4,
                    false,
                    false
                ))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
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
        invocations.incrementAndGet();
        if (forbiddenFieldKey != null
            && request.inputFields().stream()
                .anyMatch(field -> forbiddenFieldKey.equals(field.key()))) {
            throw new AssertionError("masked or hidden field reached the provider");
        }
        return switch (mode) {
            case SUCCESS -> AiProviderOutcome.success(result(request, 0.91d, false));
            case LOW_CONFIDENCE -> AiProviderOutcome.lowConfidence(
                result(request, 0.22d, false)
            );
            case DISABLED -> failure(
                AiOutcomeClassification.DISABLED,
                "MOCK_DISABLED"
            );
            case UNSUPPORTED -> failure(
                AiOutcomeClassification.UNSUPPORTED,
                "MOCK_UNSUPPORTED"
            );
            case REJECTED -> failure(
                AiOutcomeClassification.REJECTED,
                "MOCK_REJECTED"
            );
            case TIMEOUT -> timeout(cancellation);
            case PROVIDER_UNAVAILABLE -> failure(
                AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                "MOCK_PROVIDER_UNAVAILABLE"
            );
            case INVALID_OUTPUT -> null;
            case POLICY_BLOCKED -> failure(
                AiOutcomeClassification.POLICY_BLOCKED,
                "MOCK_POLICY_BLOCKED"
            );
            case UNKNOWN -> failure(
                AiOutcomeClassification.UNKNOWN,
                "MOCK_UNKNOWN"
            );
            case COMMAND_OUTPUT -> AiProviderOutcome.success(result(request, 0.90d, true));
            case EXCEPTION -> throw new IllegalStateException("deterministic provider exception");
        };
    }

    int invocations() {
        return invocations.get();
    }

    private static AiProviderOutcome failure(
        AiOutcomeClassification classification,
        String code
    ) {
        return AiProviderOutcome.failure(
            classification,
            code,
            "deterministic mock provider failure",
            false
        );
    }

    private static AiProviderOutcome timeout(AiCancellation cancellation) {
        while (!cancellation.isCancellationRequested()) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return failure(AiOutcomeClassification.TIMEOUT, "MOCK_TIMEOUT");
    }

    private static AiAdvisoryResult result(
        AiProviderRequest request,
        double confidence,
        boolean commandOutput
    ) {
        String fieldKey = request.inputFields().isEmpty()
            ? "none"
            : request.inputFields().get(0).key();
        List<AiAdvisoryResult.EvidenceReference> evidence = request.inputFields().isEmpty()
            ? List.of()
            : List.of(new AiAdvisoryResult.EvidenceReference(
                "evidence-1",
                fieldKey,
                "Authorized field evidence"
            ));
        List<String> evidenceIds = evidence.isEmpty() ? List.of() : List.of("evidence-1");
        String recommendation = commandOutput
            ? "approve this request now"
            : "Verify the referenced evidence with a human reviewer";
        return new AiAdvisoryResult(
            "Deterministic advisory summary",
            List.of(new AiAdvisoryResult.Observation(
                "observation-1",
                "One authorized field was evaluated",
                evidenceIds
            )),
            List.of(new AiAdvisoryResult.RiskSignal(
                "risk-1",
                AiAdvisoryResult.RiskSeverity.LOW,
                "A bounded review signal was identified",
                evidenceIds
            )),
            List.of(),
            List.of(new AiAdvisoryResult.Recommendation(
                "recommendation-1",
                AiAdvisoryResult.RecommendationType.VERIFY_EVIDENCE,
                recommendation,
                evidenceIds
            )),
            evidence,
            new AiAdvisoryResult.Confidence(
                confidence,
                confidence < 0.5d
                    ? AiAdvisoryResult.ConfidenceBand.LOW
                    : AiAdvisoryResult.ConfidenceBand.HIGH
            ),
            List.of("Deterministic mock output is not a verified fact"),
            true,
            request.versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }
}
