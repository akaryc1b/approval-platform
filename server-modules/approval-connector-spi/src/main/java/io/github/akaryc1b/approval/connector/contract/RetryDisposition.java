package io.github.akaryc1b.approval.connector.contract;

public enum RetryDisposition {
    DO_NOT_RETRY,
    RETRY_WITH_BACKOFF,
    RECONCILE_BEFORE_RETRY
}
