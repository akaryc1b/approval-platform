package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Precomputed TLS peer evidence. This type performs no handshake or network operation. */
public record AiTlsPeerEvidence(
    String endpointAuthorizationKey,
    String host,
    String certificateSpkiSha256,
    Status status,
    boolean redirectObserved,
    String evidenceHash,
    boolean tlsHandshakePerformed,
    boolean networkCallAttempted
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiTlsPeerEvidence {
        endpointAuthorizationKey = requireText(
            endpointAuthorizationKey,
            "endpointAuthorizationKey",
            500
        );
        host = requireText(host, "host", 253).toLowerCase();
        certificateSpkiSha256 = normalizeOptionalSha256(certificateSpkiSha256);
        status = Objects.requireNonNull(status, "status must not be null");
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");
        if (tlsHandshakePerformed || networkCallAttempted) {
            throw new IllegalArgumentException(
                "M6-D TLS evidence must be precomputed and zero-call"
            );
        }
        if (status == Status.CHAIN_HOST_AND_PIN_MATCHED
            && (certificateSpkiSha256 == null || redirectObserved)) {
            throw new IllegalArgumentException(
                "matched TLS evidence requires a pin and no redirect"
            );
        }
        if (status == Status.REDIRECT_OBSERVED && !redirectObserved) {
            throw new IllegalArgumentException(
                "REDIRECT_OBSERVED requires redirect evidence"
            );
        }
    }

    public enum Status {
        CHAIN_HOST_AND_PIN_MATCHED,
        HOSTNAME_MISMATCH,
        CHAIN_INVALID,
        CERTIFICATE_EXPIRED,
        PIN_MISMATCH,
        REDIRECT_OBSERVED,
        UNKNOWN
    }

    private static String normalizeOptionalSha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireSha256(value, "certificateSpkiSha256");
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
