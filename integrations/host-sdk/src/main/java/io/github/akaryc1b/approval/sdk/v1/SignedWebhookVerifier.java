package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC verifier for canonical webhook payload bytes with bounded replay protection. */
public final class SignedWebhookVerifier {
    public static final String ALGORITHM = "hmac-sha256";
    public static final String PROTOCOL = "approval-webhook-v1";

    private final Clock clock;
    private final Duration allowedClockSkew;
    private final KeyResolver keyResolver;
    private final NonceReplayGuard replayGuard;

    public SignedWebhookVerifier(
        Clock clock,
        Duration allowedClockSkew,
        KeyResolver keyResolver,
        NonceReplayGuard replayGuard
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowedClockSkew = Objects.requireNonNull(allowedClockSkew, "allowedClockSkew");
        if (allowedClockSkew.isNegative() || allowedClockSkew.isZero()) {
            throw new IllegalArgumentException("allowedClockSkew must be positive");
        }
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.replayGuard = Objects.requireNonNull(replayGuard, "replayGuard");
    }

    public VerificationResult verify(String rawPayload, SignatureHeaders headers) {
        Objects.requireNonNull(rawPayload, "rawPayload");
        Objects.requireNonNull(headers, "headers");
        if (!ALGORITHM.equals(headers.algorithm())) {
            return VerificationResult.UNSUPPORTED_ALGORITHM;
        }
        Instant now = clock.instant();
        Instant timestamp;
        try {
            timestamp = Instant.ofEpochSecond(headers.timestampEpochSeconds());
        } catch (RuntimeException exception) {
            return VerificationResult.TIMESTAMP_OUT_OF_RANGE;
        }
        Duration distance = Duration.between(timestamp, now).abs();
        if (distance.compareTo(allowedClockSkew) > 0) {
            return VerificationResult.TIMESTAMP_OUT_OF_RANGE;
        }
        Optional<byte[]> key = keyResolver.resolve(headers.keyReference());
        if (key.isEmpty()) {
            return VerificationResult.KEY_NOT_FOUND;
        }
        byte[] input;
        try {
            input = signatureInputBytes(rawPayload, headers);
        } catch (IllegalArgumentException exception) {
            return VerificationResult.INVALID_PAYLOAD;
        }
        byte[] expected = hmacSha256(key.get(), input);
        byte[] supplied;
        try {
            supplied = parseHex(headers.signature());
        } catch (IllegalArgumentException exception) {
            return VerificationResult.INVALID_SIGNATURE;
        }
        if (!constantTimeEquals(expected, supplied)) {
            return VerificationResult.INVALID_SIGNATURE;
        }
        Instant expiresAt = timestamp.plus(allowedClockSkew);
        if (!replayGuard.reserve(headers.keyReference(), headers.nonce(), expiresAt, now)) {
            return VerificationResult.NONCE_REPLAY;
        }
        return VerificationResult.VERIFIED;
    }

    public static byte[] canonicalPayloadBytes(String rawPayload) {
        return CanonicalJson.canonicalBytes(rawPayload);
    }

    public static byte[] signatureInputBytes(String rawPayload, SignatureHeaders headers) {
        byte[] canonicalPayload = canonicalPayloadBytes(rawPayload);
        String payloadHash = CanonicalJson.sha256Hex(canonicalPayload);
        String prefix = PROTOCOL + "\n"
            + headers.timestampEpochSeconds() + "\n"
            + headers.nonce() + "\n"
            + headers.algorithm() + "\n"
            + headers.keyReference() + "\n"
            + payloadHash + "\n";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOf(prefixBytes, prefixBytes.length + canonicalPayload.length);
        System.arraycopy(canonicalPayload, 0, input, prefixBytes.length, canonicalPayload.length);
        return input;
    }

    public static String signHex(byte[] key, String rawPayload, SignatureHeaders unsignedHeaders) {
        return CanonicalJson.hex(hmacSha256(key, signatureInputBytes(rawPayload, unsignedHeaders)));
    }

    private static byte[] hmacSha256(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 must be available", exception);
        }
    }

    private static byte[] parseHex(String value) {
        if (value == null || value.length() != 64) {
            throw new IllegalArgumentException("HMAC-SHA256 signature must contain 64 hex characters");
        }
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            int high = Character.digit(value.charAt(index), 16);
            int low = Character.digit(value.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Signature is not hexadecimal");
            }
            result[index / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        int difference = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            byte leftByte = index < left.length ? left[index] : 0;
            byte rightByte = index < right.length ? right[index] : 0;
            difference |= leftByte ^ rightByte;
        }
        return difference == 0;
    }

    public record SignatureHeaders(
        long timestampEpochSeconds,
        String nonce,
        String algorithm,
        String keyReference,
        String signature
    ) {
        public SignatureHeaders {
            nonce = EventEnvelopeV1.required(nonce, "nonce");
            algorithm = EventEnvelopeV1.required(algorithm, "algorithm");
            keyReference = EventEnvelopeV1.required(keyReference, "keyReference");
            signature = signature == null ? "" : signature;
        }

        public SignatureHeaders withSignature(String value) {
            return new SignatureHeaders(timestampEpochSeconds, nonce, algorithm, keyReference, value);
        }
    }

    public enum VerificationResult {
        VERIFIED,
        TIMESTAMP_OUT_OF_RANGE,
        NONCE_REPLAY,
        UNSUPPORTED_ALGORITHM,
        KEY_NOT_FOUND,
        INVALID_SIGNATURE,
        INVALID_PAYLOAD
    }

    @FunctionalInterface
    public interface KeyResolver {
        Optional<byte[]> resolve(String keyReference);
    }

    @FunctionalInterface
    public interface NonceReplayGuard {
        boolean reserve(String keyReference, String nonce, Instant expiresAt, Instant now);
    }

    public static final class InMemoryNonceReplayGuard implements NonceReplayGuard {
        private final Map<String, Instant> reservations = new ConcurrentHashMap<>();

        @Override
        public synchronized boolean reserve(
            String keyReference,
            String nonce,
            Instant expiresAt,
            Instant now
        ) {
            reservations.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            String key = keyReference + ':' + nonce;
            if (reservations.containsKey(key)) {
                return false;
            }
            reservations.put(key, expiresAt);
            return true;
        }
    }
}
