package io.github.akaryc1b.approval.host.model;

import java.util.Objects;

/**
 * Framework-neutral error returned by host connector endpoints.
 */
public record ConnectorError(String code, String message, boolean retryable) {

    public ConnectorError {
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    public static ConnectorError invalidSignature() {
        return new ConnectorError("INVALID_SIGNATURE", "signature validation failed", false);
    }

    public static ConnectorError expiredRequest() {
        return new ConnectorError("EXPIRED_REQUEST", "request timestamp is outside the allowed window", false);
    }

    public static ConnectorError replayedRequest() {
        return new ConnectorError("REPLAYED_REQUEST", "request nonce has already been used", false);
    }

    public static ConnectorError unknownKey() {
        return new ConnectorError("UNKNOWN_KEY", "tenant signing key was not found", false);
    }

    public static ConnectorError invalidRequest(String message) {
        return new ConnectorError("INVALID_REQUEST", message, false);
    }

    public static ConnectorError temporaryFailure(String message) {
        return new ConnectorError("TEMPORARY_FAILURE", message, true);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
