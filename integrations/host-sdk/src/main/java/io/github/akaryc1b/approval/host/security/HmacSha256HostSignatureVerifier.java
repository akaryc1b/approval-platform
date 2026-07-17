package io.github.akaryc1b.approval.host.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * HMAC-SHA256 implementation using the canonical timestamp, nonce and body input.
 */
public final class HmacSha256HostSignatureVerifier implements HostSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "v1=";

    @Override
    public String sign(byte[] secret, long timestamp, String nonce, String body) {
        Objects.requireNonNull(secret, "secret must not be null");
        nonce = requireText(nonce, "nonce");
        body = Objects.requireNonNull(body, "body must not be null");

        byte[] digest = digest(secret, canonical(timestamp, nonce, body));
        return PREFIX + HexFormat.of().formatHex(digest);
    }

    @Override
    public boolean verify(
        byte[] secret,
        long timestamp,
        String nonce,
        String body,
        String signature
    ) {
        if (signature == null || !signature.startsWith(PREFIX)) {
            return false;
        }
        String expected = sign(secret, timestamp, nonce, body);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static byte[] digest(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static String canonical(long timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + body;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
