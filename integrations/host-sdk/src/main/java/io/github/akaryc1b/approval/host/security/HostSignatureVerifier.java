package io.github.akaryc1b.approval.host.security;

/**
 * Verifies requests sent from approval-platform to host systems.
 *
 * Implementations must validate timestamp freshness and replay protection.
 */
public interface HostSignatureVerifier {

    VerificationResult verify(String tenantKey, String timestamp, String nonce, String body,
                              String signature);

    enum VerificationResult {
        VALID,
        INVALID_SIGNATURE,
        EXPIRED,
        REPLAYED
    }
}
