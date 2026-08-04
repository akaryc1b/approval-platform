package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.Fixture;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.allZero;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.assertFailure;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.killSwitch;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesSecureHttpSenderTest {

    @Test
    void exactSenderUsesOneVerifiedChannelAndZeroizesScopedSecret() throws Exception {
        Fixture fixture = fixture(200, "req_success", "{\"ok\":true}");

        OpenAiResponsesTransportPort.Response response = fixture.sender().exchange(request());

        assertEquals(200, response.statusCode());
        assertEquals("req_success", response.requestId());
        assertEquals(1, fixture.network().resolveCount.get());
        assertEquals(1, fixture.network().connectCount.get());
        assertEquals(1, fixture.network().exchangeCount.get());
        assertEquals(1, fixture.environment().secretReads.get());
        assertEquals(1, fixture.environment().versionReads.get());
        assertTrue(allZero(fixture.network().lastSecret));
        assertTrue(response.transportEvidence().verified());
        assertEquals(1, response.transportEvidence().attemptCount());
        assertFalse(response.toString().contains("req_success"));
        assertFalse(response.toString().contains("sk-test-value"));
        assertFalse(fixture.sender().toString().contains(OpenAiResponsesEndpointPolicy.HOST));
    }

    @Test
    void killSwitchDriftAfterTlsBlocksBeforeSecretLeaseAndDispatch() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        fixture.network().afterConnect = () -> fixture.killSwitch().set(killSwitch(true, 8));

        assertFailure(
            fixture,
            OpenAiResponsesTransportException.Failure.KILL_SWITCH_DRIFT
        );
        assertEquals(1, fixture.network().resolveCount.get());
        assertEquals(1, fixture.network().connectCount.get());
        assertEquals(0, fixture.network().exchangeCount.get());
        assertEquals(0, fixture.environment().secretReads.get());
        assertEquals(0, fixture.environment().versionReads.get());
    }

    @Test
    void unsafeDnsEvidenceFailsBeforeTlsSecretAndDispatch() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        fixture.network().unsafeResolution = true;

        assertFailure(
            fixture,
            OpenAiResponsesTransportException.Failure.DNS_UNSAFE
        );
        assertEquals(1, fixture.network().resolveCount.get());
        assertEquals(0, fixture.network().connectCount.get());
        assertEquals(0, fixture.network().exchangeCount.get());
        assertEquals(0, fixture.environment().secretReads.get());
    }

    @Test
    void redirectsAreRejectedAfterExactlyOneAttempt() throws Exception {
        Fixture fixture = fixture(302, "req_redirect", "redirect-body");

        assertFailure(
            fixture,
            OpenAiResponsesTransportException.Failure.REDIRECT_REJECTED
        );
        assertEquals(1, fixture.network().exchangeCount.get());
        assertTrue(allZero(fixture.network().lastSecret));
    }

    @Test
    void missingSecretFailsAfterVerifiedTlsWithoutDispatch() throws Exception {
        Fixture fixture = fixture(200, "req_unused", "{}");
        fixture.environment().secret = null;

        assertFailure(
            fixture,
            OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE
        );
        assertEquals(1, fixture.network().connectCount.get());
        assertEquals(0, fixture.network().exchangeCount.get());
        assertEquals(1, fixture.environment().secretReads.get());
    }

    @Test
    void providerHttpFailureRemainsBoundedAndSingleAttempt() throws Exception {
        Fixture fixture = fixture(429, "req_rate", "sensitive-provider-error");

        OpenAiResponsesTransportPort.Response response = fixture.sender().exchange(request());

        assertEquals(429, response.statusCode());
        assertEquals(1, fixture.network().exchangeCount.get());
        assertArrayEquals(
            "sensitive-provider-error".getBytes(StandardCharsets.UTF_8),
            response.bodyCopy()
        );
        assertFalse(response.toString().contains("sensitive-provider-error"));
        assertTrue(response.transportEvidence().verified());
    }
}
