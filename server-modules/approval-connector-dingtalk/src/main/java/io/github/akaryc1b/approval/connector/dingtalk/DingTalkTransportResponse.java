package io.github.akaryc1b.approval.connector.dingtalk;

import java.time.Instant;
import java.util.Objects;

/**
 * Bounded result from an injected DingTalk transport implementation.
 */
public record DingTalkTransportResponse(
    State state,
    int statusCode,
    String providerRequestId,
    String body,
    Instant completedAt
) {

    public DingTalkTransportResponse {
        state = Objects.requireNonNull(state, "state must not be null");
        providerRequestId = optionalText(providerRequestId, "providerRequestId", 128);
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (state == State.RESPONDED) {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("responded statusCode must be between 100 and 599");
            }
            body = requireText(body, "body", 65_536);
        } else {
            if (statusCode != 0) {
                throw new IllegalArgumentException("non-response statusCode must be zero");
            }
            if (body != null && !body.isBlank()) {
                throw new IllegalArgumentException("non-response result must not contain a body");
            }
            body = null;
        }
    }

    public static DingTalkTransportResponse responded(
        int statusCode,
        String providerRequestId,
        String body,
        Instant completedAt
    ) {
        return new DingTalkTransportResponse(
            State.RESPONDED,
            statusCode,
            providerRequestId,
            body,
            completedAt
        );
    }

    public static DingTalkTransportResponse timeout(Instant completedAt) {
        return new DingTalkTransportResponse(State.TIMEOUT, 0, null, null, completedAt);
    }

    public static DingTalkTransportResponse unknown(Instant completedAt) {
        return new DingTalkTransportResponse(State.UNKNOWN, 0, null, null, completedAt);
    }

    public enum State {
        RESPONDED,
        TIMEOUT,
        UNKNOWN
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return value;
    }

    private static String optionalText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, name, maximumLength).trim();
    }
}
