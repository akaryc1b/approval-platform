package io.github.akaryc1b.approval.integration.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with bounded symmetric jitter.
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final Duration initialDelay;
    private final Duration maximumDelay;
    private final int maximumAttempts;
    private final double jitterRatio;
    private final DoubleSupplier randomSample;

    public ExponentialBackoffRetryPolicy(
        Duration initialDelay,
        Duration maximumDelay,
        int maximumAttempts,
        double jitterRatio,
        DoubleSupplier randomSample
    ) {
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must not be shorter than initialDelay");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be at least 1");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
        this.maximumAttempts = maximumAttempts;
        this.jitterRatio = jitterRatio;
        this.randomSample = Objects.requireNonNull(randomSample, "randomSample must not be null");
    }

    public ExponentialBackoffRetryPolicy(
        Duration initialDelay,
        Duration maximumDelay,
        int maximumAttempts,
        double jitterRatio
    ) {
        this(initialDelay, maximumDelay, maximumAttempts, jitterRatio, Math::random);
    }

    @Override
    public int maxAttempts() {
        return maximumAttempts;
    }

    @Override
    public Duration nextDelay(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be at least 1");
        }
        long initialMillis = initialDelay.toMillis();
        int exponent = Math.min(attemptNumber - 1, 62);
        long multiplier = 1L << exponent;
        long exponential;
        try {
            exponential = Math.multiplyExact(initialMillis, multiplier);
        } catch (ArithmeticException exception) {
            exponential = Long.MAX_VALUE;
        }
        long capped = Math.min(exponential, maximumDelay.toMillis());
        double sample = randomSample.getAsDouble();
        if (sample < 0 || sample > 1) {
            throw new IllegalStateException("random sample must be between 0 and 1");
        }
        double factor = 1 - jitterRatio + (2 * jitterRatio * sample);
        long jittered = Math.max(1, Math.round(capped * factor));
        return Duration.ofMillis(Math.min(jittered, maximumDelay.toMillis()));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
