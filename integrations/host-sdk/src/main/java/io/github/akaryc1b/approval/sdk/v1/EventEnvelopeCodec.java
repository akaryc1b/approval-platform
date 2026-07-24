package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses inbound envelopes fail-closed by schema version while tolerating unknown fields. */
public final class EventEnvelopeCodec {
    public EventEnvelopeV1 parse(String json) {
        Object parsed = CanonicalJson.parse(json);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Event envelope must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) rawMap;
        EventEnvelopeV1 event = EventEnvelopeV1.fromMap(envelope);
        String actualHash = CanonicalJson.sha256Hex(
            CanonicalJson.canonicalizeValue(event.payload()).getBytes(StandardCharsets.UTF_8)
        );
        if (!constantTimeEquals(actualHash, event.payloadHash())) {
            throw new IllegalArgumentException("Event payload hash does not match canonical payload bytes");
        }
        return event;
    }

    public String toCanonicalJson(EventEnvelopeV1 event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", event.schemaVersion());
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", event.eventType());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("tenant", Map.of("tenantId", event.tenant().tenantId()));
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("resourceType", event.resource().resourceType());
        resource.put("resourceId", event.resource().resourceId());
        if (event.resource().version() != null) {
            resource.put("version", event.resource().version());
        }
        envelope.put("resource", resource);
        envelope.put("requestId", event.requestId());
        envelope.put("traceId", event.traceId());
        envelope.put("payload", event.payload());
        envelope.put("payloadHash", event.payloadHash());
        envelope.put(
            "producer",
            Map.of("service", event.producer().service(), "instance", event.producer().instance())
        );
        putOptional(envelope, "orderingKey", event.orderingKey());
        putOptional(envelope, "causationId", event.causationId());
        putOptional(envelope, "correlationId", event.correlationId());
        return CanonicalJson.canonicalizeValue(envelope);
    }

    private static void putOptional(Map<String, Object> values, String key, String value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.US_ASCII);
        byte[] rightBytes = right.getBytes(StandardCharsets.US_ASCII);
        int difference = leftBytes.length ^ rightBytes.length;
        int length = Math.max(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            byte leftByte = index < leftBytes.length ? leftBytes[index] : 0;
            byte rightByte = index < rightBytes.length ? rightBytes[index] : 0;
            difference |= leftByte ^ rightByte;
        }
        return difference == 0;
    }
}
