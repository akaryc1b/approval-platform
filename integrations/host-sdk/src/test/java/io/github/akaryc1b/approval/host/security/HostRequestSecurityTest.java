package io.github.akaryc1b.approval.host.security;

import io.github.akaryc1b.approval.host.model.ConnectorError;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRequestSecurityTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef"
        .getBytes(StandardCharsets.UTF_8);

    @Test
    void hmacSignatureDetectsCanonicalInputChanges() {
        var verifier = new HmacSha256HostSignatureVerifier();
        String body = "{\"action\":\"authenticate\"}";
        String signature = verifier.sign(SECRET, NOW.getEpochSecond(), "nonce-1", body);

        assertTrue(verifier.verify(SECRET, NOW.getEpochSecond(), "nonce-1", body, signature));
        assertFalse(verifier.verify(SECRET, NOW.getEpochSecond() + 1, "nonce-1", body, signature));
        assertFalse(verifier.verify(SECRET, NOW.getEpochSecond(), "nonce-2", body, signature));
        assertFalse(verifier.verify(SECRET, NOW.getEpochSecond(), "nonce-1", body + " ", signature));
        assertFalse(verifier.verify(SECRET, NOW.getEpochSecond(), "nonce-1", body, "invalid"));
    }

    @Test
    void requestVerifierReturnsVerifiedContextAndRejectsReplay() {
        var signatureVerifier = new HmacSha256HostSignatureVerifier();
        var requestVerifier = verifier(signatureVerifier);
        var request = signedRequest(signatureVerifier, "nonce-1", NOW.getEpochSecond(), "{}");

        var verified = requestVerifier.verify(request);

        assertEquals("tenant-a", verified.tenantKey());
        assertEquals("key-1", verified.keyId());
        assertEquals("request-1", verified.requestId());
        assertEquals(NOW, verified.requestedAt());
        assertEquals(NOW, verified.verifiedAt());

        var replayed = assertThrows(
            HostVerificationException.class,
            () -> requestVerifier.verify(request)
        );
        assertEquals(ConnectorError.replayedRequest(), replayed.error());
    }

    @Test
    void invalidSignatureDoesNotConsumeNonce() {
        var signatureVerifier = new HmacSha256HostSignatureVerifier();
        var requestVerifier = verifier(signatureVerifier);
        var invalid = new HostRequestVerifier.Request(
            "tenant-a",
            "key-1",
            "request-1",
            Long.toString(NOW.getEpochSecond()),
            "nonce-2",
            "{}",
            "v1=00"
        );

        var failure = assertThrows(
            HostVerificationException.class,
            () -> requestVerifier.verify(invalid)
        );
        assertEquals(ConnectorError.invalidSignature(), failure.error());

        var valid = signedRequest(signatureVerifier, "nonce-2", NOW.getEpochSecond(), "{}");
        assertEquals("nonce-2", requestVerifier.verify(valid).nonce());
    }

    @Test
    void requestVerifierRejectsExpiredUnknownKeyAndMalformedTimestamp() {
        var signatureVerifier = new HmacSha256HostSignatureVerifier();
        var requestVerifier = verifier(signatureVerifier);
        var expired = signedRequest(
            signatureVerifier,
            "nonce-3",
            NOW.minus(Duration.ofMinutes(6)).getEpochSecond(),
            "{}"
        );

        var expiredFailure = assertThrows(
            HostVerificationException.class,
            () -> requestVerifier.verify(expired)
        );
        assertEquals(ConnectorError.expiredRequest(), expiredFailure.error());

        var unknownKey = new HostRequestVerifier.Request(
            "tenant-a",
            "missing",
            "request-1",
            Long.toString(NOW.getEpochSecond()),
            "nonce-4",
            "{}",
            "v1=00"
        );
        var unknownFailure = assertThrows(
            HostVerificationException.class,
            () -> requestVerifier.verify(unknownKey)
        );
        assertEquals(ConnectorError.unknownKey(), unknownFailure.error());

        var malformed = new HostRequestVerifier.Request(
            "tenant-a",
            "key-1",
            "request-1",
            "not-a-timestamp",
            "nonce-5",
            "{}",
            "v1=00"
        );
        var malformedFailure = assertThrows(
            HostVerificationException.class,
            () -> requestVerifier.verify(malformed)
        );
        assertEquals("INVALID_REQUEST", malformedFailure.error().code());
    }

    @Test
    void secretMaterialAndAuthenticationCollectionsAreDefensive() {
        byte[] source = SECRET.clone();
        var material = new TenantSecretResolver.SecretMaterial("key-1", source);
        source[0] = 0;
        byte[] firstRead = material.secret();
        assertTrue(firstRead[0] != 0);
        firstRead[0] = 0;
        assertTrue(material.secret()[0] != 0);
    }

    private static DefaultHostRequestVerifier verifier(
        HostSignatureVerifier signatureVerifier
    ) {
        var properties = new HostConnectorProperties(
            "ruoyi5",
            Duration.ofMinutes(5),
            Duration.ofMinutes(10)
        );
        TenantSecretResolver resolver = (tenantKey, keyId) -> {
            if ("tenant-a".equals(tenantKey) && "key-1".equals(keyId)) {
                return Optional.of(new TenantSecretResolver.SecretMaterial(keyId, SECRET));
            }
            return Optional.empty();
        };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new DefaultHostRequestVerifier(
            properties,
            resolver,
            signatureVerifier,
            new InMemoryReplayGuard(clock),
            clock
        );
    }

    private static HostRequestVerifier.Request signedRequest(
        HostSignatureVerifier verifier,
        String nonce,
        long timestamp,
        String body
    ) {
        return new HostRequestVerifier.Request(
            "tenant-a",
            "key-1",
            "request-1",
            Long.toString(timestamp),
            nonce,
            body,
            verifier.sign(SECRET, timestamp, nonce, body)
        );
    }
}
