package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.EventDelivery.DeliveryResult;
import io.github.akaryc1b.approval.sdk.v1.EventDelivery.HandlingOutcome;
import io.github.akaryc1b.approval.sdk.v1.EventDelivery.InMemoryDeduplicationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventDeliveryTest {
    @Test
    void terminalDeliveryIsDeduplicatedAndRetryableRejectionIsNotCommitted() throws Exception {
        Map<String, Object> fixture = FixtureSupport.fixture();
        EventEnvelopeV1 event = new EventEnvelopeCodec().parse(
            CanonicalJson.canonicalizeValue(FixtureSupport.object(fixture, "event"))
        );
        InMemoryDeduplicationStore store = new InMemoryDeduplicationStore();
        AtomicInteger calls = new AtomicInteger();
        assertEquals(
            DeliveryResult.RETRYABLE_REJECTION,
            EventDelivery.consume(event, store, ignored -> {
                calls.incrementAndGet();
                return HandlingOutcome.RETRYABLE_REJECTION;
            })
        );
        assertEquals(
            DeliveryResult.PROCESSED,
            EventDelivery.consume(event, store, ignored -> {
                calls.incrementAndGet();
                return HandlingOutcome.ACCEPTED;
            })
        );
        assertEquals(
            DeliveryResult.DUPLICATE,
            EventDelivery.consume(event, store, ignored -> {
                calls.incrementAndGet();
                return HandlingOutcome.ACCEPTED;
            })
        );
        assertEquals(2, calls.get());
    }

    @Test
    void expiredAndPermanentOutcomesAreTerminal() throws Exception {
        Map<String, Object> fixture = FixtureSupport.fixture();
        EventEnvelopeV1 event = new EventEnvelopeCodec().parse(
            CanonicalJson.canonicalizeValue(FixtureSupport.object(fixture, "event"))
        );
        assertTrue(EventDelivery.expired(
            event,
            Clock.fixed(Instant.parse("2026-07-23T13:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(10)
        ));

        InMemoryDeduplicationStore expiredStore = new InMemoryDeduplicationStore();
        assertEquals(
            DeliveryResult.EXPIRED_EVENT,
            EventDelivery.consume(event, expiredStore, ignored -> HandlingOutcome.EXPIRED_EVENT)
        );
        assertEquals(
            DeliveryResult.DUPLICATE,
            EventDelivery.consume(event, expiredStore, ignored -> HandlingOutcome.ACCEPTED)
        );

        InMemoryDeduplicationStore permanentStore = new InMemoryDeduplicationStore();
        assertEquals(
            DeliveryResult.PERMANENT_REJECTION,
            EventDelivery.consume(
                event,
                permanentStore,
                ignored -> HandlingOutcome.PERMANENT_REJECTION
            )
        );
        assertEquals(
            DeliveryResult.DUPLICATE,
            EventDelivery.consume(event, permanentStore, ignored -> HandlingOutcome.ACCEPTED)
        );
    }
}
