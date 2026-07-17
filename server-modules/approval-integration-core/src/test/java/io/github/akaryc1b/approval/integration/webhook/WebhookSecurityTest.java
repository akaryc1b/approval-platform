package io.github.akaryc1b.approval.integration.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSecurityTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);

    @Test
    void canonicalJsonSortsObjectKeysAndPreservesArrayOrder() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("b", true);
        nested.put("a", null);
        var value = new LinkedHashMap<String, Object>();
        value.put("z", 1);
        value.put("a", List.of("first", "second"));
        value.put("nested", nested);

        String json = CanonicalJson.write(value);

        assertEquals(
            "{\"a\":[\"first\",\"second\"],\"nested\":{\"a\":null,\"b\":true},\"z\":1}",
            json
        );
    }

    @Test
    void signatureDetectsBodyTimestampAndNonceChanges() {
        var signer = new HmacSha256WebhookSigner();
        String body = "{\"event\":\"approved\"}";
        String signature = signer.sign(SECRET, 1000, "nonce-1", body);

        assertTrue(signer.verify(SECRET, 1000, "nonce-1", body, signature));
        assertFalse(signer.verify(SECRET, 1001, "nonce-1", body, signature));
        assertFalse(signer.verify(SECRET, 1000, "nonce-2", body, signature));
        assertFalse(signer.verify(SECRET, 1000, "nonce-1", body + " ", signature));
    }

    @Test
    void verifierRejectsExpiredAndInvalidRequests() {
        var signer = new HmacSha256WebhookSigner();
        var verifier = new SignedWebhookVerifier(signer, Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        String body = "{}";
        long timestamp = now.getEpochSecond();
        String signature = signer.sign(SECRET, timestamp, "nonce", body);

        assertEquals(
            SignedWebhookVerifier.VerificationResult.VALID,
            verifier.verify(SECRET, Long.toString(timestamp), "nonce", body, signature, now)
        );
        assertEquals(
            SignedWebhookVerifier.VerificationResult.EXPIRED,
            verifier.verify(SECRET, Long.toString(timestamp - 301), "nonce", body, signature, now)
        );
        assertEquals(
            SignedWebhookVerifier.VerificationResult.INVALID_SIGNATURE,
            verifier.verify(SECRET, Long.toString(timestamp), "nonce", body, "v1=00", now)
        );
    }

    @Test
    void payloadHashChangesWhenBodyChanges() {
        var hasher = new Sha256PayloadHasher();
        String first = hasher.hash("{\"value\":1}");
        String second = hasher.hash("{\"value\":2}");

        assertEquals(64, first.length());
        assertNotEquals(first, second);
    }
}
