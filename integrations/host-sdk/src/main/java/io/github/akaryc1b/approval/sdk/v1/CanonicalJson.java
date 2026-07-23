package io.github.akaryc1b.approval.sdk.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic JSON parsing and serialization for signed SDK contracts. */
public final class CanonicalJson {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private CanonicalJson() {
    }

    public static Object parse(String json) {
        Objects.requireNonNull(json, "json");
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected trailing JSON content");
        }
        return value;
    }

    public static String canonicalize(String json) {
        return canonicalizeValue(parse(json));
    }

    public static byte[] canonicalBytes(String json) {
        return canonicalize(json).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonicalizeValue(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(value, output);
        return output.toString();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(value & 0x0f, 16));
        }
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            writeString(string, output);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Number number) {
            long integer = number.longValue();
            if (number.doubleValue() != integer || (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER)) {
                throw new IllegalArgumentException(
                    "Contract JSON numbers must be safe integers; encode decimals as strings"
                );
            }
            output.append(integer);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                keys.add(stringKey);
            }
            Collections.sort(keys);
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeString(key, output);
                output.append(':');
                writeValue(((Map<String, Object>) map).get(key), output);
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                writeValue(list.get(index), output);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
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
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String json;
        private int offset;

        private Parser(String json) {
            this.json = json;
        }

        private Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return switch (json.charAt(offset)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            ArrayList<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char character = json.charAt(offset++);
                if (character == '"') {
                    return result.toString();
                }
                if (character != '\\') {
                    if (character < 0x20) {
                        throw new IllegalArgumentException("Unescaped control character in JSON string");
                    }
                    result.append(character);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("Incomplete JSON escape");
                }
                char escaped = json.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(parseUnicode());
                    default -> throw new IllegalArgumentException("Unsupported JSON escape: " + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private String parseUnicode() {
            char first = (char) parseHexCodeUnit();
            if (Character.isHighSurrogate(first)) {
                if (offset + 1 >= json.length() || json.charAt(offset) != '\\' || json.charAt(offset + 1) != 'u') {
                    throw new IllegalArgumentException("High surrogate must be paired");
                }
                offset += 2;
                char second = (char) parseHexCodeUnit();
                if (!Character.isLowSurrogate(second)) {
                    throw new IllegalArgumentException("Invalid low surrogate");
                }
                return new String(new char[] {first, second});
            }
            if (Character.isLowSurrogate(first)) {
                throw new IllegalArgumentException("Unexpected low surrogate");
            }
            return String.valueOf(first);
        }

        private int parseHexCodeUnit() {
            if (offset + 4 > json.length()) {
                throw new IllegalArgumentException("Incomplete Unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(json.charAt(offset++), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("Invalid Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private Object parseNumber() {
            int start = offset;
            if (consume('-')) {
                // Sign consumed.
            }
            if (atEnd() || !Character.isDigit(json.charAt(offset))) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
            if (json.charAt(offset) == '0') {
                offset++;
            } else {
                while (!atEnd() && Character.isDigit(json.charAt(offset))) {
                    offset++;
                }
            }
            if (!atEnd() && (json.charAt(offset) == '.' || json.charAt(offset) == 'e' || json.charAt(offset) == 'E')) {
                throw new IllegalArgumentException(
                    "Contract JSON numbers must be integers; encode decimals as strings"
                );
            }
            long value;
            try {
                value = Long.parseLong(json.substring(start, offset));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("JSON integer is outside the supported range", exception);
            }
            if ((value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER)) {
                throw new IllegalArgumentException("JSON integer exceeds the cross-language safe range");
            }
            return value;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, offset)) {
                throw new IllegalArgumentException("Invalid JSON token");
            }
            offset += literal.length();
            return value;
        }

        private void expect(char expected) {
            if (atEnd() || json.charAt(offset) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at offset " + offset);
            }
            offset++;
        }

        private boolean consume(char character) {
            if (!atEnd() && json.charAt(offset) == character) {
                offset++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char character = json.charAt(offset);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                offset++;
            }
        }

        private boolean atEnd() {
            return offset >= json.length();
        }
    }
}
