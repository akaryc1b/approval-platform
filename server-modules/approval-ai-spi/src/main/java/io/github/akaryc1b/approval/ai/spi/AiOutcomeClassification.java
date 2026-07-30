package io.github.akaryc1b.approval.ai.spi;

/** Stable provider and policy outcome classification. */
public enum AiOutcomeClassification {
    SUCCESS,
    DISABLED,
    UNSUPPORTED,
    REJECTED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_OUTPUT,
    POLICY_BLOCKED,
    LOW_CONFIDENCE,
    UNKNOWN
}
