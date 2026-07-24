package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;

/** Metadata-only validation request; no prompt, business input or model response is included. */
public record AiProviderProtocolValidationRequest(
    AiVersionReferences versions,
    AiCapability capability,
    String endpointPolicyAuthorizationKey,
    int estimatedRequestBytes,
    int maximumExpectedResponseBytes,
    boolean structuredOutputRequested,
    boolean providerInvocationAttempted,
    boolean secretResolutionAttempted,
    boolean networkCallAttempted
) {

    public AiProviderProtocolValidationRequest {
        versions = Objects.requireNonNull(versions, "versions must not be null");
        capability = Objects.requireNonNull(capability, "capability must not be null");
        endpointPolicyAuthorizationKey = requireText(
            endpointPolicyAuthorizationKey,
            "endpointPolicyAuthorizationKey",
            240
        );
        if (estimatedRequestBytes < 0 || estimatedRequestBytes > 16_777_216) {
            throw new IllegalArgumentException("estimatedRequestBytes must be bounded");
        }
        if (maximumExpectedResponseBytes < 1
            || maximumExpectedResponseBytes > 16_777_216) {
            throw new IllegalArgumentException(
                "maximumExpectedResponseBytes must be bounded"
            );
        }
        if (!structuredOutputRequested) {
            throw new IllegalArgumentException(
                "provider validation requests must require structured output"
            );
        }
        if (providerInvocationAttempted || secretResolutionAttempted || networkCallAttempted) {
            throw new IllegalArgumentException(
                "provider validation requests must remain zero-call and secret-free"
            );
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
