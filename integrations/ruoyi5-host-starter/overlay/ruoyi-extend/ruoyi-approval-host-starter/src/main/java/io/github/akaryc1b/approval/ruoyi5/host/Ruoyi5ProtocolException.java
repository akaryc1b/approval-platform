package io.github.akaryc1b.approval.ruoyi5.host;

final class Ruoyi5ProtocolException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    Ruoyi5ProtocolException(int status, String code, String message, boolean retryable) {
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

    static Ruoyi5ProtocolException unauthorized(String message) {
        return new Ruoyi5ProtocolException(401, "UNAUTHORIZED", message, false);
    }

    static Ruoyi5ProtocolException invalid(String message) {
        return new Ruoyi5ProtocolException(400, "INVALID_REQUEST", message, false);
    }

    static Ruoyi5ProtocolException missing(String message) {
        return new Ruoyi5ProtocolException(404, "NOT_FOUND", message, false);
    }
}
