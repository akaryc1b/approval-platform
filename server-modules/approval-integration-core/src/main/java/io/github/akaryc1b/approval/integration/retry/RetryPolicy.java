package io.github.akaryc1b.approval.integration.retry;

import java.time.Duration;

/**
 * Retry policy used by asynchronous delivery workers.
 */
public interface RetryPolicy {

    int maxAttempts();

    Duration nextDelay(int attemptNumber);
}
