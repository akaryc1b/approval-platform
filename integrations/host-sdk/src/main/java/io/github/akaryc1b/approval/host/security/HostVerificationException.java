package io.github.akaryc1b.approval.host.security;

import io.github.akaryc1b.approval.host.model.ConnectorError;

import java.util.Objects;

/**
 * Raised before a host controller invokes authentication or organization services.
 */
public final class HostVerificationException extends RuntimeException {

    private final ConnectorError error;

    public HostVerificationException(ConnectorError error) {
        super(Objects.requireNonNull(error, "error must not be null").message());
        this.error = error;
    }

    public ConnectorError error() {
        return error;
    }
}
