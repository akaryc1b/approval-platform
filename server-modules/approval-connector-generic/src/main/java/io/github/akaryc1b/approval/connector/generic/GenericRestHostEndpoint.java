package io.github.akaryc1b.approval.connector.generic;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Tenant-specific host endpoint used for authentication and organization queries.
 */
public record GenericRestHostEndpoint(
    URI baseUri,
    String keyId,
    byte[] secret,
    Duration timeout,
    Map<String, String> headers
) {

    public GenericRestHostEndpoint {
        baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        if (!"https".equalsIgnoreCase(baseUri.getScheme())
            && !"http".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("baseUri scheme must be http or https");
        }
        if (baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must not include query or fragment");
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

    public URI resolve(String path) {
        path = requireText(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        String base = baseUri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
