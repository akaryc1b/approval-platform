package io.github.akaryc1b.approval.integration.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * HMAC-SHA256 signer using the canonical timestamp, nonce and body sequence.
 */
public final class HmacSha256WebhookSigner {

    public static final String VERSION_PREFIX = "v1=";
    private static final String ALGORITHM = "HmacSHA256";

    public String sign(byte[] secret, long timestamp, String nonce, String body) {
        byte[] digest = digest(secret, timestamp, nonce, body);
        return VERSION_PREFIX + HexFormat.of().formatHex(digest);
    }

    public boolean verify(
        byte[] secret,
        long timestamp,
        String nonce,
        String body,
        String suppliedSignature
    ) {
        if (suppliedSignature == null || !suppliedSignature.startsWith(VERSION_PREFIX)) {
            return false;
        }
        String expected = sign(secret, timestamp, nonce, body);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            suppliedSignature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private byte[] digest(byte[] secret, long timestamp, String nonce, String body) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 32) {
            throw new IllegalArgumentException("secret must contain at least 32 bytes");
        }
        nonce = requireText(nonce, "nonce");
        body = Objects.requireNonNull(body, "body must not be null");
        String canonical = timestamp + "\n" + nonce + "\n" + body;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
