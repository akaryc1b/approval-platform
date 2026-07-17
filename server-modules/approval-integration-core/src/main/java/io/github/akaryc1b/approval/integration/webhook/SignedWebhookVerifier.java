package io.github.akaryc1b.approval.integration.webhook;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Validates signature syntax and timestamp freshness before Inbox processing.
 */
public final class SignedWebhookVerifier {

    private final HmacSha256WebhookSigner signer;
    private final Duration allowedClockSkew;

    public SignedWebhookVerifier(
        HmacSha256WebhookSigner signer,
        Duration allowedClockSkew
    ) {
        this.signer = Objects.requireNonNull(signer, "signer must not be null");
        this.allowedClockSkew = requirePositive(allowedClockSkew, "allowedClockSkew");
    }

    public VerificationResult verify(
        byte[] secret,
        String timestampHeader,
        String nonce,
        String body,
        String signature,
        Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null");
        if (nonce == null || nonce.isBlank() || nonce.length() > 256) {
            return VerificationResult.MALFORMED;
        }
        long timestamp;
        Instant signedAt;
        try {
            timestamp = Long.parseLong(timestampHeader);
            signedAt = Instant.ofEpochSecond(timestamp);
        } catch (NumberFormatException | DateTimeException exception) {
            return VerificationResult.MALFORMED;
        }
        Duration age = Duration.between(signedAt, now).abs();
        if (age.compareTo(allowedClockSkew) > 0) {
            return VerificationResult.EXPIRED;
        }
        if (!signer.verify(secret, timestamp, nonce, body, signature)) {
            return VerificationResult.INVALID_SIGNATURE;
        }
        return VerificationResult.VALID;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public enum VerificationResult {
        VALID,
        EXPIRED,
        INVALID_SIGNATURE,
        MALFORMED
    }
}
