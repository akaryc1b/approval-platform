package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesTransportPortTest {

    @Test
    void requestAndResponseOwnDefensiveRedactionSafeCopies() {
        byte[] requestBytes = "{}".getBytes(StandardCharsets.UTF_8);
        OpenAiResponsesTransportPort.Request request =
            new OpenAiResponsesTransportPort.Request(
                requestBytes,
                OpenAiResponsesProtocol.sha256(requestBytes),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
            );
        requestBytes[0] = 'x';
        byte[] requestCopy = request.bodyCopy();
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), requestCopy);
        requestCopy[0] = 'x';
        assertArrayEquals(
            "{}".getBytes(StandardCharsets.UTF_8),
            request.bodyCopy()
        );
        assertFalse(request.toString().contains("{}"));

        byte[] responseBytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        OpenAiResponsesTransportPort.Response response =
            new OpenAiResponsesTransportPort.Response(
                200,
                "req_private",
                responseBytes
            );
        responseBytes[0] = 'x';
        byte[] responseCopy = response.bodyCopy();
        assertArrayEquals(
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
            responseCopy
        );
        responseCopy[0] = 'x';
        assertArrayEquals(
            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8),
            response.bodyCopy()
        );
        assertFalse(response.toString().contains("req_private"));
        assertFalse(response.toString().contains("{\"ok\":true}"));
    }

    @Test
    void timeoutAndBodyBoundsFailBeforeAnyTransportImplementation() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String hash = OpenAiResponsesProtocol.sha256(body);

        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesTransportPort.Request(
                body,
                hash,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesTransportPort.Request(
                body,
                hash,
                Duration.ofSeconds(2),
                Duration.ofSeconds(16)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new OpenAiResponsesTransportPort.Request(
                body,
                OpenAiResponsesProtocol.sha256Utf8("different"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
            )
        );
    }

    @Test
    void deterministicFakePortIsTestOnlyAndExplicitlySingleCall() {
        assertTrue(OpenAiResponsesTransportPort.class.isInterface());
        AtomicInteger calls = new AtomicInteger();
        OpenAiResponsesTransportPort fake = request -> {
            assertEquals(1, calls.incrementAndGet());
            return new OpenAiResponsesTransportPort.Response(
                200,
                "req_test",
                "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8)
            );
        };

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        OpenAiResponsesTransportPort.Response response = fake.exchange(
            new OpenAiResponsesTransportPort.Request(
                body,
                OpenAiResponsesProtocol.sha256(body),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
            )
        );

        assertEquals(1, calls.get());
        assertEquals(200, response.statusCode());
    }
}
