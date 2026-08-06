package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.NOW;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.TENANT_HASH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.allZero;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesPostDispatchUnknownAcceptanceTest {

    @Test
    void timeoutAndIoFailureAfterDispatchRemainSingleAttemptUnknown() throws Exception {
        for (OpenAiResponsesTransportException.Failure injected : List.of(
            OpenAiResponsesTransportException.Failure.TIMEOUT,
            OpenAiResponsesTransportException.Failure.IO_FAILURE
        )) {
            OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture();
            fixture.network().exchangeFailure = injected;

            OpenAiResponsesTransportException failure = assertThrows(
                OpenAiResponsesTransportException.class,
                () -> fixture.sender().exchange(request())
            );

            assertEquals(OpenAiResponsesTransportException.Failure.UNKNOWN, failure.failure());
            assertEquals(1, fixture.network().resolveCount.get());
            assertEquals(1, fixture.network().connectCount.get());
            assertEquals(1, fixture.network().exchangeCount.get());
            assertEquals(1, fixture.environment().secretReads.get());
            assertTrue(allZero(fixture.network().lastSecret));
            var usage = fixture.usage().snapshot(TENANT_HASH, NOW);
            assertEquals(1, usage.committedRequests());
            assertTrue(usage.committedUpperBoundMicros() > 0);
            assertEquals(
                OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
                fixture.circuit().state()
            );
            assertEquals(2, fixture.circuit().generation());
            assertFalse(failure.toString().contains("sk-p7-secret"));
        }
    }

    @Test
    void preDispatchDnsTlsAndSecretFailuresKeepExactClassificationAndZeroUsage()
        throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture dns = fixture();
        dns.network().resolveFailure = OpenAiResponsesTransportException.Failure.DNS_FAILURE;
        assertPreDispatchFailure(dns, OpenAiResponsesTransportException.Failure.DNS_FAILURE);
        assertEquals(0, dns.network().connectCount.get());
        assertEquals(0, dns.environment().secretReads.get());

        OpenAiResponsesP7FaultTestSupport.Fixture tls = fixture();
        tls.network().connectFailure = OpenAiResponsesTransportException.Failure.TLS_FAILURE;
        assertPreDispatchFailure(tls, OpenAiResponsesTransportException.Failure.TLS_FAILURE);
        assertEquals(1, tls.network().connectCount.get());
        assertEquals(0, tls.environment().secretReads.get());

        OpenAiResponsesP7FaultTestSupport.Fixture secret = fixture();
        secret.environment().secret = null;
        assertPreDispatchFailure(
            secret,
            OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE
        );
        assertEquals(1, secret.network().connectCount.get());
        assertEquals(1, secret.environment().secretReads.get());
    }

    @Test
    void connectionDriftBeforeDispatchDoesNotReadSecretOrRecordUsage() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture();
        fixture.network().connectedAddressDrift = true;

        assertPreDispatchFailure(
            fixture,
            OpenAiResponsesTransportException.Failure.CONNECTION_DRIFT
        );

        assertEquals(1, fixture.network().resolveCount.get());
        assertEquals(1, fixture.network().connectCount.get());
        assertEquals(0, fixture.network().exchangeCount.get());
        assertEquals(0, fixture.environment().secretReads.get());
    }

    private static void assertPreDispatchFailure(
        OpenAiResponsesP7FaultTestSupport.Fixture fixture,
        OpenAiResponsesTransportException.Failure expected
    ) {
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> fixture.sender().exchange(request())
        );
        assertEquals(expected, failure.failure());
        assertEquals(0, fixture.network().exchangeCount.get());
        assertEquals(0, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            fixture.circuit().state()
        );
        assertEquals(1, fixture.circuit().generation());
    }
}
