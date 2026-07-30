package io.github.akaryc1b.approval.ai.spi;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider-ready request built only from server-owned authorization and minimized input. */
public record AiProviderRequest(
    AuthorizedContext context,
    AuthorizedResource resource,
    AiCapability capability,
    Set<String> allowedFields,
    List<InputField> inputFields,
    AiVersionReferences versions,
    Duration timeout
) {

    public AiProviderRequest {
        context = Objects.requireNonNull(context, "context must not be null");
        resource = Objects.requireNonNull(resource, "resource must not be null");
        capability = Objects.requireNonNull(capability, "capability must not be null");
        allowedFields = allowedFields == null ? Set.of() : Set.copyOf(allowedFields);
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        versions = Objects.requireNonNull(versions, "versions must not be null");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (!context.tenantId().equals(resource.tenantId())) {
            throw new IllegalArgumentException("request context and resource tenant must match");
        }
        if (inputFields.size() > 200) {
            throw new IllegalArgumentException("inputFields must be bounded");
        }
        java.util.Set<String> fieldKeys = new java.util.HashSet<>();
        for (InputField field : inputFields) {
            if (!fieldKeys.add(field.key())) {
                throw new IllegalArgumentException("input field keys must be unique");
            }
            if (!allowedFields.contains(field.key())) {
                throw new IllegalArgumentException(
                    "input field is not present in the allowed field set: " + field.key()
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
            tenantId = requireText(tenantId, "tenantId", 120);
            operatorId = requireText(operatorId, "operatorId", 200);
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
        }
    }

    public record AuthorizedResource(
        String tenantId,
        String resourceType,
        String resourceId,
        String authorizationReference
    ) {
        public AuthorizedResource {
            tenantId = requireText(tenantId, "tenantId", 120);
            resourceType = requireText(resourceType, "resourceType", 80);
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
            key = requireText(key, "field.key", 160);
            type = requireText(type, "field.type", 80);
            value = Objects.requireNonNull(value, "field.value must not be null");
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

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("optional value must be bounded");
        }
        return normalized;
    }
}
