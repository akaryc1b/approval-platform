package io.github.akaryc1b.approval.connector.model;

import java.util.Objects;

/**
 * Stable identifier for an object owned by an external connector.
 */
public record ExternalId(String source, String objectType, String value) {

    public ExternalId {
        source = requireText(source, "source");
        objectType = requireText(objectType, "objectType");
        value = requireText(value, "value");
    }

    public String canonicalValue() {
        return source + ":" + objectType + ":" + value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
