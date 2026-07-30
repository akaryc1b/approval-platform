package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidationRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidationResult;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidator;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only metadata validator. It never resolves a secret, performs I/O or invokes a Provider. */
final class DeterministicProtocolValidator implements AiProviderProtocolValidator {

    enum Mode {
        VALID,
        INVALID,
        UNSUPPORTED,
        UNKNOWN,
        EXCEPTION
    }

    private final AiProviderProtocolProfile profile;
    private final Mode mode;
    private final AtomicInteger validations = new AtomicInteger();

    DeterministicProtocolValidator(AiProviderProtocolProfile profile, Mode mode) {
        this.profile = profile;
        this.mode = mode;
    }

    @Override
    public AiProviderProtocolProfile profile() {
        return profile;
    }

    @Override
    public AiProviderProtocolValidationResult validate(
        AiProviderProtocolValidationRequest request
    ) {
        validations.incrementAndGet();
        if (!request.versions().provider().equals(profile.providerVersion())) {
            return invalid("AI_TEST_VALIDATOR_PROVIDER_MISMATCH");
        }
        if (!profile.supports(request.capability())) {
            return invalid("AI_TEST_VALIDATOR_CAPABILITY_MISMATCH");
        }
        if (request.estimatedRequestBytes() > profile.maximumRequestBytes()) {
            return invalid("AI_TEST_VALIDATOR_REQUEST_TOO_LARGE");
        }
        return switch (mode) {
            case VALID -> AiProviderProtocolValidationResult.valid();
            case INVALID -> invalid("AI_TEST_VALIDATOR_REJECTED");
            case UNSUPPORTED -> result(
                AiProviderProtocolValidationResult.Status.UNSUPPORTED,
                "AI_TEST_VALIDATOR_UNSUPPORTED"
            );
            case UNKNOWN -> result(
                AiProviderProtocolValidationResult.Status.UNKNOWN,
                "AI_TEST_VALIDATOR_UNKNOWN"
            );
            case EXCEPTION -> throw new IllegalStateException("deterministic validator exception");
        };
    }

    int validations() {
        return validations.get();
    }

    private static AiProviderProtocolValidationResult invalid(String code) {
        return new AiProviderProtocolValidationResult(
            AiProviderProtocolValidationResult.Status.INVALID,
            List.of(new AiProviderProtocolValidationResult.Issue(
                code,
                "deterministic structural validation failure",
                AiProviderProtocolValidationResult.Severity.ERROR
            )),
            false,
            false,
            false,
            false,
            false
        );
    }

    private static AiProviderProtocolValidationResult result(
        AiProviderProtocolValidationResult.Status status,
        String code
    ) {
        return new AiProviderProtocolValidationResult(
            status,
            List.of(new AiProviderProtocolValidationResult.Issue(
                code,
                "deterministic structural validation evidence",
                AiProviderProtocolValidationResult.Severity.WARNING
            )),
            false,
            false,
            false,
            false,
            false
        );
    }
}
