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
        double oldestUnfinishedAgeSeconds,
        long notificationPending,
        long notificationDue,
        long notificationInFlight,
        long notificationExpiredLeases,
        long notificationDead,
        double notificationOldestUnfinishedAgeSeconds
    ) {
        public Snapshot {
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            if (pending < 0 || due < 0 || due > pending || inFlight < 0
                || expiredLeases < 0 || expiredLeases > inFlight || dead < 0
                || !Double.isFinite(oldestUnfinishedAgeSeconds) || oldestUnfinishedAgeSeconds < 0
                || (pending == 0 && inFlight == 0 && oldestUnfinishedAgeSeconds != 0)
                || notificationPending < 0 || notificationPending > pending
                || notificationDue < 0 || notificationDue > notificationPending
                || notificationInFlight < 0 || notificationInFlight > inFlight
                || notificationExpiredLeases < 0 || notificationExpiredLeases > notificationInFlight
                || notificationDead < 0 || notificationDead > dead
                || !Double.isFinite(notificationOldestUnfinishedAgeSeconds)
                || notificationOldestUnfinishedAgeSeconds < 0
                || (notificationPending == 0 && notificationInFlight == 0
                    && notificationOldestUnfinishedAgeSeconds != 0)) {
                throw new IllegalArgumentException("invalid Outbox aggregate snapshot");
            }
        }

        public Snapshot(Instant observedAt, long pending, long due, long inFlight,
            long expiredLeases, long dead, double oldestUnfinishedAgeSeconds) {
            this(observedAt, pending, due, inFlight, expiredLeases, dead, oldestUnfinishedAgeSeconds,
                0, 0, 0, 0, 0, 0.0d);
        }
    }
}
