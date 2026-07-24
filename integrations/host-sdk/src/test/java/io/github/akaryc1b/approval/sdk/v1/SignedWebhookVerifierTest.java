package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.InMemoryNonceReplayGuard;
import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.SignatureHeaders;
import io.github.akaryc1b.approval.sdk.v1.SignedWebhookVerifier.VerificationResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignedWebhookVerifierTest {
    @Test
    void verifiesFixtureAndRejectsNonceReplay() throws Exception {
        FixtureValues fixture = fixtureValues();
        InMemoryNonceReplayGuard guard = new InMemoryNonceReplayGuard();
        SignedWebhookVerifier verifier = verifier(fixture, guard, fixture.timestamp());
        assertEquals(VerificationResult.VERIFIED, verifier.verify(fixture.rawEvent(), fixture.headers()));
        assertEquals(VerificationResult.NONCE_REPLAY, verifier.verify(fixture.rawEvent(), fixture.headers()));
        assertEquals(fixture.signatureInputHex(), CanonicalJson.hex(
            SignedWebhookVerifier.signatureInputBytes(fixture.rawEvent(), fixture.headers())
        ));
        assertEquals(fixture.signatureInputSha256(), CanonicalJson.sha256Hex(
            SignedWebhookVerifier.signatureInputBytes(fixture.rawEvent(), fixture.headers())
        ));
    }

    @Test
    void rejectsClockSkewAndTamperingWithoutConsumingNonce() throws Exception {
        FixtureValues fixture = fixtureValues();
        InMemoryNonceReplayGuard staleGuard = new InMemoryNonceReplayGuard();
        assertEquals(
            VerificationResult.TIMESTAMP_OUT_OF_RANGE,
            verifier(fixture, staleGuard, fixture.timestamp() + 301)
                .verify(fixture.rawEvent(), fixture.headers())
        );

        Map<String, Object> tamperedEvent = new LinkedHashMap<>(fixture.event());
        Map<String, Object> tamperedPayload = new LinkedHashMap<>(
            FixtureSupport.object(tamperedEvent, "payload")
        );
        tamperedPayload.put("decision", "REJECTED");
        tamperedEvent.put("payload", tamperedPayload);
        InMemoryNonceReplayGuard tamperGuard = new InMemoryNonceReplayGuard();
        SignedWebhookVerifier verifier = verifier(fixture, tamperGuard, fixture.timestamp());
        assertEquals(
            VerificationResult.INVALID_SIGNATURE,
            verifier.verify(CanonicalJson.canonicalizeValue(tamperedEvent), fixture.headers())
        );
        assertEquals(VerificationResult.VERIFIED, verifier.verify(fixture.rawEvent(), fixture.headers()));
    }

    private static SignedWebhookVerifier verifier(
        FixtureValues fixture,
        InMemoryNonceReplayGuard guard,
        long now
    ) {
        return new SignedWebhookVerifier(
            Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
            Duration.ofSeconds(300),
            keyReference -> keyReference.equals(fixture.headers().keyReference())
                ? Optional.of(fixture.secret()) : Optional.empty(),
            guard
        );
    }

    private static FixtureValues fixtureValues() throws Exception {
        Map<String, Object> fixture = FixtureSupport.fixture();
        Map<String, Object> event = FixtureSupport.object(fixture, "event");
        Map<String, Object> webhook = FixtureSupport.object(fixture, "webhook");
        Map<String, Object> expectations = FixtureSupport.object(fixture, "expectations");
        long timestamp = ((Number) webhook.get("timestampEpochSeconds")).longValue();
        SignatureHeaders headers = new SignatureHeaders(
            timestamp,
            (String) webhook.get("nonce"),
            (String) webhook.get("algorithm"),
            (String) webhook.get("keyReference"),
            (String) webhook.get("signature")
        );
        return new FixtureValues(
            event,
            CanonicalJson.canonicalizeValue(event),
            headers,
            ((String) webhook.get("secretUtf8")).getBytes(StandardCharsets.UTF_8),
            timestamp,
            (String) expectations.get("signatureInputHex"),
            (String) expectations.get("signatureInputSha256")
        );
    }

    private record FixtureValues(
        Map<String, Object> event,
        String rawEvent,
        SignatureHeaders headers,
        byte[] secret,
        long timestamp,
        String signatureInputHex,
        String signatureInputSha256
    ) {
    }
}
