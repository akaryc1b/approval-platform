package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed normalization of stateless Responses output items before strict advisory decoding.
 *
 * <p>Reasoning items are protocol metadata, never application advisory content. This normalizer
 * validates their closed shape and removes them while preserving exactly one assistant message.
 * Every remaining response invariant is still enforced by {@link OpenAiResponsesResponseDecoder}.
 * </p>
 */
final class OpenAiResponsesOutputNormalizer {

    private static final JsonFactory JSON_FACTORY = new JsonFactory()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);
    private static final Set<String> REASONING_FIELDS = Set.of(
        "content",
        "encrypted_content",
        "id",
        "status",
        "summary",
        "type"
    );
    private static final Set<String> REASONING_PART_FIELDS = Set.of("text", "type");
    private static final Set<String> REASONING_PART_TYPES = Set.of(
        "reasoning_text",
        "summary_text",
        "text"
    );
    private static final int MAXIMUM_OUTPUT_ITEMS = 16;
    private static final int MAXIMUM_REASONING_PARTS = 16;
    private static final int MAXIMUM_REASONING_TEXT = 4_096;
    private static final int MAXIMUM_ENCRYPTED_CONTENT = 16_384;

    private OpenAiResponsesOutputNormalizer() {
    }

    static OpenAiResponsesTransportPort.Response normalize(
        OpenAiResponsesTransportPort.Response response
    ) {
        Objects.requireNonNull(response, "response must not be null");
        ObjectNode root = object(parse(decodeUtf8(response.bodyCopy())));
        JsonNode outputValue = root.get("output");
        if (!(outputValue instanceof ArrayNode output)
            || output.isEmpty()
            || output.size() > MAXIMUM_OUTPUT_ITEMS) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }

        ObjectNode message = null;
        boolean reasoningObserved = false;
        for (JsonNode item : output) {
            ObjectNode outputItem = object(item);
            String type = exactText(outputItem.get("type"), 32);
            if ("message".equals(type)) {
                if (message != null) {
                    throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
                }
                message = outputItem;
            } else if ("reasoning".equals(type)) {
                validateReasoning(outputItem);
                reasoningObserved = true;
            } else {
                throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
            }
        }
        if (message == null) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
        if (!reasoningObserved) {
            return response;
        }

        ObjectNode normalized = root.deepCopy();
        normalized.set("output", MAPPER.createArrayNode().add(message.deepCopy()));
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(normalized);
        } catch (JsonProcessingException exception) {
            throw failure(OpenAiResponsesProtocol.Failure.MALFORMED_JSON);
        }
        if (body.length == 0
            || body.length > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES) {
            throw failure(OpenAiResponsesProtocol.Failure.RESPONSE_TOO_LARGE);
        }
        return new OpenAiResponsesTransportPort.Response(
            response.statusCode(),
            response.requestId(),
            body,
            response.transportEvidence()
        );
    }

    private static void validateReasoning(ObjectNode reasoning) {
        requireAllowed(reasoning, REASONING_FIELDS);
        requireExact(reasoning, "type", "reasoning");
        exactText(reasoning.get("id"), 200);
        requireExact(reasoning, "status", "completed");
        validateReasoningParts(reasoning.get("summary"));
        validateReasoningParts(reasoning.get("content"));

        JsonNode encrypted = reasoning.get("encrypted_content");
        if (encrypted != null && !encrypted.isNull()) {
            exactText(encrypted, MAXIMUM_ENCRYPTED_CONTENT);
        }
    }

    private static void validateReasoningParts(JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!(value instanceof ArrayNode parts) || parts.size() > MAXIMUM_REASONING_PARTS) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
        for (JsonNode part : parts) {
            ObjectNode object = object(part);
            requireAllowed(object, REASONING_PART_FIELDS);
            String type = exactText(object.get("type"), 64);
            if (!REASONING_PART_TYPES.contains(type)) {
                throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
            }
            exactText(object.get("text"), MAXIMUM_REASONING_TEXT);
        }
    }

    private static JsonNode parse(String value) {
        try (JsonParser parser = JSON_FACTORY.createParser(value)) {
            JsonNode output = MAPPER.readTree(parser);
            if (output == null || parser.nextToken() != null) {
                throw failure(OpenAiResponsesProtocol.Failure.MALFORMED_JSON);
            }
            return output;
        } catch (OpenAiResponsesProtocol.ProtocolException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            if (exception.getOriginalMessage() != null
                && exception.getOriginalMessage().contains("Duplicate field")) {
                throw failure(OpenAiResponsesProtocol.Failure.DUPLICATE_PROPERTY);
            }
            throw failure(OpenAiResponsesProtocol.Failure.MALFORMED_JSON);
        } catch (IOException exception) {
            throw failure(OpenAiResponsesProtocol.Failure.MALFORMED_JSON);
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            throw failure(OpenAiResponsesProtocol.Failure.INVALID_UTF8);
        }
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
        return object;
    }

    private static void requireAllowed(ObjectNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw failure(OpenAiResponsesProtocol.Failure.UNKNOWN_PROPERTY);
            }
        }
    }

    private static void requireExact(ObjectNode object, String name, String expected) {
        if (!expected.equals(exactText(object.get(name), Math.max(32, expected.length())))) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
    }

    private static String exactText(JsonNode value, int maximumLength) {
        if (value == null || !value.isTextual()) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
        String text = value.textValue();
        if (text == null
            || text.isBlank()
            || text.length() > maximumLength
            || !text.equals(text.trim())) {
            throw failure(OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
        }
        return text;
    }

    private static OpenAiResponsesProtocol.ProtocolException failure(
        OpenAiResponsesProtocol.Failure failure
    ) {
        return OpenAiResponsesProtocol.failure(failure);
    }
}
