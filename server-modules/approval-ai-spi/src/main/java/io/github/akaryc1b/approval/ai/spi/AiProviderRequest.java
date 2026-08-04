package io.github.akaryc1b.approval.ai.spi;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-safe request assembled from server-owned identity, authorization and minimized fields.
 */
public record AiProviderRequest(
    AuthorizedContext context,
    AuthorizedResource resource,
    AiCapability capability,
    Set<String> allowedFieldKeys,
    List<InputField> fields,
    AiVersionReferences versions,
    Duration timeout
) {

    public AiProviderRequest {
        context = Objects.requireNonNull(context, "context must not be null");
        resource = Objects.requireNonNull(resource, "resource must not be null");
        capability = Objects.requireNonNull(capability, "capability must not be null");
        allowedFieldKeys = allowedFieldKeys == null ? Set.of() : Set.copyOf(allowedFieldKeys);
        fields = fields == null ? List.of() : List.copyOf(fields);
        versions = Objects.requireNonNull(versions, "versions must not be null");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (!context.tenantId().equals(resource.tenantId())) {
            throw new IllegalArgumentException("request tenant evidence must match");
        }
        if (fields.size() > 500 || allowedFieldKeys.size() > 500) {
            throw new IllegalArgumentException("request fields must be bounded");
        }
        for (InputField field : fields) {
            if (!allowedFieldKeys.contains(field.key())) {
                throw new IllegalArgumentException(
                    "provider field is not authorized: " + field.key()
                );
            }
        }
    }

    public record AuthorizedContext(
        String tenantId,
        String operatorId,
        String requestId,
        String traceId
    ) {
        public AuthorizedContext {
            tenantId = requireText(tenantId, "tenantId", 128);
            operatorId = requireText(operatorId, "operatorId", 200);
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, "traceId", 128);
        }
    }

    public record AuthorizedResource(
        String tenantId,
        String resourceType,
        String resourceId,
        String authorizationReference
    ) {
        public AuthorizedResource {
            tenantId = requireText(tenantId, "tenantId", 128);
            resourceType = requireText(resourceType, "resourceType", 64);
            resourceId = requireText(resourceId, "resourceId", 200);
            authorizationReference = requireText(
                authorizationReference,
                "authorizationReference",
                200
            );
        }
    }

    public record InputField(
        String key,
        String type,
        Object value,
        MaskingDisposition maskingDisposition
    ) {
        public InputField {
            key = requireText(key, "key", 160);
            type = requireText(type, "type", 64);
            value = Objects.requireNonNull(value, "value must not be null");
            maskingDisposition = Objects.requireNonNull(
                maskingDisposition,
                "maskingDisposition must not be null"
            );
        }
    }

    public enum MaskingDisposition {
        INCLUDED,
        MASKED
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String normalizeOptional(
        String value,
        String name,
        int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return normalized;
    }
}
