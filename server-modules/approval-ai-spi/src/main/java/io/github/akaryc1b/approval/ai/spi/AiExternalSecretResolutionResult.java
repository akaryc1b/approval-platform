package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Metadata-only secret-reference evidence. No material can be returned by this contract. */
public record AiExternalSecretResolutionResult(
    AiVersionReferences.ProviderVersion providerVersion,
    String referenceAuthorizationKey,
    AiExternalSecretResolutionRequest.Purpose purpose,
    Status status,
    RotationState rotationState,
    String evidenceHash,
    boolean secretMaterialReturned,
    boolean secretResolutionPerformed,
    boolean networkCallAttempted,
    boolean providerInvocationAuthorized,
    boolean productionEnablementAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiExternalSecretResolutionResult {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        referenceAuthorizationKey = requireText(
            referenceAuthorizationKey,
            "referenceAuthorizationKey",
            360
        );
        purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        rotationState = Objects.requireNonNull(
            rotationState,
            "rotationState must not be null"
        );
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        if (secretMaterialReturned || secretResolutionPerformed || networkCallAttempted) {
            throw new IllegalArgumentException(
                "M6-D secret evidence must remain metadata-only and zero-call"
            );
        }
        if (providerInvocationAuthorized) {
            throw new IllegalArgumentException(
                "secret evidence cannot authorize Provider invocation"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "secret evidence cannot authorize production enablement"
            );
        }
        if (status == Status.REFERENCE_AVAILABLE && rotationState != RotationState.CURRENT) {
            throw new IllegalArgumentException(
                "available secret references must have CURRENT rotation evidence"
            );
        }
    }

    public enum Status {
        REFERENCE_AVAILABLE,
        REFERENCE_UNAVAILABLE,
        REFERENCE_REVOKED,
        REFERENCE_EXPIRED,
        UNKNOWN
    }

    public enum RotationState {
        CURRENT,
        ROTATION_DUE,
        EXPIRED,
        REVOKED,
        UNKNOWN
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
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
