package io.github.akaryc1b.approval.ai.core;

/** Bounded input limit violation. */
public class AiInputLimitException extends AiPolicyViolationException {

    public AiInputLimitException(String code, String message) {
        super(code, message);
    }
}
