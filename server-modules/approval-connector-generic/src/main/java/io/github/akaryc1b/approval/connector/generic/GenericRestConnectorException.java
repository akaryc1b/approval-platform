package io.github.akaryc1b.approval.connector.generic;

public final class GenericRestConnectorException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public GenericRestConnectorException(
        String message,
        int statusCode,
        boolean retryable
    ) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public GenericRestConnectorException(
        String message,
        int statusCode,
        boolean retryable,
        Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
