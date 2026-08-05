package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesHttpFramingTest {

    @Test
    void contentLengthResponseIsParsedExactly() throws Exception {
        OpenAiResponsesNetworkSupport.ExchangeResult result = parse(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "Content-Encoding: identity\r\n"
                + "X-Request-Id: req_fixture\r\n"
                + "Content-Type: application/json\r\n"
                + "\r\n{}"
        );

        assertEquals(200, result.statusCode());
        assertEquals("req_fixture", result.requestId());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), result.bodyCopy());
    }

    @Test
    void chunkedFailureResponseRemainsBounded() throws Exception {
        OpenAiResponsesNetworkSupport.ExchangeResult result = parse(
            "HTTP/1.1 429 Too Many Requests\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "X-Request-Id: req_rate\r\n"
                + "\r\n"
                + "4\r\nrate\r\n"
                + "0\r\n\r\n"
        );

        assertEquals(429, result.statusCode());
        assertArrayEquals(
            "rate".getBytes(StandardCharsets.UTF_8),
            result.bodyCopy()
        );
    }

    @Test
    void redirectsAreRejectedAtTheResponseBoundary() {
        assertFailure(
            "HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n",
            OpenAiResponsesTransportException.Failure.REDIRECT_REJECTED
        );
    }

    @Test
    void duplicateOrAmbiguousLengthHeadersFailClosed() {
        assertFailure(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "Content-Length: 2\r\n\r\n{}",
            OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
        );
        assertFailure(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n{}",
            OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
        );
    }

    @Test
    void whitespaceBeforeColonAndControlCharactersFailClosed() {
        assertFailure(
            "HTTP/1.1 200 OK\r\nContent-Length : 2\r\n\r\n{}",
            OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
        );
        assertFailure(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "X-Ambiguous: bad\u0000value\r\n\r\n{}",
            OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
        );
    }

    @Test
    void oversizedOrCompressedBodiesFailClosedBeforeAllocation() {
        assertFailure(
            "HTTP/1.1 200 OK\r\nContent-Length: 524289\r\n\r\n",
            OpenAiResponsesTransportException.Failure.RESPONSE_TOO_LARGE
        );
        assertFailure(
            "HTTP/1.1 200 OK\r\n"
                + "Content-Length: 2\r\n"
                + "Content-Encoding: gzip\r\n\r\n{}",
            OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
        );
    }

    private static OpenAiResponsesNetworkSupport.ExchangeResult parse(
        String response
    ) throws Exception {
        return OpenAiResponsesHttpCodec.readResponse(
            new ByteArrayInputStream(response.getBytes(StandardCharsets.US_ASCII)),
            request(),
            "client-fixture-1",
            OpenAiResponsesNetworkSupport.Deadline.start(Duration.ofSeconds(5))
        );
    }

    private static void assertFailure(
        String response,
        OpenAiResponsesTransportException.Failure expected
    ) {
        OpenAiResponsesTransportException failure = assertThrows(
            OpenAiResponsesTransportException.class,
            () -> parse(response)
        );
        assertEquals(expected, failure.failure());
    }

    private static OpenAiResponsesTransportPort.Request request() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        return new OpenAiResponsesTransportPort.Request(
            body,
            OpenAiResponsesProtocol.sha256(body),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5)
        );
    }
}
