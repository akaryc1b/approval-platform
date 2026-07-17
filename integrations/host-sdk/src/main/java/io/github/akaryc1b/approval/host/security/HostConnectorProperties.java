package io.github.akaryc1b.approval.host.security;

import java.util.Objects;

public record HostConnectorProperties(String source, String tenantKeyId) {

    public HostConnectorProperties {
        source = requireText(source, "source");
        tenantKeyId = requireText(tenantKeyId, "tenantKeyId");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
