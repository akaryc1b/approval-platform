package io.github.akaryc1b.approval.host.security;

/**
 * Signs and verifies the canonical host request payload.
 */
public interface HostSignatureVerifier {

    String sign(byte[] secret, long timestamp, String nonce, String body);

    boolean verify(
        byte[] secret,
        long timestamp,
        String nonce,
        String body,
        String signature
    );
}
