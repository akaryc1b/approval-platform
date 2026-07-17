package io.github.akaryc1b.approval.host.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record HostAuthenticationResult(
    String userId,
    String tenantId,
    String username,
    Set<String> permissions,
    Instant expiresAt,
    Map<String, String> attributes
) {
    public HostAuthenticationResult {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
