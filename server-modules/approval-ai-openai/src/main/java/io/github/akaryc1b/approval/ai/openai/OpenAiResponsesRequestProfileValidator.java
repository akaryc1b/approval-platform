package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Exact structural validation of the P6-C encoded stateless request before P6-D admission. */
final class OpenAiResponsesRequestProfileValidator {

    private static final JsonFactory JSON_FACTORY = new JsonFactory()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);
    private static final Set<String> REQUEST_FIELDS = Set.of(
        "background", "input", "instructions", "max_output_tokens",
        "model", "store", "stream", "text", "tool_choice", "tools",
        "truncation"
    );
    private static final Set<String> INPUT_MESSAGE_FIELDS = Set.of("content", "role");
    private static final Set<String> INPUT_CONTENT_FIELDS = Set.of("text", "type");
    private static final Set<String> TEXT_CONFIGURATION_FIELDS = Set.of("format");
    private static final Set<String> FORMAT_FIELDS = Set.of(
        "name", "schema", "strict", "type"
    );

    private OpenAiResponsesRequestProfileValidator() {
    }

    static int requireExact(OpenAiResponsesTransportPort.Request request) {
        ObjectNode root;
        try (JsonParser parser = JSON_FACTORY.createParser(request.bodyCopy())) {
            JsonNode parsed = MAPPER.readTree(parser);
            if (!(parsed instanceof ObjectNode object) || parser.nextToken() != null) {
                throw failure();
            }
            root = object;
        } catch (OpenAiResponsesTransportException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure();
        }
        requireExactFields(root, REQUEST_FIELDS);
        if (!OpenAiResponsesProtocol.MODEL_SNAPSHOT.equals(text(root, "model"))
            || !booleanValue(root, "store", false)
            || !booleanValue(root, "background", false)
            || !booleanValue(root, "stream", false)
            || !"none".equals(text(root, "tool_choice"))
            || !"disabled".equals(text(root, "truncation"))
            || !root.path("tools").isArray()
            || !root.path("tools").isEmpty()) {
            throw failure();
        }
        requireInstructions(root.get("instructions"));
        requireTextInput(root.get("input"));
        requireStrictFormat(root.get("text"));
        JsonNode tokens = root.get("max_output_tokens");
        if (tokens == null || !tokens.isIntegralNumber() || !tokens.canConvertToInt()) {
            throw failure();
        }
        int maximumOutputTokens = tokens.intValue();
        if (maximumOutputTokens < 1 || maximumOutputTokens > 16_384) {
            throw failure();
        }
        return maximumOutputTokens;
    }

    private static void requireInstructions(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw failure();
        }
        String instructions = value.textValue();
        if (instructions.isBlank() || !instructions.equals(instructions.trim())
            || instructions.length() > OpenAiResponsesProtocol.MAXIMUM_PROMPT_CHARACTERS) {
            throw failure();
        }
    }

    private static void requireTextInput(JsonNode value) {
        if (value == null || !value.isArray() || value.size() != 1) {
            throw failure();
        }
        ObjectNode message = requireObject(value.get(0));
        requireExactFields(message, INPUT_MESSAGE_FIELDS);
        if (!"user".equals(text(message, "role"))) {
            throw failure();
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isArray() || content.size() != 1) {
            throw failure();
        }
        ObjectNode inputText = requireObject(content.get(0));
        requireExactFields(inputText, INPUT_CONTENT_FIELDS);
        String payload = text(inputText, "text");
        if (!"input_text".equals(text(inputText, "type"))
            || payload == null || payload.isBlank()
            || payload.length() > OpenAiResponsesProtocol.MAXIMUM_REQUEST_BYTES) {
            throw failure();
        }
    }

    private static void requireStrictFormat(JsonNode value) {
        ObjectNode textConfiguration = requireObject(value);
        requireExactFields(textConfiguration, TEXT_CONFIGURATION_FIELDS);
        ObjectNode format = requireObject(textConfiguration.get("format"));
        requireExactFields(format, FORMAT_FIELDS);
        if (!"json_schema".equals(text(format, "type"))
            || !OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME.equals(text(format, "name"))
            || !booleanValue(format, "strict", true)
            || !format.path("schema").isObject()) {
            throw failure();
        }
    }

    private static ObjectNode requireObject(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            throw failure();
        }
        return object;
    }

    private static void requireExactFields(ObjectNode object, Set<String> expected) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        if (!names.equals(expected)) {
            throw failure();
        }
    }

    private static String text(ObjectNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean booleanValue(ObjectNode object, String name, boolean expected) {
        JsonNode value = object.get(name);
        return value != null && value.isBoolean() && value.booleanValue() == expected;
    }

    private static OpenAiResponsesTransportException failure() {
        return new OpenAiResponsesTransportException(
            OpenAiResponsesTransportException.Failure.REQUEST_INVALID
        );
    }
}
