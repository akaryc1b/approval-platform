package io.github.akaryc1b.approval.ruoyi5.host;

final class Ruoyi5HostException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    Ruoyi5HostException(int status, String code, String message, boolean retryable) {
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
