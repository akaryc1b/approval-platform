package io.github.akaryc1b.approval.integration.outbox;

import java.util.UUID;

/**
 * Optional local telemetry for an already claimed delivery attempt. Implementations must
 * not perform business writes, external delivery or synchronous telemetry network calls.
 * No payload, credentials, tenant or recipient information crosses this boundary.
 */
@FunctionalInterface
public interface OutboxDispatchObserver {

    OutboxDispatchObserver NOOP = eventId -> Attempt.NOOP;

    Attempt start(UUID eventId);

    enum Outcome {
        DELIVERED,
        RESCHEDULED,
        DEAD,
        LEASE_LOST,
        FAILED
    }

    interface Attempt extends AutoCloseable {
        Attempt NOOP = new Attempt() {
            @Override
            public void outcome(Outcome outcome) {
            }

            @Override
            public void close() {
            }
        };

        void outcome(Outcome outcome);

        @Override
        void close();
    }
}
