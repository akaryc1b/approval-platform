package io.github.akaryc1b.approval.integration.webhook;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Minimal deterministic JSON writer used for signing connector payloads.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        appendValue(builder, value);
        return builder.toString();
    }

    private static void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            appendString(builder, string);
        } else if (value instanceof Character character) {
            appendString(builder, character.toString());
        } else if (value instanceof Boolean bool) {
            builder.append(bool);
        } else if (value instanceof Number number) {
            appendNumber(builder, number);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(builder, map);
        } else if (value instanceof Collection<?> collection) {
            appendIterable(builder, collection);
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(builder, iterable);
        } else if (value instanceof byte[] bytes) {
            appendString(builder, Base64.getEncoder().encodeToString(bytes));
        } else if (value.getClass().isArray()) {
            appendArray(builder, value);
        } else if (value instanceof UUID || value instanceof TemporalAccessor || value instanceof Enum<?>) {
            appendString(builder, value.toString());
        } else {
            throw new IllegalArgumentException("Unsupported canonical JSON type: " + value.getClass().getName());
        }
    }

    private static void appendMap(StringBuilder builder, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Canonical JSON object keys must be strings");
            }
            sorted.put(key, entry.getValue());
        }
        builder.append('{');
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            appendString(builder, entry.getKey());
            builder.append(':');
            appendValue(builder, entry.getValue());
            first = false;
        }
        builder.append('}');
    }

    private static void appendIterable(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                builder.append(',');
            }
            appendValue(builder, item);
            first = false;
        }
        builder.append(']');
    }

    private static void appendArray(StringBuilder builder, Object array) {
        builder.append('[');
        int length = Array.getLength(array);
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendValue(builder, Array.get(array, index));
        }
        builder.append(']');
    }

    private static void appendNumber(StringBuilder builder, Number number) {
        if (number instanceof Double value && !Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite numbers are not valid JSON");
        }
        if (number instanceof Float value && !Float.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite numbers are not valid JSON");
        }
        if (number instanceof BigDecimal decimal) {
            builder.append(decimal.stripTrailingZeros().toPlainString());
        } else if (number instanceof BigInteger integer) {
            builder.append(integer);
        } else if (number instanceof Byte || number instanceof Short
            || number instanceof Integer || number instanceof Long) {
            builder.append(number);
        } else {
            builder.append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        }
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}
