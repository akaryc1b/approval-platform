package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.AuthorizedContext;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.AuthorizedResource;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.InputField;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.MaskingDisposition;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exact canonical validation of the P6-C request bytes before P6-D egress admission. */
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
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
        "capability", "fields", "output_schema", "policy", "prompt_template"
    );
    private static final Set<String> PROMPT_FIELDS = Set.of(
        "content_hash", "id", "version"
    );
    private static final Set<String> POLICY_FIELDS = Set.of(
        "content_hash", "id", "version"
    );
    private static final Set<String> OUTPUT_SCHEMA_FIELDS = Set.of("id", "version");
    private static final Set<String> INPUT_FIELD_FIELDS = Set.of(
        "key", "masking", "type", "value"
    );
    private static final int MAXIMUM_VALUE_DEPTH = 32;
    private static final int MAXIMUM_VALUE_COLLECTION_SIZE = 500;

    private OpenAiResponsesRequestProfileValidator() {
    }

    static int requireExact(OpenAiResponsesTransportPort.Request request) {
        try {
            return validate(request);
        } catch (OpenAiResponsesTransportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure();
        }
    }

    private static int validate(OpenAiResponsesTransportPort.Request request)
        throws IOException {
        if (request == null) {
            throw failure();
        }
        ObjectNode root = parseObject(request.bodyCopy());
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

        String instructions = requireInstructions(root.get("instructions"));
        ParsedPayload payload = requireTextInput(root.get("input"));
        ObjectNode schema = requireStrictFormat(root.get("text"));
        int maximumOutputTokens = requirePositiveInt(
            root.get("max_output_tokens"),
            16_384
        );
        OpenAiResponsesProtocol.OutputLimits limits = outputLimits(schema);

        AiVersionReferences versions = new AiVersionReferences(
            new ProviderVersion(
                OpenAiResponsesProtocol.PROVIDER_ID,
                OpenAiResponsesProtocol.PROVIDER_VERSION
            ),
            new ModelVersion(
                OpenAiResponsesProtocol.PROVIDER_ID,
                OpenAiResponsesProtocol.MODEL_ID,
                OpenAiResponsesProtocol.MODEL_VERSION
            ),
            payload.promptTemplate(),
            KnowledgeSourceVersion.none(),
            payload.policy(),
            payload.outputSchema()
        );
        Set<String> allowedFields = new HashSet<>();
        for (InputField field : payload.fields()) {
            if (!allowedFields.add(field.key())) {
                throw failure();
            }
        }
        AiProviderRequest reconstructed = new AiProviderRequest(
            new AuthorizedContext(
                "validator-tenant",
                "validator-operator",
                "validator-request",
                "validator-trace"
            ),
            new AuthorizedResource(
                "validator-tenant",
                "APPROVAL_TASK",
                "validator-resource",
                "validator-authorization"
            ),
            payload.capability(),
            allowedFields,
            payload.fields(),
            versions,
            request.totalTimeout()
        );
        OpenAiResponsesProtocol.ServerPrompt prompt =
            new OpenAiResponsesProtocol.ServerPrompt(
                payload.promptTemplate().templateId(),
                payload.promptTemplate().version(),
                payload.promptTemplate().contentHash(),
                instructions
            );
        OpenAiResponsesTransportPort.Request canonical =
            new OpenAiResponsesRequestEncoder().encode(
                reconstructed,
                prompt,
                limits,
                maximumOutputTokens
            );
        if (!Arrays.equals(request.bodyCopy(), canonical.bodyCopy())
            || !request.bodyHash().equals(canonical.bodyHash())
            || !request.connectTimeout().equals(canonical.connectTimeout())
            || !request.totalTimeout().equals(canonical.totalTimeout())) {
            throw failure();
        }
        return maximumOutputTokens;
    }

    private static String requireInstructions(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw failure();
        }
        String instructions = value.textValue();
        if (instructions.isBlank() || !instructions.equals(instructions.trim())
            || instructions.length() > OpenAiResponsesProtocol.MAXIMUM_PROMPT_CHARACTERS) {
            throw failure();
        }
        return instructions;
    }

    private static ParsedPayload requireTextInput(JsonNode value) throws IOException {
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
        String payloadText = text(inputText, "text");
        if (!"input_text".equals(text(inputText, "type"))
            || payloadText == null || payloadText.isBlank()
            || payloadText.length() > OpenAiResponsesProtocol.MAXIMUM_REQUEST_BYTES) {
            throw failure();
        }
        return parsePayload(parseObject(payloadText));
    }

    private static ParsedPayload parsePayload(ObjectNode payload) {
        requireExactFields(payload, PAYLOAD_FIELDS);
        AiCapability capability = AiCapability.valueOf(text(payload, "capability"));

        ObjectNode prompt = requireObject(payload.get("prompt_template"));
        requireExactFields(prompt, PROMPT_FIELDS);
        PromptTemplateVersion promptTemplate = new PromptTemplateVersion(
            text(prompt, "id"),
            text(prompt, "version"),
            text(prompt, "content_hash")
        );

        ObjectNode policy = requireObject(payload.get("policy"));
        requireExactFields(policy, POLICY_FIELDS);
        PolicyVersion policyVersion = new PolicyVersion(
            text(policy, "id"),
            text(policy, "version"),
            text(policy, "content_hash")
        );

        ObjectNode outputSchema = requireObject(payload.get("output_schema"));
        requireExactFields(outputSchema, OUTPUT_SCHEMA_FIELDS);
        OutputSchemaVersion outputSchemaVersion = new OutputSchemaVersion(
            text(outputSchema, "id"),
            requirePositiveInt(outputSchema.get("version"), Integer.MAX_VALUE)
        );

        JsonNode fieldNodes = payload.get("fields");
        if (fieldNodes == null || !fieldNodes.isArray()
            || fieldNodes.isEmpty() || fieldNodes.size() > 200) {
            throw failure();
        }
        List<InputField> fields = new ArrayList<>(fieldNodes.size());
        for (JsonNode fieldNode : fieldNodes) {
            ObjectNode field = requireObject(fieldNode);
            requireExactFields(field, INPUT_FIELD_FIELDS);
            fields.add(new InputField(
                text(field, "key"),
                text(field, "type"),
                value(field.get("value"), 1),
                MaskingDisposition.valueOf(text(field, "masking"))
            ));
        }
        return new ParsedPayload(
            capability,
            promptTemplate,
            policyVersion,
            outputSchemaVersion,
            List.copyOf(fields)
        );
    }

    private static ObjectNode requireStrictFormat(JsonNode value) {
        ObjectNode textConfiguration = requireObject(value);
        requireExactFields(textConfiguration, TEXT_CONFIGURATION_FIELDS);
        ObjectNode format = requireObject(textConfiguration.get("format"));
        requireExactFields(format, FORMAT_FIELDS);
        if (!"json_schema".equals(text(format, "type"))
            || !OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME.equals(text(format, "name"))
            || !booleanValue(format, "strict", true)) {
            throw failure();
        }
        return requireObject(format.get("schema"));
    }

    private static OpenAiResponsesProtocol.OutputLimits outputLimits(ObjectNode schema) {
        ObjectNode properties = requireObject(schema.get("properties"));
        return new OpenAiResponsesProtocol.OutputLimits(
            maximumItems(properties, "observations"),
            maximumItems(properties, "riskSignals"),
            maximumItems(properties, "missingMaterials"),
            maximumItems(properties, "recommendations"),
            maximumItems(properties, "evidenceReferences"),
            maximumItems(properties, "limitations")
        );
    }

    private static int maximumItems(ObjectNode properties, String name) {
        ObjectNode array = requireObject(properties.get(name));
        if (!"array".equals(text(array, "type"))) {
            throw failure();
        }
        return requirePositiveInt(array.get("maxItems"), Integer.MAX_VALUE);
    }

    private static Object value(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > MAXIMUM_VALUE_DEPTH) {
            throw failure();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            BigInteger number = node.bigIntegerValue();
            return number;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal number = node.decimalValue();
            return number;
        }
        if (node.isArray()) {
            if (node.size() > MAXIMUM_VALUE_COLLECTION_SIZE) {
                throw failure();
            }
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode entry : node) {
                values.add(value(entry, depth + 1));
            }
            return List.copyOf(values);
        }
        if (node.isObject()) {
            if (node.size() > MAXIMUM_VALUE_COLLECTION_SIZE) {
                throw failure();
            }
            TreeMap<String, Object> values = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                values.put(entry.getKey(), value(entry.getValue(), depth + 1));
            }
            return Map.copyOf(values);
        }
        throw failure();
    }

    private static ObjectNode parseObject(byte[] bytes) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(bytes)) {
            JsonNode parsed = MAPPER.readTree(parser);
            if (!(parsed instanceof ObjectNode object) || parser.nextToken() != null) {
                throw failure();
            }
            return object;
        }
    }

    private static ObjectNode parseObject(String text) throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(text)) {
            JsonNode parsed = MAPPER.readTree(parser);
            if (!(parsed instanceof ObjectNode object) || parser.nextToken() != null) {
                throw failure();
            }
            return object;
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

    private static int requirePositiveInt(JsonNode value, int maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure();
        }
        int number = value.intValue();
        if (number < 1 || number > maximum) {
            throw failure();
        }
        return number;
    }

    private static OpenAiResponsesTransportException failure() {
        return new OpenAiResponsesTransportException(
            OpenAiResponsesTransportException.Failure.REQUEST_INVALID
        );
    }

    private record ParsedPayload(
        AiCapability capability,
        PromptTemplateVersion promptTemplate,
        PolicyVersion policy,
        OutputSchemaVersion outputSchema,
        List<InputField> fields
    ) {
    }
}
