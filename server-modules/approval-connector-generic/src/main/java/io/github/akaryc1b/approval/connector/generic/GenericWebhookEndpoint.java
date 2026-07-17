package io.github.akaryc1b.approval.connector.generic;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record GenericWebhookEndpoint(
    URI uri,
    String keyId,
    byte[] secret,
    Duration timeout,
    Map<String, String> headers
) {

    public GenericWebhookEndpoint {
        uri = Objects.requireNonNull(uri, "uri must not be null");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("uri scheme must be http or https");
        }
        keyId = requireText(keyId, "keyId");
        secret = Objects.requireNonNull(secret, "secret must not be null").clone();
        if (secret.length < 32) {
            throw new IllegalArgumentException("secret must contain at least 32 bytes");
        }
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
