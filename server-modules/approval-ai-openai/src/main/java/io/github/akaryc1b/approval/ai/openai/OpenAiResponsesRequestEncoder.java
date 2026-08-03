package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.REQUEST_INVALID;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol.Failure.REQUEST_TOO_LARGE;

/**
 * Deterministic P6-C encoder for the exact OpenAI Responses request profile.
 *
 * <p>The encoder includes only Provider-safe fields and exact server-owned version evidence. It
 * never serializes tenant, operator, task, instance, authorization-reference or Secret data.</p>
 */
public final class OpenAiResponsesRequestEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Duration MAXIMUM_TOTAL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAXIMUM_OUTPUT_TOKENS = 16_384;
    private static final int MAXIMUM_VALUE_DEPTH = 32;

    public OpenAiResponsesTransportPort.Request encode(
        AiProviderRequest request,
        OpenAiResponsesProtocol.ServerPrompt prompt,
        OpenAiResponsesProtocol.OutputLimits limits,
        int maximumOutputTokens
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        requireExactProfile(request, prompt);
        if (maximumOutputTokens < 1 || maximumOutputTokens > MAXIMUM_OUTPUT_TOKENS) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }
        if (request.timeout().compareTo(MAXIMUM_TOTAL_TIMEOUT) > 0) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }

        ObjectNode root = JSON.objectNode();
        root.put("model", OpenAiResponsesProtocol.MODEL_SNAPSHOT);
        root.put("store", false);
        root.put("background", false);
        root.put("stream", false);
        root.set("tools", JSON.arrayNode());
        root.put("tool_choice", "none");
        root.put("truncation", "disabled");
        root.put("max_output_tokens", maximumOutputTokens);
        root.put("instructions", prompt.instructions());
        root.set("input", input(request));
        root.set("text", textConfiguration(request.versions(), limits));

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException failure) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }
        if (body.length == 0
            || body.length > OpenAiResponsesProtocol.MAXIMUM_REQUEST_BYTES) {
            throw OpenAiResponsesProtocol.failure(REQUEST_TOO_LARGE);
        }

        Duration connectTimeout = request.timeout().compareTo(MAXIMUM_CONNECT_TIMEOUT) < 0
            ? request.timeout()
            : MAXIMUM_CONNECT_TIMEOUT;
        return new OpenAiResponsesTransportPort.Request(
            body,
            OpenAiResponsesProtocol.sha256(body),
            connectTimeout,
            request.timeout()
        );
    }

    private static ArrayNode input(AiProviderRequest request) {
        ObjectNode payload = JSON.objectNode();
        payload.put("capability", request.capability().name());
        payload.set("prompt_template", promptTemplate(request.versions().promptTemplate()));
        payload.set("policy", policy(request.versions().policy()));
        payload.set("output_schema", outputSchema(request.versions().outputSchema()));

        ArrayNode fields = JSON.arrayNode();
        request.inputFields().stream()
            .sorted(Comparator.comparing(AiProviderRequest.InputField::key))
            .map(OpenAiResponsesRequestEncoder::field)
            .forEach(fields::add);
        payload.set("fields", fields);

        String payloadText;
        try {
            payloadText = MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }

        ObjectNode content = JSON.objectNode();
        content.put("type", "input_text");
        content.put("text", payloadText);

        ObjectNode message = JSON.objectNode();
        message.put("role", "user");
        message.set("content", JSON.arrayNode().add(content));
        return JSON.arrayNode().add(message);
    }

    private static ObjectNode field(AiProviderRequest.InputField field) {
        ObjectNode encoded = JSON.objectNode();
        encoded.put("key", field.key());
        encoded.put("type", field.type());
        encoded.put("masking", field.maskingDisposition().name());
        encoded.set("value", value(field.value(), 1));
        return encoded;
    }

    private static JsonNode value(Object value, int depth) {
        if (value == null || depth > MAXIMUM_VALUE_DEPTH) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }
        if (value instanceof String text) {
            return JSON.textNode(text);
        }
        if (value instanceof Boolean bool) {
            return BooleanNode.valueOf(bool);
        }
        if (value instanceof Byte number) {
            return JSON.numberNode(number.intValue());
        }
        if (value instanceof Short number) {
            return JSON.numberNode(number.intValue());
        }
        if (value instanceof Integer number) {
            return JSON.numberNode(number);
        }
        if (value instanceof Long number) {
            return JSON.numberNode(number);
        }
        if (value instanceof BigInteger number) {
            return JSON.numberNode(number);
        }
        if (value instanceof BigDecimal number) {
            return DecimalNode.valueOf(number);
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
            }
            return DecimalNode.valueOf(BigDecimal.valueOf(number.doubleValue()));
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
            }
            return DecimalNode.valueOf(BigDecimal.valueOf(number));
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || sorted.put(key, entry.getValue()) != null) {
                    throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
                }
            }
            ObjectNode object = JSON.objectNode();
            sorted.forEach((key, entry) -> object.set(key, value(entry, depth + 1)));
            return object;
        }
        if (value instanceof Collection<?> collection) {
            ArrayNode array = JSON.arrayNode();
            for (Object entry : collection) {
                array.add(value(entry, depth + 1));
            }
            return array;
        }
        throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
    }

    private static ObjectNode promptTemplate(PromptTemplateVersion prompt) {
        ObjectNode value = JSON.objectNode();
        value.put("id", prompt.templateId());
        value.put("version", prompt.version());
        value.put("content_hash", prompt.contentHash());
        return value;
    }

    private static ObjectNode policy(AiVersionReferences.PolicyVersion policy) {
        ObjectNode value = JSON.objectNode();
        value.put("id", policy.policyId());
        value.put("version", policy.version());
        value.put("content_hash", policy.contentHash());
        return value;
    }

    private static ObjectNode outputSchema(OutputSchemaVersion outputSchema) {
        ObjectNode value = JSON.objectNode();
        value.put("id", outputSchema.schemaId());
        value.put("version", outputSchema.version());
        return value;
    }

    private static ObjectNode textConfiguration(
        AiVersionReferences versions,
        OpenAiResponsesProtocol.OutputLimits limits
    ) {
        ObjectNode format = JSON.objectNode();
        format.put("type", "json_schema");
        format.put("name", OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME);
        format.put("strict", true);
        format.set("schema", resultSchema(versions, limits));

        ObjectNode text = JSON.objectNode();
        text.set("format", format);
        return text;
    }

    private static ObjectNode resultSchema(
        AiVersionReferences versions,
        OpenAiResponsesProtocol.OutputLimits limits
    ) {
        Map<String, JsonNode> properties = new TreeMap<>();
        properties.put("assertionStatus", enumString("UNVERIFIED_ADVISORY"));
        properties.put("authority", enumString("ADVISORY"));
        properties.put("confidence", confidenceSchema());
        properties.put(
            "evidenceReferences",
            arraySchema(evidenceReferenceSchema(), 1, limits.maximumEvidenceReferences())
        );
        properties.put(
            "limitations",
            arraySchema(stringSchema(1, 1_000), 1, limits.maximumLimitations())
        );
        properties.put(
            "missingMaterials",
            arraySchema(missingMaterialSchema(), 0, limits.maximumMissingMaterials())
        );
        properties.put("needsHumanReview", BooleanNode.TRUE);
        properties.put(
            "observations",
            arraySchema(observationSchema(), 0, limits.maximumObservations())
        );
        properties.put(
            "recommendations",
            arraySchema(recommendationSchema(), 0, limits.maximumRecommendations())
        );
        properties.put(
            "riskSignals",
            arraySchema(riskSignalSchema(), 0, limits.maximumRiskSignals())
        );
        properties.put("summary", stringSchema(1, 4_000));
        properties.put("versions", versionsSchema(versions));
        return objectSchema(properties);
    }

    private static ObjectNode observationSchema() {
        return objectSchema(Map.of(
            "evidenceReferenceIds", arraySchema(stringSchema(1, 120), 1, 64),
            "id", stringSchema(1, 120),
            "text", stringSchema(1, 2_000)
        ));
    }

    private static ObjectNode riskSignalSchema() {
        return objectSchema(Map.of(
            "evidenceReferenceIds", arraySchema(stringSchema(1, 120), 1, 64),
            "id", stringSchema(1, 120),
            "severity", enumString("INFO", "LOW", "MEDIUM", "HIGH"),
            "text", stringSchema(1, 2_000)
        ));
    }

    private static ObjectNode missingMaterialSchema() {
        return objectSchema(Map.of(
            "id", stringSchema(1, 120),
            "materialType", stringSchema(1, 160),
            "reason", stringSchema(1, 2_000)
        ));
    }

    private static ObjectNode recommendationSchema() {
        return objectSchema(Map.of(
            "evidenceReferenceIds", arraySchema(stringSchema(1, 120), 1, 64),
            "id", stringSchema(1, 120),
            "text", stringSchema(1, 2_000),
            "type", enumString(
                "REQUEST_INFORMATION",
                "VERIFY_EVIDENCE",
                "REVIEW_RISK",
                "SEEK_SPECIALIST_REVIEW",
                "NO_ACTION_SUGGESTED"
            )
        ));
    }

    private static ObjectNode evidenceReferenceSchema() {
        return objectSchema(Map.of(
            "description", stringSchema(1, 1_000),
            "fieldKey", stringSchema(1, 160),
            "id", stringSchema(1, 120)
        ));
    }

    private static ObjectNode confidenceSchema() {
        ObjectNode score = JSON.objectNode();
        score.put("type", "number");
        score.put("minimum", 0.0d);
        score.put("maximum", 1.0d);
        return objectSchema(Map.of(
            "band", enumString("LOW", "MEDIUM", "HIGH"),
            "score", score
        ));
    }

    private static ObjectNode versionsSchema(AiVersionReferences versions) {
        return objectSchema(Map.of(
            "knowledgeSource",
            objectSchema(Map.of(
                "containsCustomerData",
                BooleanNode.valueOf(versions.knowledgeSource().containsCustomerData()),
                "contentHash",
                enumString(versions.knowledgeSource().contentHash()),
                "sourceId",
                enumString(versions.knowledgeSource().sourceId()),
                "version",
                enumString(versions.knowledgeSource().version())
            )),
            "model",
            objectSchema(Map.of(
                "modelId", enumString(versions.model().modelId()),
                "providerId", enumString(versions.model().providerId()),
                "version", enumString(versions.model().version())
            )),
            "outputSchema",
            objectSchema(Map.of(
                "schemaId", enumString(versions.outputSchema().schemaId()),
                "version", integerConstant(versions.outputSchema().version())
            )),
            "policy",
            objectSchema(Map.of(
                "contentHash", enumString(versions.policy().contentHash()),
                "policyId", enumString(versions.policy().policyId()),
                "version", enumString(versions.policy().version())
            )),
            "promptTemplate",
            objectSchema(Map.of(
                "contentHash", enumString(versions.promptTemplate().contentHash()),
                "templateId", enumString(versions.promptTemplate().templateId()),
                "version", enumString(versions.promptTemplate().version())
            )),
            "provider",
            objectSchema(Map.of(
                "providerId", enumString(versions.provider().providerId()),
                "version", enumString(versions.provider().version())
            ))
        ));
    }

    private static ObjectNode objectSchema(Map<String, JsonNode> values) {
        ObjectNode properties = JSON.objectNode();
        List<String> required = new ArrayList<>(values.keySet());
        required.sort(String::compareTo);
        for (String name : required) {
            properties.set(name, values.get(name));
        }

        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.valueToTree(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode arraySchema(
        JsonNode items,
        int minimumItems,
        int maximumItems
    ) {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "array");
        schema.set("items", items);
        schema.put("minItems", minimumItems);
        schema.put("maxItems", maximumItems);
        return schema;
    }

    private static ObjectNode stringSchema(int minimumLength, int maximumLength) {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "string");
        schema.put("minLength", minimumLength);
        schema.put("maxLength", maximumLength);
        return schema;
    }

    private static ObjectNode enumString(String... values) {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "string");
        ArrayNode enumeration = JSON.arrayNode();
        for (String value : values) {
            enumeration.add(value);
        }
        schema.set("enum", enumeration);
        return schema;
    }

    private static ObjectNode integerConstant(int value) {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "integer");
        schema.put("minimum", value);
        schema.put("maximum", value);
        return schema;
    }

    private static void requireExactProfile(
        AiProviderRequest request,
        OpenAiResponsesProtocol.ServerPrompt prompt
    ) {
        AiVersionReferences versions = request.versions();
        ProviderVersion provider = versions.provider();
        ModelVersion model = versions.model();
        KnowledgeSourceVersion knowledge = versions.knowledgeSource();
        OutputSchemaVersion outputSchema = versions.outputSchema();

        boolean invalid = !OpenAiResponsesProtocol.PROVIDER_ID.equals(provider.providerId())
            || !OpenAiResponsesProtocol.PROVIDER_ID.equals(model.providerId())
            || !OpenAiResponsesProtocol.MODEL_ID.equals(model.modelId())
            || !OpenAiResponsesProtocol.MODEL_VERSION.equals(model.version())
            || !KnowledgeSourceVersion.none().equals(knowledge)
            || !OpenAiResponsesProtocol.OUTPUT_SCHEMA_ID.equals(outputSchema.schemaId())
            || OpenAiResponsesProtocol.OUTPUT_SCHEMA_VERSION != outputSchema.version()
            || !prompt.templateId().equals(versions.promptTemplate().templateId())
            || !prompt.version().equals(versions.promptTemplate().version())
            || !prompt.contentHash().equals(versions.promptTemplate().contentHash())
            || !expectedPromptTemplate(request.capability()).equals(prompt.templateId())
            || request.inputFields().isEmpty();
        if (invalid) {
            throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        }
    }

    private static String expectedPromptTemplate(AiCapability capability) {
        return switch (capability) {
            case APPROVAL_SUMMARY -> "approval-summary";
            case MATERIAL_COMPLETENESS -> "approval-material-completeness";
            case RISK_SIGNALS -> "approval-risk-review";
            default -> throw OpenAiResponsesProtocol.failure(REQUEST_INVALID);
        };
    }
}
