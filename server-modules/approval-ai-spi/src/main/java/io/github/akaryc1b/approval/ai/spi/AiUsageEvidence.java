package io.github.akaryc1b.approval.ai.spi;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** Bounded token, cost and latency evidence without business-content metric labels. */
public record AiUsageEvidence(
    int inputCharacters,
    Long inputTokens,
    Long outputTokens,
    Long totalTokens,
    Long providerLatencyMillis,
    long observedLatencyMillis,
    BigDecimal estimatedCost,
    String currency,
    Source source
) {

    private static final long MAXIMUM_COUNT = 1_000_000_000L;
    private static final long MAXIMUM_LATENCY_MILLIS = 86_400_000L;

    public AiUsageEvidence {
        if (inputCharacters < 0) {
            throw new IllegalArgumentException("inputCharacters must not be negative");
        }
        inputTokens = boundedOptional(inputTokens, "inputTokens", MAXIMUM_COUNT);
        outputTokens = boundedOptional(outputTokens, "outputTokens", MAXIMUM_COUNT);
        totalTokens = boundedOptional(totalTokens, "totalTokens", MAXIMUM_COUNT);
        providerLatencyMillis = boundedOptional(
            providerLatencyMillis,
            "providerLatencyMillis",
            MAXIMUM_LATENCY_MILLIS
        );
        if (observedLatencyMillis < 0 || observedLatencyMillis > MAXIMUM_LATENCY_MILLIS) {
            throw new IllegalArgumentException("observedLatencyMillis must be bounded");
        }
        if (inputTokens != null && outputTokens != null && totalTokens != null
            && totalTokens.longValue() != inputTokens.longValue() + outputTokens.longValue()) {
            throw new IllegalArgumentException("totalTokens must equal inputTokens plus outputTokens");
        }
        if (estimatedCost != null && estimatedCost.signum() < 0) {
            throw new IllegalArgumentException("estimatedCost must not be negative");
        }
        if (estimatedCost == null) {
            if (currency != null && !currency.isBlank()) {
                throw new IllegalArgumentException("currency requires estimatedCost");
            }
            currency = null;
        } else {
            currency = requireCurrency(currency);
        }
        source = Objects.requireNonNull(source, "source must not be null");
        boolean providerValuesPresent = inputTokens != null
            || outputTokens != null
            || totalTokens != null
            || providerLatencyMillis != null
            || estimatedCost != null;
        boolean platformValuesPresent = inputCharacters > 0 || observedLatencyMillis > 0;
        if (source == Source.UNAVAILABLE && (providerValuesPresent || platformValuesPresent)) {
            throw new IllegalArgumentException("unavailable usage evidence must not contain values");
        }
        if (source == Source.PLATFORM_OBSERVED && providerValuesPresent) {
            throw new IllegalArgumentException(
                "platform-observed usage must not claim provider-reported values"
            );
        }
        if (source == Source.PROVIDER_REPORTED && !providerValuesPresent) {
            throw new IllegalArgumentException(
                "provider-reported usage requires provider evidence"
            );
        }
        if (source == Source.MIXED && (!providerValuesPresent || !platformValuesPresent)) {
            throw new IllegalArgumentException(
                "mixed usage requires platform and provider evidence"
            );
        }
    }

    public static AiUsageEvidence unavailable() {
        return new AiUsageEvidence(
            0,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            Source.UNAVAILABLE
        );
    }

    public static AiUsageEvidence platformObserved(
        int inputCharacters,
        long observedLatencyMillis
    ) {
        return new AiUsageEvidence(
            inputCharacters,
            null,
            null,
            null,
            null,
            observedLatencyMillis,
            null,
            null,
            Source.PLATFORM_OBSERVED
        );
    }

    private static Long boundedOptional(Long value, String name, long maximum) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return value;
    }

    private static String requireCurrency(String value) {
        Objects.requireNonNull(value, "currency must not be null when estimatedCost is present");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 8) {
            throw new IllegalArgumentException("currency must be bounded");
        }
        return normalized;
    }

    public enum Source {
        UNAVAILABLE,
        PLATFORM_OBSERVED,
        PROVIDER_REPORTED,
        MIXED
    }
}
