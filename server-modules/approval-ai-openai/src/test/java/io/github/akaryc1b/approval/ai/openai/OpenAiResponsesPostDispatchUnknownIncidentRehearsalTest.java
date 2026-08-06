package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.NOW;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.TENANT_HASH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesPostDispatchUnknownIncidentRehearsalTest {

    @Test
    void scenario9PostDispatchUnknownRemainsSingleAttemptAuditableAndNonRetryable()
        throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture();
        fixture.network().exchangeFailure = OpenAiResponsesTransportException.Failure.TIMEOUT;

        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.sender().exchange(request())
        );

        assertEquals(OpenAiResponsesTransportException.Failure.UNKNOWN, failure.failure());
        assertEquals(1, fixture.network().resolveCount.get());
        assertEquals(1, fixture.network().connectCount.get());
        assertEquals(1, fixture.network().exchangeCount.get());
        assertEquals(1, fixture.environment().secretReads.get());
        var usage = fixture.usage().snapshot(TENANT_HASH, NOW);
        assertEquals(1, usage.committedRequests());
        assertTrue(usage.committedUpperBoundMicros() > 0);
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            fixture.circuit().state()
        );
        assertEquals(2, fixture.circuit().generation());
        assertFalse(failure.retryable());
        assertFalse(failure.toString().contains("sk-p7-secret"));
    }
}
