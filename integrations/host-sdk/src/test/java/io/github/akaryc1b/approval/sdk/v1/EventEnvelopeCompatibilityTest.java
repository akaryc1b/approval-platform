package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.ApprovalSdk.Correlation;
import io.github.akaryc1b.approval.sdk.v1.ApprovalSdk.Error;
import io.github.akaryc1b.approval.sdk.v1.ApprovalSdk.ErrorCategory;
import io.github.akaryc1b.approval.sdk.v1.ApprovalSdk.Request;
import io.github.akaryc1b.approval.sdk.v1.ApprovalSdk.Result;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeCompatibilityTest {
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    @Test
    void parsesAndSerializesDeterministicCrossLanguageFixture() throws Exception {
        Map<String, Object> fixture = FixtureSupport.fixture();
        Map<String, Object> eventMap = FixtureSupport.object(fixture, "event");
        Map<String, Object> expectations = FixtureSupport.object(fixture, "expectations");
        String rawEvent = CanonicalJson.canonicalizeValue(eventMap);

        assertEquals(expectations.get("canonicalEventJson"), rawEvent);
        assertEquals(
            expectations.get("canonicalPayloadJson"),
            CanonicalJson.canonicalizeValue(eventMap.get("payload"))
        );
        assertEquals(
            expectations.get("payloadSha256"),
            CanonicalJson.sha256Hex(
                CanonicalJson.canonicalizeValue(eventMap.get("payload"))
                    .getBytes(StandardCharsets.UTF_8)
            )
        );

        EventEnvelopeV1 event = codec.parse(rawEvent);
        assertEquals("evt_01JZSDK000000000000000001", event.eventId());
        assertEquals(CanonicalJson.MAX_SAFE_INTEGER, event.resource().version());
        assertEquals(expectations.get("canonicalKnownEventJson"), codec.toCanonicalJson(event));
        assertEquals("tenant_server_001", event.tenant().tenantId());
    }

    @Test
    void rejectsUnknownVersionButAllowsUnknownFields() throws Exception {
        Map<String, Object> fixture = FixtureSupport.fixture();
        Map<String, Object> original = FixtureSupport.object(fixture, "event");
        Map<String, Object> unsupported = new LinkedHashMap<>(original);
        unsupported.put("schemaVersion", "2.0");
        assertThrows(
            EventEnvelopeV1.UnsupportedSchemaVersionException.class,
            () -> codec.parse(CanonicalJson.canonicalizeValue(unsupported))
        );

        Map<String, Object> forwardCompatible = new LinkedHashMap<>(original);
        forwardCompatible.put("anotherFutureField", Map.of("enabled", true));
        assertEquals(original.get("eventId"), codec.parse(
            CanonicalJson.canonicalizeValue(forwardCompatible)
        ).eventId());
    }

    @Test
    void enforcesCrossLanguageIntegerAndUnicodePolicy() {
        assertEquals("{\"message\":\"审批✅ Café\",\"value\":9007199254740991}", CanonicalJson.canonicalize(
            "{\"value\":9007199254740991,\"message\":\"审批✅ Café\"}"
        ));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("9007199254740992"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("0.125"));
    }

    @Test
    void clientRequestCannotCarryTrustedServerEvidence() {
        Request request = new Request(
            "approval.task.read",
            Map.of("taskId", "task_9001"),
            new Correlation("req-sdk-1", "trace-sdk-1"),
            ApprovalSdk.idempotencyKey(
                "approval.task.read",
                "req-sdk-1",
                Map.of("taskId", "task_9001")
            )
        );
        Map<String, Boolean> fieldNames = new LinkedHashMap<>();
        for (var component : Request.class.getRecordComponents()) {
            fieldNames.put(component.getName(), Boolean.TRUE);
        }
        for (String forbidden : new String[] {
            "tenant", "tenantId", "operator", "permission", "authority", "auditEvidence"
        }) {
            assertFalse(fieldNames.containsKey(forbidden));
        }
        assertTrue(request.idempotencyKey().matches("[0-9a-f]{64}"));

        Result<String> failure = Result.failure(new Error(
            "temporary_unavailable",
            "Retry later",
            ErrorCategory.RETRYABLE,
            "req-sdk-1"
        ));
        assertFalse(failure.successful());
        assertInstanceOf(Error.class, failure.error());
        assertEquals(ErrorCategory.RETRYABLE, failure.error().category());
    }
}
