package io.github.akaryc1b.approval.api;

/** Stable high-risk management governance failure. */
public final class ApprovalManagementGovernanceException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    public ApprovalManagementGovernanceException(
        int status,
        String code,
        String message,
        boolean retryable
    ) {
        super(message);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP error status");
        }
        this.status = status;
        this.code = requireText(code, "code");
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
