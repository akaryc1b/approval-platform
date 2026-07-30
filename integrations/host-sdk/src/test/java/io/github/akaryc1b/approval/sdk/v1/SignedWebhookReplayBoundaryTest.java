package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.InMemoryNonceReplayGuard;
import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.SignatureHeaders;
import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.VerificationResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignedWebhookReplayBoundaryTest {
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final Duration SKEW = Duration.ofSeconds(300);
    private static final String RAW_PAYLOAD = "{}";
    private static final byte[] SECRET = "fixture-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void retainsReplayReservationAtExactAcceptedClockSkewBoundary() {
        InMemoryNonceReplayGuard guard = new InMemoryNonceReplayGuard();
        SignatureHeaders headers = signedHeaders("key-reference", "nonce-value");

        assertEquals(
            VerificationResult.VERIFIED,
            verifier(guard, TIMESTAMP).verify(RAW_PAYLOAD, headers)
        );
        assertEquals(
            VerificationResult.NONCE_REPLAY,
            verifier(guard, TIMESTAMP + SKEW.toSeconds()).verify(RAW_PAYLOAD, headers)
        );
    }

    @Test
    void replayIdentityCannotCollideThroughDelimiters() {
        InMemoryNonceReplayGuard guard = new InMemoryNonceReplayGuard();
        Instant now = Instant.ofEpochSecond(TIMESTAMP);
        Instant expiresAt = now.plus(SKEW);

        assertTrue(guard.reserve("tenant:key", "nonce", expiresAt, now));
        assertTrue(guard.reserve("tenant", "key:nonce", expiresAt, now));
        assertFalse(guard.reserve("tenant:key", "nonce", expiresAt, now));
    }

    private static SignedWebhookVerifier verifier(InMemoryNonceReplayGuard guard, long now) {
        return new SignedWebhookVerifier(
            Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
            SKEW,
            keyReference -> "key-reference".equals(keyReference)
                ? Optional.of(SECRET)
                : Optional.empty(),
            guard
        );
    }

    private static SignatureHeaders signedHeaders(String keyReference, String nonce) {
        SignatureHeaders unsigned = new SignatureHeaders(
            TIMESTAMP,
            nonce,
            SignedWebhookVerifier.ALGORITHM,
            keyReference,
            ""
        );
        return unsigned.withSignature(
            SignedWebhookVerifier.signHex(SECRET, RAW_PAYLOAD, unsigned)
        );
    }
}
