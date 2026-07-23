package io.github.akaryc1b.approval.ai.core;

import java.util.Objects;
import java.util.Set;

/** Server-authorized resource and exact field allowlist. */
public record AiAuthorizedResource(
    String tenantId,
    ResourceType resourceType,
    String resourceId,
    String authorizationReference,
    Set<String> allowedFieldKeys
) {

    public AiAuthorizedResource {
        tenantId = requireText(tenantId, "tenantId", 120);
        resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        resourceId = requireText(resourceId, "resourceId", 200);
        authorizationReference = requireText(
            authorizationReference,
            "authorizationReference",
            200
        );
        allowedFieldKeys = allowedFieldKeys == null ? Set.of() : Set.copyOf(allowedFieldKeys);
    }

    public enum ResourceType {
        PROCESS_INSTANCE,
        APPROVAL_TASK,
        FORM_SUBMISSION
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
