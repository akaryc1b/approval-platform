package io.github.akaryc1b.approval.example.host;

final class GenericHostException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    GenericHostException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }

    boolean retryable() {
        return retryable;
    }
}
