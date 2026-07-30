package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiExternalSecretResolutionRequest;
import io.github.akaryc1b.approval.ai.spi.AiExternalSecretResolutionResult;
import io.github.akaryc1b.approval.ai.spi.AiExternalSecretResolver;

import java.util.Map;
import java.util.Objects;

/** Deterministic metadata-only secret resolver fixture. */
final class DeterministicExternalSecretResolver implements AiExternalSecretResolver {

    private final Map<String, AiExternalSecretResolutionResult.Status> statuses;

    DeterministicExternalSecretResolver(
        Map<String, AiExternalSecretResolutionResult.Status> statuses
    ) {
        this.statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
    }

    @Override
    public AiExternalSecretResolutionResult inspectReference(
        AiExternalSecretResolutionRequest request
    ) {
        Objects.requireNonNull(request, "request must not be null");
        AiExternalSecretResolutionResult.Status status = statuses.getOrDefault(
            request.referenceAuthorizationKey(),
            AiExternalSecretResolutionResult.Status.UNKNOWN
        );
        AiExternalSecretResolutionResult.RotationState rotation = switch (status) {
            case REFERENCE_AVAILABLE -> AiExternalSecretResolutionResult.RotationState.CURRENT;
            case REFERENCE_EXPIRED -> AiExternalSecretResolutionResult.RotationState.EXPIRED;
            case REFERENCE_REVOKED -> AiExternalSecretResolutionResult.RotationState.REVOKED;
            case REFERENCE_UNAVAILABLE, UNKNOWN ->
                AiExternalSecretResolutionResult.RotationState.UNKNOWN;
        };
        return new AiExternalSecretResolutionResult(
            request.providerVersion(),
            request.referenceAuthorizationKey(),
            request.purpose(),
            status,
            rotation,
            request.requestEvidenceHash(),
            false,
            false,
            false,
            false,
            false
        );
    }
}
