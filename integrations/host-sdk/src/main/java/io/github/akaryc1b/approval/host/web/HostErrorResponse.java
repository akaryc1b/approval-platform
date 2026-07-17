package io.github.akaryc1b.approval.host.web;

import io.github.akaryc1b.approval.host.model.ConnectorError;

import java.time.Instant;
import java.util.Objects;

/**
 * Stable JSON error envelope returned by host connector endpoints.
 */
public record HostErrorResponse(
    String code,
    String message,
    boolean retryable,
    String requestId,
    Instant timestamp
) {

    public HostErrorResponse {
        code = requireText(code, "code");
        message = requireText(message, "message");
        requestId = requireText(requestId, "requestId");
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static HostErrorResponse from(
        ConnectorError error,
        String requestId,
        Instant timestamp
    ) {
        Objects.requireNonNull(error, "error must not be null");
        return new HostErrorResponse(
            error.code(),
            error.message(),
            error.retryable(),
            requestId,
            timestamp
        );
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
