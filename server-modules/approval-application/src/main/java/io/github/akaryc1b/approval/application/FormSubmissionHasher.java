package io.github.akaryc1b.approval.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Produces deterministic hashes for normalized form submissions. */
public final class FormSubmissionHasher {

    public FormSubmissionHasher() {
    }

    public FormSubmissionHasher(Object ignoredSerializer) {
        this();
    }

    public String hash(
        String formKey,
        int formVersion,
        String schemaHash,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, Map.of(
                "businessKey", businessKey,
                "formKey", formKey,
                "formVersion", formVersion,
                "schemaHash", schemaHash,
                "startParameters", startParameters == null ? Map.of() : startParameters,
                "values", values == null ? Map.of() : values
            ));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(MessageDigest digest, Object value) {
        if (value == null) {
            update(digest, "null");
        } else if (value instanceof Map<?, ?> map) {
            update(digest, "map:" + map.size());
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            sorted.forEach((key, item) -> {
                update(digest, key);
                append(digest, item);
            });
        } else if (value instanceof List<?> list) {
            update(digest, "list:" + list.size());
            list.forEach(item -> append(digest, item));
        } else if (value instanceof Number number) {
            update(digest, "number:" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        } else if (value instanceof Boolean flag) {
            update(digest, "boolean:" + flag);
        } else {
            update(digest, "string:" + value);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}
