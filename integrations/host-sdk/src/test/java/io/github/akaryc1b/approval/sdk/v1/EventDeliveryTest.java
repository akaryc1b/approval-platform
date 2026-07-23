package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.akaryc1b.approval.sdk.v1.EventDelivery.DeliveryResult;
import io.github.akaryc1b.approval.sdk.v1.EventDelivery.HandlingOutcome;
import io.github.akaryc1b.approval.sdk.v1.EventDelivery.InMemoryDeduplicationStore;
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
}
