package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesOutputNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MESSAGE = """
        {"type":"message","id":"msg_1","status":"completed",\
        "role":"assistant","content":[]}
        """.replace("\\\n", "");
    private static final String REASONING = """
        {"type":"reasoning","id":"rs_1","status":"completed",\
        "summary":[{"type":"summary_text","text":"opaque-summary"}],\
        "content":[{"type":"reasoning_text","text":"opaque-reasoning"}],\
        "encrypted_content":"opaque-encrypted"}
        """.replace("\\\n", "");

    @Test
    void statelessReasoningBeforeOrAfterMessageIsAcceptedButNotExposed() throws Exception {
        OpenAiResponsesTransportPort.Response before = response(
            "{\"output\":[" + REASONING + "," + MESSAGE + "]}"
        );
        OpenAiResponsesTransportPort.Response after = response(
            "{\"output\":[" + MESSAGE + "," + REASONING + "]}"
        );

        assertNormalizedMessageOnly(OpenAiResponsesOutputNormalizer.normalize(before));
        assertNormalizedMessageOnly(OpenAiResponsesOutputNormalizer.normalize(after));
    }

    @Test
    void responseWithoutReasoningPreservesOriginalTransportResponse() {
        OpenAiResponsesTransportPort.Response response = response(
            "{\"output\":[" + MESSAGE + "]}"
        );

        assertSame(response, OpenAiResponsesOutputNormalizer.normalize(response));
    }

    @Test
    void reasoningOnlyFailsClosed() {
        assertFailure(
            "{\"output\":[" + REASONING + "]}",
            OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT
        );
    }

    @Test
    void duplicateAssistantMessagesFailClosed() {
        assertFailure(
            "{\"output\":[" + MESSAGE + "," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT
        );
    }

    @Test
    void unknownOutputTypeFailsClosed() {
        assertFailure(
            "{\"output\":[{\"type\":\"tool_call\"}," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT
        );
    }

    @Test
    void unknownReasoningFieldFailsClosed() {
        String reasoning = REASONING.substring(0, REASONING.length() - 1)
            + ",\"unexpected\":true}";
        assertFailure(
            "{\"output\":[" + reasoning + "," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.UNKNOWN_PROPERTY
        );
    }

    @Test
    void incompleteReasoningFailsClosed() {
        String reasoning = REASONING.replace(
            "\"status\":\"completed\"",
            "\"status\":\"in_progress\""
        );
        assertFailure(
            "{\"output\":[" + reasoning + "," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT
        );
    }

    @Test
    void malformedReasoningPartFailsClosed() {
        String reasoning = """
            {"type":"reasoning","id":"rs_1","status":"completed",\
            "summary":[{"type":"summary_text"}],"content":[]}
            """.replace("\\\n", "");
        assertFailure(
            "{\"output\":[" + reasoning + "," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT
        );
    }

    @Test
    void duplicateReasoningPropertyFailsClosed() {
        String reasoning = """
            {"type":"reasoning","id":"rs_1","id":"rs_2",\
            "status":"completed","summary":[],"content":[]}
            """.replace("\\\n", "");
        assertFailure(
            "{\"output\":[" + reasoning + "," + MESSAGE + "]}",
            OpenAiResponsesProtocol.Failure.DUPLICATE_PROPERTY
        );
    }

    private static void assertNormalizedMessageOnly(
        OpenAiResponsesTransportPort.Response normalized
    ) throws Exception {
        String body = new String(normalized.bodyCopy(), StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(body);
        assertEquals(1, root.path("output").size());
        assertEquals("message", root.path("output").get(0).path("type").textValue());
        assertFalse(body.contains("opaque-summary"));
        assertFalse(body.contains("opaque-reasoning"));
        assertFalse(body.contains("opaque-encrypted"));
    }

    private static void assertFailure(
        String body,
        OpenAiResponsesProtocol.Failure expected
    ) {
        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> OpenAiResponsesOutputNormalizer.normalize(response(body))
        );
        assertEquals(expected, failure.failure());
    }

    private static OpenAiResponsesTransportPort.Response response(String body) {
        return new OpenAiResponsesTransportPort.Response(
            200,
            "req_1",
            body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
