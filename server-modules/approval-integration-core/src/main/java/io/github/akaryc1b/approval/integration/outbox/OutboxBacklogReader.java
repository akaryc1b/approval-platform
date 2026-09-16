package io.github.akaryc1b.approval.integration.outbox;

import java.time.Instant;
import java.util.Objects;

/** Deployment-wide aggregates for private operational monitoring, never tenant-facing data. */
@FunctionalInterface
public interface OutboxBacklogReader {

    Snapshot read();

    /** Due and expired leases are subsets, not additional messages. Dead messages are terminal. */
    record Snapshot(
        Instant observedAt,
        long pending,
        long due,
        long inFlight,
        long expiredLeases,
        long dead,
        double oldestUnfinishedAgeSeconds
    ) {
        public Snapshot {
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            if (pending < 0 || due < 0 || due > pending || inFlight < 0
                || expiredLeases < 0 || expiredLeases > inFlight || dead < 0
                || !Double.isFinite(oldestUnfinishedAgeSeconds) || oldestUnfinishedAgeSeconds < 0
                || (pending == 0 && inFlight == 0 && oldestUnfinishedAgeSeconds != 0)) {
                throw new IllegalArgumentException("invalid Outbox aggregate snapshot");
            }
        }
    }
}
