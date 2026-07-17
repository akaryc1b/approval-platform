package io.github.akaryc1b.approval.host.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Framework-neutral inbound request verification boundary.
 */
public interface HostRequestVerifier {

    VerifiedRequest verify(Request request);

    record Request(
        String tenantKey,
        String keyId,
        String requestId,
        String timestamp,
        String nonce,
        String body,
        String signature
    ) {
        public Request {
            tenantKey = requireText(tenantKey, "tenantKey");
            keyId = requireText(keyId, "keyId");
            requestId = requireText(requestId, "requestId");
            timestamp = requireText(timestamp, "timestamp");
            nonce = requireText(nonce, "nonce");
            body = Objects.requireNonNull(body, "body must not be null");
            signature = requireText(signature, "signature");
        }
    }

    record VerifiedRequest(
        String tenantKey,
        String keyId,
        String requestId,
        Instant requestedAt,
        Instant verifiedAt,
        String nonce
    ) {
        public VerifiedRequest {
            tenantKey = requireText(tenantKey, "tenantKey");
            keyId = requireText(keyId, "keyId");
            requestId = requireText(requestId, "requestId");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt must not be null");
            nonce = requireText(nonce, "nonce");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
