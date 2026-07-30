package io.github.akaryc1b.approval.ai.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Precomputed offline transport fixture observation with no response body. */
public record AiProviderTransportFixtureObservation(
    AiVersionReferences.ProviderVersion providerVersion,
    String mapperAuthorizationKey,
    String observedResponseSchemaHash,
    Shape shape,
    int observedBytes,
    String responseEvidenceHash,
    boolean timeoutObserved,
    boolean cancellationObserved,
    boolean rawBodyStored,
    boolean networkCallAttempted,
    boolean providerInvocationAttempted,
    boolean retryAttempted
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderTransportFixtureObservation {
        providerVersion = Objects.requireNonNull(
            providerVersion,
            "providerVersion must not be null"
        );
        mapperAuthorizationKey = requireText(
            mapperAuthorizationKey,
            "mapperAuthorizationKey",
            300
        );
        observedResponseSchemaHash = normalizeOptionalSha256(
            observedResponseSchemaHash,
            "observedResponseSchemaHash"
        );
        shape = Objects.requireNonNull(shape, "shape must not be null");
        if (observedBytes < 0 || observedBytes > 16_777_216) {
            throw new IllegalArgumentException("observedBytes must be bounded");
        }
        responseEvidenceHash = requireSha256(
            responseEvidenceHash,
            "responseEvidenceHash"
        );
        if (rawBodyStored) {
            throw new IllegalArgumentException(
                "transport fixture observations cannot store raw response bodies"
            );
        }
        if (networkCallAttempted || providerInvocationAttempted || retryAttempted) {
            throw new IllegalArgumentException(
                "transport fixture observations must remain offline and zero-call"
            );
        }
        if ((shape == Shape.TIMEOUT) != timeoutObserved) {
            throw new IllegalArgumentException(
                "timeoutObserved must match the TIMEOUT fixture shape"
            );
        }
        if ((shape == Shape.CANCELLED) != cancellationObserved) {
            throw new IllegalArgumentException(
                "cancellationObserved must match the CANCELLED fixture shape"
            );
        }
        if (shape == Shape.STRUCTURED_VALID && observedResponseSchemaHash == null) {
            throw new IllegalArgumentException(
                "structured valid fixtures require an observed response schema hash"
            );
        }
    }

    public enum Shape {
        STRUCTURED_VALID,
        MALFORMED_JSON,
        SCHEMA_DRIFT,
        UNKNOWN_FIELDS,
        BODY_TOO_LARGE,
        TIMEOUT,
        CANCELLED,
        CONNECTION_ERROR,
        EMPTY_BODY,
        UNKNOWN
    }

    private static String normalizeOptionalSha256(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireSha256(value, name);
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
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
