package io.github.akaryc1b.approval.host.security;

import java.util.Objects;

/**
 * Framework neutral inbound request verification contract.
 */
public interface HostRequestVerifier {

    Verification verify(Request request);

    record Request(String tenantKey, String timestamp, String nonce, String body, String signature) {
        public Request {
            tenantKey = require(tenantKey, "tenantKey");
            timestamp = require(timestamp, "timestamp");
            nonce = require(nonce, "nonce");
            body = Objects.requireNonNull(body, "body");
            signature = require(signature, "signature");
        }
    }

    enum Verification {
        PASS,
        INVALID_SIGNATURE,
        EXPIRED,
        REPLAYED
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
