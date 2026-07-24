package io.github.akaryc1b.approval.connector.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CanonicalPayloadHash {

    private CanonicalPayloadHash() {
    }

    public static String sha256Utf8(String canonicalPayload) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
