package io.github.akaryc1b.approval.host.model;

public record ConnectorError(String code, String message, boolean retryable) {

    public static ConnectorError unauthorized() {
        return new ConnectorError("UNAUTHORIZED", "signature validation failed", false);
    }

    public static ConnectorError temporaryFailure(String message) {
        return new ConnectorError("TEMPORARY_FAILURE", message, true);
    }
}
