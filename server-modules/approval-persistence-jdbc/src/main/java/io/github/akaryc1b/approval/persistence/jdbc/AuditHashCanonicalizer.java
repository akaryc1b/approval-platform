package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.audit.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Exact Java replica of the PostgreSQL V21 audit payload and chain hash contract. */
final class AuditHashCanonicalizer {

    private static final long NANOS_PER_MICROSECOND = 1_000L;
    private static final long HALF_MICROSECOND_NANOS = NANOS_PER_MICROSECOND / 2;
    private static final DateTimeFormatter MICROSECOND_INSTANT = new DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
        .appendLiteral('Z')
        .toFormatter(Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    private static final Comparator<JsonField> POSTGRESQL_JSONB_KEY_ORDER = (left, right) -> {
        int length = Integer.compare(left.utf8Name().length, right.utf8Name().length);
        if (length != 0) {
            return length;
        }
        for (int index = 0; index < left.utf8Name().length; index++) {
            int compared = Integer.compare(
                Byte.toUnsignedInt(left.utf8Name()[index]),
                Byte.toUnsignedInt(right.utf8Name()[index])
            );
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    };

    private AuditHashCanonicalizer() {
    }

    static String payloadHash(AuditEvent event) {
        return payloadHash(Payload.from(event));
    }

    static String payloadHash(Payload payload) {
        return sha256(payloadDocument(payload));
    }

    static String chainHash(String previousHash, String payloadHash) {
        return sha256(
            requireHash(previousHash, "previousHash")
                + ":"
                + requireHash(payloadHash, "payloadHash")
        );
    }

    static String payloadDocument(AuditEvent event) {
        return payloadDocument(Payload.from(event));
    }

    static String payloadDocument(Payload payload) {
        Payload exact = Objects.requireNonNull(payload, "payload must not be null");
        List<JsonField> fields = new ArrayList<>();
        fields.add(stringField("eventId", exact.eventId().toString()));
        fields.add(stringField("tenantId", exact.tenantId()));
        fields.add(stringField("operatorId", exact.operatorId()));
        fields.add(stringField("action", exact.action()));
        fields.add(stringField("aggregateType", exact.aggregateType()));
        fields.add(stringField("aggregateId", exact.aggregateId()));
        fields.add(stringField("schemaName", exact.schemaName()));
        fields.add(numberField("schemaVersion", exact.schemaVersion()));
        fields.add(stringField("requestId", exact.requestId()));
        fields.add(nullableStringField("traceId", exact.traceId()));
        fields.add(stringField(
            "occurredAt",
            MICROSECOND_INSTANT.format(canonicalInstant(exact.occurredAt()))
        ));
        fields.add(objectField("attributes", exact.attributes()));
        return object(fields);
    }

    static Instant canonicalInstant(Instant value) {
        Instant exact = Objects.requireNonNull(value, "instant must not be null");
        long remainder = exact.getNano() % NANOS_PER_MICROSECOND;
        if (remainder < HALF_MICROSECOND_NANOS) {
            return exact.minusNanos(remainder);
        }
        return exact.plusNanos(NANOS_PER_MICROSECOND - remainder);
    }

    private static JsonField stringField(String name, String value) {
        return field(name, output -> appendString(output, value));
    }

    private static JsonField nullableStringField(String name, String value) {
        return value == null
            ? field(name, output -> output.append("null"))
            : stringField(name, value);
    }

    private static JsonField numberField(String name, int value) {
        return field(name, output -> output.append(value));
    }

    private static JsonField objectField(String name, Map<String, String> values) {
        Map<String, String> exact = values == null ? Map.of() : values;
        return field(name, output -> {
            List<JsonField> entries = new ArrayList<>(exact.size());
            exact.forEach((key, value) -> entries.add(stringField(key, value)));
            output.append(object(entries));
        });
    }

    private static JsonField field(String name, JsonValue value) {
        String exactName = requireValidUnicode(name, "JSON field name");
        return new JsonField(
            exactName,
            exactName.getBytes(StandardCharsets.UTF_8),
            Objects.requireNonNull(value, "JSON value must not be null")
        );
    }

    private static String object(List<JsonField> values) {
        List<JsonField> ordered = new ArrayList<>(values);
        ordered.sort(POSTGRESQL_JSONB_KEY_ORDER);
        StringBuilder output = new StringBuilder();
        output.append('{');
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                output.append(", ");
            }
            JsonField field = ordered.get(index);
            appendString(output, field.name());
            output.append(": ");
            field.value().appendTo(output);
        }
        return output.append('}').toString();
    }

    private static void appendString(StringBuilder output, String value) {
        String exact = requireValidUnicode(value, "JSON string");
        output.append('"');
        for (int index = 0; index < exact.length(); index++) {
            char character = exact.charAt(index);
            if (Character.isHighSurrogate(character)) {
                output.append(character).append(exact.charAt(++index));
                continue;
            }
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        appendUnicodeEscape(output, character);
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String requireValidUnicode(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        for (int index = 0; index < exact.length(); index++) {
            char character = exact.charAt(index);
            if (character == 0) {
                throw new IllegalArgumentException(name + " contains U+0000");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= exact.length()
                    || !Character.isLowSurrogate(exact.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate");
            }
        }
        return exact;
    }

    private static void appendUnicodeEscape(StringBuilder output, char character) {
        String digits = "0123456789abcdef";
        output.append("\\u")
            .append(digits.charAt((character >>> 12) & 0x0f))
            .append(digits.charAt((character >>> 8) & 0x0f))
            .append(digits.charAt((character >>> 4) & 0x0f))
            .append(digits.charAt(character & 0x0f));
    }

    private static String requireHash(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null");
        if (!exact.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
        }
        return exact;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Payload(
        java.util.UUID eventId,
        String tenantId,
        String operatorId,
        String action,
        String aggregateType,
        String aggregateId,
        String schemaName,
        int schemaVersion,
        String requestId,
        String traceId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        Payload {
            eventId = Objects.requireNonNull(eventId, "eventId must not be null");
            tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
            operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
            action = Objects.requireNonNull(action, "action must not be null");
            aggregateType = Objects.requireNonNull(
                aggregateType,
                "aggregateType must not be null"
            );
            aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            schemaName = Objects.requireNonNull(schemaName, "schemaName must not be null");
            if (schemaVersion < 0) {
                throw new IllegalArgumentException("schemaVersion must not be negative");
            }
            requestId = Objects.requireNonNull(requestId, "requestId must not be null");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        static Payload from(AuditEvent event) {
            AuditEvent exact = Objects.requireNonNull(event, "event must not be null");
            return new Payload(
                exact.eventId(),
                exact.tenantId(),
                exact.operatorId(),
                exact.action(),
                exact.aggregateType(),
                exact.aggregateId(),
                exact.schemaName(),
                exact.schemaVersion(),
                exact.requestId(),
                exact.traceId(),
                exact.occurredAt(),
                exact.attributes()
            );
        }
    }

    @FunctionalInterface
    private interface JsonValue {
        void appendTo(StringBuilder output);
    }

    private record JsonField(String name, byte[] utf8Name, JsonValue value) {
        private JsonField {
            name = Objects.requireNonNull(name, "name must not be null");
            utf8Name = Objects.requireNonNull(utf8Name, "utf8Name must not be null").clone();
            value = Objects.requireNonNull(value, "value must not be null");
        }

        @Override
        public byte[] utf8Name() {
            return utf8Name.clone();
        }
    }
}
