package io.github.akaryc1b.approval.host.security;

import java.time.Duration;
import java.util.Objects;

/**
 * Security defaults shared by host-side connector implementations.
 */
public record HostConnectorProperties(
    String source,
    Duration allowedClockSkew,
    Duration nonceTtl
) {

    public HostConnectorProperties {
        source = requireText(source, "source");
        allowedClockSkew = requirePositive(allowedClockSkew, "allowedClockSkew");
        nonceTtl = requirePositive(nonceTtl, "nonceTtl");
        if (nonceTtl.compareTo(allowedClockSkew) < 0) {
            throw new IllegalArgumentException("nonceTtl must not be shorter than allowedClockSkew");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
