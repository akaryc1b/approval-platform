package io.github.akaryc1b.approval.connector.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class ConnectorContractSupport {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private ConnectorContractSupport() {
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

    static String optionalText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, name, maximumLength);
    }

    static String requireCode(String value, String name) {
        String code = requireText(value, name, 64).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(name + " must be an upper-case bounded code");
        }
        return code;
    }

    static String requireSafeIdentifier(String value, String name) {
        String identifier = requireText(value, name, 128);
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return identifier;
    }

    static String requireSha256(String value, String name) {
        String hash = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(hash).matches()) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256 value");
        }
        return hash;
    }

    static Map<String, String> boundedMetadata(
        Map<String, String> source,
        String name,
        int maximumEntries,
        int maximumKeyLength,
        int maximumValueLength,
        boolean rejectSensitiveKeys
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > maximumEntries) {
            throw new IllegalArgumentException(name + " exceeds " + maximumEntries + " entries");
        }
        Map<String, String> sorted = new TreeMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireText(key, name + " key", maximumKeyLength);
            if (rejectSensitiveKeys && isSensitiveName(normalizedKey)) {
                throw new IllegalArgumentException(name + " must not contain sensitive key " + normalizedKey);
            }
            sorted.put(
                normalizedKey,
                requireText(value, name + " value", maximumValueLength)
            );
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    static boolean isSensitiveName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("authorization")
            || normalized.contains("credential")
            || normalized.contains("apikey");
    }

    static String json(String value) {
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
}
