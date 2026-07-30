package io.github.akaryc1b.approval.connector.contract;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Secret-free signing and replay-defense evidence.
 */
public record ConnectorSecurityEvidence(
    Instant timestamp,
    String nonce,
    SignatureAlgorithm signatureAlgorithm,
    Duration validityWindow,
    ReplayDetectionResult replayDetectionResult,
    String canonicalPayloadHash
) {

    private static final Duration MAXIMUM_VALIDITY_WINDOW = Duration.ofMinutes(10);
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    public ConnectorSecurityEvidence {
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        nonce = ConnectorContractSupport.requireText(nonce, "nonce", 128);
        if (!NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("nonce must contain 16 to 128 safe characters");
        }
        signatureAlgorithm = Objects.requireNonNull(
            signatureAlgorithm,
            "signatureAlgorithm must not be null"
        );
        validityWindow = Objects.requireNonNull(validityWindow, "validityWindow must not be null");
        if (validityWindow.isZero() || validityWindow.isNegative()
            || validityWindow.compareTo(MAXIMUM_VALIDITY_WINDOW) > 0) {
            throw new IllegalArgumentException("validityWindow must be positive and at most 10 minutes");
        }
        replayDetectionResult = Objects.requireNonNull(
            replayDetectionResult,
            "replayDetectionResult must not be null"
        );
        canonicalPayloadHash = ConnectorContractSupport.requireSha256(
            canonicalPayloadHash,
            "canonicalPayloadHash"
        );
    }

    public boolean timestampIsValidAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return Duration.between(timestamp, now).abs().compareTo(validityWindow) <= 0;
    }

    public enum SignatureAlgorithm {
        HMAC_SHA256_V1("hmac-sha256-v1");

        private final String identifier;

        SignatureAlgorithm(String identifier) {
            this.identifier = identifier;
        }

        public String identifier() {
            return identifier;
        }
    }

    public enum ReplayDetectionResult {
        NOT_CHECKED,
        ACCEPTED,
        REPLAYED,
        EXPIRED,
        NONCE_CONFLICT
    }
}
