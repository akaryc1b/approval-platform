package io.github.akaryc1b.approval.connector.model;

import java.util.Map;
import java.util.Objects;

public record TenantSnapshot(
    ExternalId id,
    String name,
    boolean active,
    Map<String, String> attributes
) {

    public TenantSnapshot {
        id = Objects.requireNonNull(id, "id must not be null");
        name = requireText(name, "name");
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
