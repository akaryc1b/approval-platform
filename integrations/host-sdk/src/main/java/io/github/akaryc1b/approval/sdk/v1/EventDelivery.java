package io.github.akaryc1b.approval.sdk.v1;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** At-least-once delivery decisions and an idempotent consumer helper. */
public final class EventDelivery {
    private EventDelivery() {
    }

    public enum HandlingOutcome {
        ACCEPTED,
        RETRYABLE_REJECTION,
        PERMANENT_REJECTION,
        EXPIRED_EVENT,
        UNSUPPORTED_SCHEMA_VERSION
    }

    public enum DeliveryResult {
        PROCESSED,
        DUPLICATE,
        RETRYABLE_REJECTION,
        PERMANENT_REJECTION,
        EXPIRED_EVENT,
        UNSUPPORTED_SCHEMA_VERSION
    }

    @FunctionalInterface
    public interface Handler {
        HandlingOutcome handle(EventEnvelopeV1 event);
    }

    public interface DeduplicationStore {
        boolean contains(String eventId);

        void markTerminal(String eventId);
    }

    public static DeliveryResult consume(
        EventEnvelopeV1 event,
        DeduplicationStore deduplicationStore,
        Handler handler
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(deduplicationStore, "deduplicationStore");
        Objects.requireNonNull(handler, "handler");
        if (deduplicationStore.contains(event.eventId())) {
            return DeliveryResult.DUPLICATE;
        }
        HandlingOutcome outcome = Objects.requireNonNull(handler.handle(event), "handler outcome");
        return switch (outcome) {
            case ACCEPTED -> terminal(deduplicationStore, event.eventId(), DeliveryResult.PROCESSED);
            case RETRYABLE_REJECTION -> DeliveryResult.RETRYABLE_REJECTION;
            case PERMANENT_REJECTION -> terminal(
                deduplicationStore,
                event.eventId(),
                DeliveryResult.PERMANENT_REJECTION
            );
            case EXPIRED_EVENT -> terminal(
                deduplicationStore,
                event.eventId(),
                DeliveryResult.EXPIRED_EVENT
            );
            case UNSUPPORTED_SCHEMA_VERSION -> terminal(
                deduplicationStore,
                event.eventId(),
                DeliveryResult.UNSUPPORTED_SCHEMA_VERSION
            );
        };
    }

    private static DeliveryResult terminal(
        DeduplicationStore store,
        String eventId,
        DeliveryResult result
    ) {
        store.markTerminal(eventId);
        return result;
    }

    public static boolean expired(EventEnvelopeV1 event, Clock clock, Duration maximumAge) {
        Objects.requireNonNull(maximumAge, "maximumAge");
        return event.occurredAt().plus(maximumAge).isBefore(clock.instant());
    }

    public static final class InMemoryDeduplicationStore implements DeduplicationStore {
        private final Map<String, Boolean> terminalEvents = new ConcurrentHashMap<>();

        @Override
        public boolean contains(String eventId) {
            return terminalEvents.containsKey(eventId);
        }

        @Override
        public void markTerminal(String eventId) {
            terminalEvents.put(eventId, Boolean.TRUE);
        }
    }
}
