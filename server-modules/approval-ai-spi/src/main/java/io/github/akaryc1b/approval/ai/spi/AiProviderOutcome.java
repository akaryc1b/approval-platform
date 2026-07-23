package io.github.akaryc1b.approval.ai.spi;

import java.util.Objects;

/** Provider outcome that distinguishes usable advisory data from every failure class. */
public record AiProviderOutcome(
    AiOutcomeClassification classification,
    AiAdvisoryResult result,
    Failure failure
) {

    public AiProviderOutcome {
        classification = Objects.requireNonNull(
            classification,
            "classification must not be null"
        );
        boolean resultClassification = classification == AiOutcomeClassification.SUCCESS
            || classification == AiOutcomeClassification.LOW_CONFIDENCE;
        if (resultClassification && (result == null || failure != null)) {
            throw new IllegalArgumentException(
                "success and low-confidence outcomes require only a structured result"
            );
        }
        if (!resultClassification && (result != null || failure == null)) {
            throw new IllegalArgumentException(
                "failure outcomes require only structured failure evidence"
            );
        }
    }

    public static AiProviderOutcome success(AiAdvisoryResult result) {
        return new AiProviderOutcome(AiOutcomeClassification.SUCCESS, result, null);
    }

    public static AiProviderOutcome lowConfidence(AiAdvisoryResult result) {
        return new AiProviderOutcome(AiOutcomeClassification.LOW_CONFIDENCE, result, null);
    }

    public static AiProviderOutcome failure(
        AiOutcomeClassification classification,
        String code,
        String message,
        boolean retryable
    ) {
        if (classification == AiOutcomeClassification.SUCCESS
            || classification == AiOutcomeClassification.LOW_CONFIDENCE) {
            throw new IllegalArgumentException("classification is not a failure class");
        }
        return new AiProviderOutcome(
            classification,
            null,
            new Failure(code, message, retryable)
        );
    }

    public boolean hasAdvisoryResult() {
        return result != null;
    }

    public record Failure(String code, String message, boolean retryable) {
        public Failure {
            code = requireText(code, "failure.code", 120);
            message = requireText(message, "failure.message", 1_000);
        }

        private static String requireText(String value, String name, int maximumLength) {
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(name + " must be non-blank and bounded");
            }
            return normalized;
        }
    }
}
