package io.github.akaryc1b.approval.integration.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void growsExponentiallyAndCapsAtMaximum() {
        var policy = new ExponentialBackoffRetryPolicy(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            6,
            0,
            () -> 0.5
        );

        assertEquals(Duration.ofSeconds(1), policy.nextDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.nextDelay(2));
        assertEquals(Duration.ofSeconds(4), policy.nextDelay(3));
        assertEquals(Duration.ofSeconds(5), policy.nextDelay(4));
        assertEquals(Duration.ofSeconds(5), policy.nextDelay(10));
        assertEquals(6, policy.maxAttempts());
    }

    @Test
    void appliesBoundedJitter() {
        var minimum = new ExponentialBackoffRetryPolicy(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            3,
            0.2,
            () -> 0
        );
        var maximum = new ExponentialBackoffRetryPolicy(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            3,
            0.2,
            () -> 1
        );

        assertEquals(Duration.ofSeconds(8), minimum.nextDelay(1));
        assertEquals(Duration.ofSeconds(12), maximum.nextDelay(1));
    }

    @Test
    void rejectsInvalidAttemptNumbers() {
        var policy = new ExponentialBackoffRetryPolicy(
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            3,
            0
        );
        assertThrows(IllegalArgumentException.class, () -> policy.nextDelay(0));
    }
}
