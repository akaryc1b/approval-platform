package io.github.akaryc1b.approval.ai.core;

/** Fail-closed preparation or output policy violation. */
public class AiPolicyViolationException extends RuntimeException {

    private final String code;

    public AiPolicyViolationException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code.trim();
    }

    public String code() {
        return code;
    }
}
