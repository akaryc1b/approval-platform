package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesCodecHardeningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST_ID = "req_codec_hardening";
    private static final String REQUEST_ID_HASH =
        OpenAiResponsesProtocol.sha256Utf8(REQUEST_ID);

    @Test
    void strictSchemaUsesTypedBooleanConstantsAndExactProviderVersion() throws Exception {
        OpenAiResponsesTransportPort.Request encoded = new OpenAiResponsesRequestEncoder().encode(
            request(versions()),
            prompt(),
            limits(),
            2_048
        );
        JsonNode root = MAPPER.readTree(encoded.bodyCopy());
        JsonNode properties = root.path("text").path("format").path("schema")
            .path("properties");

        JsonNode humanReview = properties.path("needsHumanReview");
        assertTrue(humanReview.isObject());
        assertEquals("boolean", humanReview.path("type").asText());
        assertEquals(1, humanReview.path("enum").size());
        assertTrue(humanReview.path("enum").get(0).asBoolean());

        JsonNode customerData = properties.path("versions").path("properties")
            .path("knowledgeSource").path("properties")
            .path("containsCustomerData");
        assertTrue(customerData.isObject());
        assertEquals("boolean", customerData.path("type").asText());
        assertEquals(1, customerData.path("enum").size());
        assertFalse(customerData.path("enum").get(0).asBoolean());

        AiVersionReferences drifted = new AiVersionReferences(
            new ProviderVersion(OpenAiResponsesProtocol.PROVIDER_ID, "floating"),
            versions().model(),
            versions().promptTemplate(),
            versions().knowledgeSource(),
            versions().policy(),
            versions().outputSchema()
        );
        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> new OpenAiResponsesRequestEncoder().encode(
                request(drifted),
                prompt(),
                limits(),
                2_048
            )
        );
        assertEquals(OpenAiResponsesProtocol.Failure.REQUEST_INVALID, failure.failure());
    }

    @Test
    void decodedConfidenceUsesTheAcceptedP2HalfAndFourFifthsThresholds() {
        assertDoesNotThrow(() -> decoded(0.49d, AiAdvisoryResult.ConfidenceBand.LOW));
        assertDoesNotThrow(() -> decoded(0.50d, AiAdvisoryResult.ConfidenceBand.MEDIUM));
        assertDoesNotThrow(() -> decoded(0.79d, AiAdvisoryResult.ConfidenceBand.MEDIUM));
        assertDoesNotThrow(() -> decoded(0.80d, AiAdvisoryResult.ConfidenceBand.HIGH));

        assertResultInvalid(0.50d, AiAdvisoryResult.ConfidenceBand.LOW);
        assertResultInvalid(0.70d, AiAdvisoryResult.ConfidenceBand.HIGH);
        assertResultInvalid(0.80d, AiAdvisoryResult.ConfidenceBand.MEDIUM);
    }

    @Test
    void currentKnownResponseFieldsAreAcceptedOnlyInTheStatelessProfile() throws Exception {
        ObjectNode accepted = responseBody();
        accepted.putNull("max_tool_calls");
        accepted.putNull("prompt");
        accepted.putNull("prompt_cache_key");
        accepted.putNull("prompt_cache_retention");
        accepted.putNull("safety_identifier");
        accepted.putNull("user");
        accepted.put("top_logprobs", 0);
        accepted.set("metadata", MAPPER.createObjectNode());

        OpenAiResponsesProtocol.DecodedResponse decoded = decoder().decode(
            response(accepted),
            expectations()
        );
        assertEquals(0.70d, decoded.advisory().confidence().score());
        assertEquals(
            AiAdvisoryResult.ConfidenceBand.MEDIUM,
            decoded.advisory().confidence().band()
        );

        ObjectNode promptState = accepted.deepCopy();
        promptState.set("prompt", MAPPER.createObjectNode().put("id", "pmpt_forbidden"));
        assertSchemaMismatch(promptState);

        ObjectNode cacheRetention = accepted.deepCopy();
        cacheRetention.put("prompt_cache_retention", "24h");
        assertSchemaMismatch(cacheRetention);

        ObjectNode metadata = accepted.deepCopy();
        metadata.set("metadata", MAPPER.createObjectNode().put("tenant", "forbidden"));
        assertSchemaMismatch(metadata);
    }

    private static OpenAiResponsesProtocol.DecodedResponse decoded(
        double score,
        AiAdvisoryResult.ConfidenceBand band
    ) {
        return new OpenAiResponsesProtocol.DecodedResponse(
            advisory(score, band),
            new OpenAiResponsesProtocol.Usage(1, 1, 2, 0, 0),
            "a".repeat(64),
            "b".repeat(64)
        );
    }

    private static void assertResultInvalid(
        double score,
        AiAdvisoryResult.ConfidenceBand band
    ) {
        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoded(score, band)
        );
        assertEquals(OpenAiResponsesProtocol.Failure.RESULT_INVALID, failure.failure());
    }

    private static void assertSchemaMismatch(ObjectNode body) throws Exception {
        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoder().decode(response(body), expectations())
        );
        assertEquals(OpenAiResponsesProtocol.Failure.SCHEMA_MISMATCH, failure.failure());
    }

    private static OpenAiResponsesResponseDecoder decoder() {
        return new OpenAiResponsesResponseDecoder();
    }

    private static OpenAiResponsesProtocol.DecodeExpectations expectations() {
        return new OpenAiResponsesProtocol.DecodeExpectations(
            versions(),
            limits(),
            Set.of("amount"),
            REQUEST_ID_HASH
        );
    }

    private static OpenAiResponsesTransportPort.Response response(ObjectNode body)
        throws Exception {
        return new OpenAiResponsesTransportPort.Response(
            200,
            REQUEST_ID,
            MAPPER.writeValueAsBytes(body)
        );
    }

    private static ObjectNode responseBody() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "resp_codec_hardening");
        root.put("object", "response");
        root.put("status", "completed");
        root.putNull("error");
        root.putNull("incomplete_details");
        root.put("model", OpenAiResponsesProtocol.MODEL_SNAPSHOT);
        root.put("store", false);
        root.put("background", false);
        root.putNull("previous_response_id");
        root.putNull("conversation");
        root.set("tools", MAPPER.createArrayNode());
        root.put("tool_choice", "none");

        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME);
        format.put("strict", true);
        format.set("schema", MAPPER.createObjectNode());
        root.set("text", MAPPER.createObjectNode().set("format", format));

        ObjectNode outputText = MAPPER.createObjectNode();
        outputText.put("type", "output_text");
        outputText.put("text", MAPPER.writeValueAsString(structuredResult()));
        outputText.set("annotations", MAPPER.createArrayNode());
        outputText.putNull("logprobs");

        ObjectNode message = MAPPER.createObjectNode();
        message.put("id", "msg_codec_hardening");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("status", "completed");
        message.set("content", MAPPER.createArrayNode().add(outputText));
        root.set("output", MAPPER.createArrayNode().add(message));

        root.set(
            "usage",
            MAPPER.createObjectNode()
                .put("input_tokens", 1)
                .put("output_tokens", 1)
                .put("total_tokens", 2)
        );
        return root;
    }

    private static ObjectNode structuredResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("summary", "Bounded summary");

        ObjectNode observation = MAPPER.createObjectNode();
        observation.put("id", "observation-1");
        observation.put("text", "Authorized amount is present");
        observation.set(
            "evidenceReferenceIds",
            MAPPER.createArrayNode().add("evidence-1")
        );
        result.set("observations", MAPPER.createArrayNode().add(observation));
        result.set("riskSignals", MAPPER.createArrayNode());
        result.set("missingMaterials", MAPPER.createArrayNode());

        ObjectNode recommendation = MAPPER.createObjectNode();
        recommendation.put("id", "recommendation-1");
        recommendation.put("type", "VERIFY_EVIDENCE");
        recommendation.put("text", "Verify the authorized amount");
        recommendation.set(
            "evidenceReferenceIds",
            MAPPER.createArrayNode().add("evidence-1")
        );
        result.set(
            "recommendations",
            MAPPER.createArrayNode().add(recommendation)
        );

        ObjectNode evidence = MAPPER.createObjectNode();
        evidence.put("id", "evidence-1");
        evidence.put("fieldKey", "amount");
        evidence.put("description", "Authorized amount field");
        result.set("evidenceReferences", MAPPER.createArrayNode().add(evidence));

        result.set(
            "confidence",
            MAPPER.createObjectNode().put("score", 0.70d).put("band", "MEDIUM")
        );
        result.set(
            "limitations",
            MAPPER.createArrayNode().add("Human review is required")
        );
        result.put("needsHumanReview", true);
        result.set("versions", versionsNode());
        result.put("authority", "ADVISORY");
        result.put("assertionStatus", "UNVERIFIED_ADVISORY");
        return result;
    }

    private static ObjectNode versionsNode() {
        AiVersionReferences value = versions();
        ObjectNode versions = MAPPER.createObjectNode();
        versions.set(
            "provider",
            MAPPER.createObjectNode()
                .put("providerId", value.provider().providerId())
                .put("version", value.provider().version())
        );
        versions.set(
            "model",
            MAPPER.createObjectNode()
                .put("providerId", value.model().providerId())
                .put("modelId", value.model().modelId())
                .put("version", value.model().version())
        );
        versions.set(
            "promptTemplate",
            MAPPER.createObjectNode()
                .put("templateId", value.promptTemplate().templateId())
                .put("version", value.promptTemplate().version())
                .put("contentHash", value.promptTemplate().contentHash())
        );
        versions.set(
            "knowledgeSource",
            MAPPER.createObjectNode()
                .put("sourceId", value.knowledgeSource().sourceId())
                .put("version", value.knowledgeSource().version())
                .put("contentHash", value.knowledgeSource().contentHash())
                .put(
                    "containsCustomerData",
                    value.knowledgeSource().containsCustomerData()
                )
        );
        versions.set(
            "policy",
            MAPPER.createObjectNode()
                .put("policyId", value.policy().policyId())
                .put("version", value.policy().version())
                .put("contentHash", value.policy().contentHash())
        );
        versions.set(
            "outputSchema",
            MAPPER.createObjectNode()
                .put("schemaId", value.outputSchema().schemaId())
                .put("version", value.outputSchema().version())
        );
        return versions;
    }

    private static AiAdvisoryResult advisory(
        double score,
        AiAdvisoryResult.ConfidenceBand band
    ) {
        return new AiAdvisoryResult(
            "Bounded summary",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new AiAdvisoryResult.Confidence(score, band),
            List.of("Human review is required"),
            true,
            versions(),
            AiAdvisoryResult.Authority.ADVISORY,
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
        );
    }

    private static AiProviderRequest request(AiVersionReferences versions) {
        List<AiProviderRequest.InputField> fields = List.of(
            new AiProviderRequest.InputField(
                "amount",
                "NUMBER",
                "1000.00",
                AiProviderRequest.MaskingDisposition.INCLUDED
            )
        );
        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                "tenant-secret",
                "operator-secret",
                "request-secret",
                "trace-secret"
            ),
            new AiProviderRequest.AuthorizedResource(
                "tenant-secret",
                "APPROVAL_TASK",
                "task-secret",
                "authorization-secret"
            ),
            AiCapability.APPROVAL_SUMMARY,
            Set.of("amount"),
            fields,
            versions,
            Duration.ofSeconds(10)
        );
    }

    private static OpenAiResponsesProtocol.ServerPrompt prompt() {
        return new OpenAiResponsesProtocol.ServerPrompt(
            "approval-summary",
            "v1",
            "prompt-hash-v1",
            "Summarize only the supplied Provider-safe fields as unverified advisory material."
        );
    }

    private static OpenAiResponsesProtocol.OutputLimits limits() {
        return new OpenAiResponsesProtocol.OutputLimits(4, 4, 4, 4, 8, 4);
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new ProviderVersion(
                OpenAiResponsesProtocol.PROVIDER_ID,
                OpenAiResponsesProtocol.PROVIDER_VERSION
            ),
            new ModelVersion(
                OpenAiResponsesProtocol.PROVIDER_ID,
                OpenAiResponsesProtocol.MODEL_ID,
                OpenAiResponsesProtocol.MODEL_VERSION
            ),
            new PromptTemplateVersion(
                "approval-summary",
                "v1",
                "prompt-hash-v1"
            ),
            KnowledgeSourceVersion.none(),
            new PolicyVersion("approval-data-policy", "v3", "policy-hash-v3"),
            new OutputSchemaVersion(
                OpenAiResponsesProtocol.OUTPUT_SCHEMA_ID,
                OpenAiResponsesProtocol.OUTPUT_SCHEMA_VERSION
            )
        );
    }
}
