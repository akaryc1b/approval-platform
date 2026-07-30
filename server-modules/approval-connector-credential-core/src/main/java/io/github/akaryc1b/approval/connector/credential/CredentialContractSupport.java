package io.github.akaryc1b.approval.connector.credential;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class CredentialContractSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private CredentialContractSupport() {
    }

    static String requireIdentifier(String value, String name) {
        String normalized = requireText(value, name, 128);
        if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256 value");
        }
        return normalized;
    }

    static Map<String, String> boundedMetadata(Map<String, String> source, String name) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > 16) {
            throw new IllegalArgumentException(name + " exceeds 16 entries");
        }
        Map<String, String> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, name + " key", 64);
            if (isSensitiveName(normalizedKey)) {
                throw new IllegalArgumentException(name + " contains sensitive key " + normalizedKey);
            }
            sorted.put(normalizedKey, requireText(value, name + " value", 256));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    static boolean isSensitiveName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("authorization")
            || normalized.contains("credential")
            || normalized.contains("apikey")
            || normalized.contains("privatekey");
    }

    static String json(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    static String instant(Instant value) {
        return value == null ? "null" : json(value.toString());
    }
}
