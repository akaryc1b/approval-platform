package io.github.akaryc1b.approval.connector.dingtalk.token;

import io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class DingTalkTokenSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern STABLE_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private DingTalkTokenSupport() {
    }

    static String identifier(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    static String sha256(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lower-case SHA-256 value");
        }
        return normalized;
    }

    static String stableCode(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!STABLE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a stable low-cardinality code");
        }
        return normalized;
    }

    static String hash(String value) {
        return CanonicalPayloadHash.sha256Utf8(value);
    }

    static String json(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    static String instant(Instant value) {
        return value == null ? "null" : json(value.toString());
    }
}
