package io.github.akaryc1b.approval.host.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authentication snapshot returned by a host framework adapter.
 */
public record HostAuthenticationResult(
    String userId,
    String tenantId,
    String username,
    String displayName,
    Set<String> permissions,
    Instant expiresAt,
    Map<String, String> attributes
) {

    public HostAuthenticationResult {
        userId = requireText(userId, "userId");
        tenantId = requireText(tenantId, "tenantId");
        username = requireText(username, "username");
        displayName = requireText(displayName, "displayName");
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
