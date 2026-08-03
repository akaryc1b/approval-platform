package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.KnowledgeSourceVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ModelVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.OutputSchemaVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PromptTemplateVersion;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.ProviderVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesResponseDecoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST_ID = "req_test_123";
    private static final String REQUEST_ID_HASH =
        OpenAiResponsesProtocol.sha256Utf8(REQUEST_ID);

    @Test
    void completedStructuredResponseDecodesToBoundedHashOnlyEvidence() throws Exception {
        OpenAiResponsesProtocol.DecodedResponse decoded = decoder().decode(
            response(200, REQUEST_ID, completedBody()),
            expectations()
        );

        assertEquals("Bounded summary", decoded.advisory().summary());
        assertEquals(1, decoded.advisory().observations().size());
        assertEquals(1, decoded.advisory().recommendations().size());
        assertEquals(1, decoded.advisory().evidenceReferences().size());
        assertTrue(decoded.advisory().needsHumanReview());
        assertEquals(AiAdvisoryResult.Authority.ADVISORY, decoded.advisory().authority());
        assertEquals(
            AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY,
            decoded.advisory().assertionStatus()
        );
        assertEquals(20, decoded.usage().inputTokens());
        assertEquals(10, decoded.usage().outputTokens());
        assertEquals(30, decoded.usage().totalTokens());
        assertEquals(REQUEST_ID_HASH, decoded.requestIdHash());
        assertEquals(64, decoded.responseIdHash().length());
        assertFalse(decoded.toString().contains(REQUEST_ID));
        assertFalse(decoded.toString().contains("resp_test_123"));
    }

    @Test
    void httpAndRequestIdentifierEvidenceFailClosed() throws Exception {
        OpenAiResponsesProtocol.ProtocolException status = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoder().decode(
                response(429, REQUEST_ID, completedBody()),
                expectations()
            )
        );
        assertEquals(
            OpenAiResponsesProtocol.Failure.HTTP_STATUS_REJECTED,
            status.failure()
        );

        OpenAiResponsesProtocol.ProtocolException missing = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoder().decode(
                response(200, null, completedBody()),
                expectations()
            )
        );
        assertEquals(
            OpenAiResponsesProtocol.Failure.REQUEST_ID_MISSING,
            missing.failure()
        );

        OpenAiResponsesProtocol.ProtocolException mismatch = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoder().decode(
                response(200, "req_other", completedBody()),
                expectations()
            )
        );
        assertEquals(
            OpenAiResponsesProtocol.Failure.REQUEST_ID_MISMATCH,
            mismatch.failure()
        );
    }

    @Test
    void incompleteProviderErrorRefusalAndMultipleOutputsFailClosed() throws Exception {
        ObjectNode incomplete = bodyNode();
        incomplete.put("status", "incomplete");
        assertFailure(
            incomplete,
            OpenAiResponsesProtocol.Failure.RESPONSE_STATUS_REJECTED
        );

        ObjectNode providerError = bodyNode();
        providerError.set("error", MAPPER.createObjectNode().put("code", "rejected"));
        assertFailure(providerError, OpenAiResponsesProtocol.Failure.PROVIDER_ERROR);

        ObjectNode refusal = bodyNode();
        ObjectNode refusalContent = MAPPER.createObjectNode();
        refusalContent.put("type", "refusal");
        refusalContent.put("refusal", "not returned");
        ArrayNode refusalItems = (ArrayNode) message(refusal).get("content");
        refusalItems.removeAll().add(refusalContent);
        assertFailure(refusal, OpenAiResponsesProtocol.Failure.REFUSAL);

        ObjectNode multiple = bodyNode();
        ArrayNode multipleOutput = (ArrayNode) multiple.get("output");
        multipleOutput.add(multipleOutput.get(0).deepCopy());
        assertFailure(multiple, OpenAiResponsesProtocol.Failure.OUTPUT_NOT_EXACT);
    }

    @Test
    void duplicateUnknownMalformedInvalidUtf8AndOversizeFailClosed() throws Exception {
        String duplicate = new String(
            completedBody(),
            StandardCharsets.UTF_8
        ).replace(
            "\"status\":\"completed\"",
            "\"status\":\"completed\",\"status\":\"completed\""
        );
        assertFailure(
            duplicate.getBytes(StandardCharsets.UTF_8),
            OpenAiResponsesProtocol.Failure.DUPLICATE_PROPERTY
        );

        ObjectNode unknown = bodyNode();
        unknown.put("unexpected", true);
        assertFailure(unknown, OpenAiResponsesProtocol.Failure.UNKNOWN_PROPERTY);

        assertFailure(
            "{".getBytes(StandardCharsets.UTF_8),
            OpenAiResponsesProtocol.Failure.MALFORMED_JSON
        );
        assertFailure(
            new byte[] {(byte) 0xc3, (byte) 0x28},
            OpenAiResponsesProtocol.Failure.INVALID_UTF8
        );
        assertFailure(
            new byte[OpenAiResponsesProtocol.MAXIMUM_RESPONSE_BYTES + 1],
            OpenAiResponsesProtocol.Failure.RESPONSE_TOO_LARGE
        );
    }

    @Test
    void modelVersionUsageAndEvidenceDriftFailClosed() throws Exception {
        ObjectNode model = bodyNode();
        model.put("model", "gpt-5-mini");
        assertFailure(model, OpenAiResponsesProtocol.Failure.MODEL_MISMATCH);

        ObjectNode version = bodyNode();
        ObjectNode structuredVersion = structuredNode(version);
        ObjectNode versionValues = (ObjectNode) structuredVersion.get("versions");
        ((ObjectNode) versionValues.get("model")).put("version", "floating");
        replaceStructuredText(version, structuredVersion);
        assertFailure(version, OpenAiResponsesProtocol.Failure.VERSION_MISMATCH);

        ObjectNode usage = bodyNode();
        ((ObjectNode) usage.get("usage")).put("total_tokens", 31);
        assertFailure(usage, OpenAiResponsesProtocol.Failure.USAGE_INVALID);

        ObjectNode evidence = bodyNode();
        ObjectNode structuredEvidence = structuredNode(evidence);
        ArrayNode evidenceValues = (ArrayNode) structuredEvidence.get("evidenceReferences");
        ((ObjectNode) evidenceValues.get(0)).put("fieldKey", "unauthorized");
        replaceStructuredText(evidence, structuredEvidence);
        assertFailure(evidence, OpenAiResponsesProtocol.Failure.RESULT_INVALID);
    }

    @Test
    void nestedStructuredUnknownsAndConfidenceMismatchFailClosed() throws Exception {
        ObjectNode unknown = bodyNode();
        ObjectNode structuredUnknown = structuredNode(unknown);
        structuredUnknown.put("command", "approve");
        replaceStructuredText(unknown, structuredUnknown);
        assertFailure(unknown, OpenAiResponsesProtocol.Failure.UNKNOWN_PROPERTY);

        ObjectNode confidence = bodyNode();
        ObjectNode structuredConfidence = structuredNode(confidence);
        ObjectNode confidenceValue = (ObjectNode) structuredConfidence.get("confidence");
        confidenceValue.put("score", 0.90d);
        confidenceValue.put("band", "LOW");
        replaceStructuredText(confidence, structuredConfidence);
        assertFailure(confidence, OpenAiResponsesProtocol.Failure.RESULT_INVALID);
    }

    private static OpenAiResponsesResponseDecoder decoder() {
        return new OpenAiResponsesResponseDecoder();
    }

    private static OpenAiResponsesProtocol.DecodeExpectations expectations() {
        return new OpenAiResponsesProtocol.DecodeExpectations(
            versions(),
            new OpenAiResponsesProtocol.OutputLimits(4, 4, 4, 4, 8, 4),
            Set.of("amount"),
            REQUEST_ID_HASH
        );
    }

    private static OpenAiResponsesTransportPort.Response response(
        int statusCode,
        String requestId,
        byte[] body
    ) {
        return new OpenAiResponsesTransportPort.Response(statusCode, requestId, body);
    }

    private static byte[] completedBody() throws Exception {
        return MAPPER.writeValueAsBytes(bodyNode());
    }

    private static ObjectNode bodyNode() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "resp_test_123");
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
        root.set(
            "text",
            MAPPER.createObjectNode().set("format", format)
        );

        ObjectNode outputText = MAPPER.createObjectNode();
        outputText.put("type", "output_text");
        outputText.put("text", MAPPER.writeValueAsString(structuredResult()));
        outputText.set("annotations", MAPPER.createArrayNode());
        outputText.putNull("logprobs");

        ObjectNode message = MAPPER.createObjectNode();
        message.put("id", "msg_test_123");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("status", "completed");
        message.set("content", MAPPER.createArrayNode().add(outputText));
        root.set("output", MAPPER.createArrayNode().add(message));

        ObjectNode usage = MAPPER.createObjectNode();
        usage.put("input_tokens", 20);
        usage.put("output_tokens", 10);
        usage.put("total_tokens", 30);
        usage.set(
            "input_tokens_details",
            MAPPER.createObjectNode().put("cached_tokens", 2)
        );
        usage.set(
            "output_tokens_details",
            MAPPER.createObjectNode().put("reasoning_tokens", 3)
        );
        root.set("usage", usage);
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
        result.set(
            "evidenceReferences",
            MAPPER.createArrayNode().add(evidence)
        );

        ObjectNode confidence = MAPPER.createObjectNode();
        confidence.put("score", 0.85d);
        confidence.put("band", "HIGH");
        result.set("confidence", confidence);
        result.set(
            "limitations",
            MAPPER.createArrayNode().add(
                "Unverified advisory material requires human review"
            )
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

    private static ObjectNode structuredNode(ObjectNode body) throws Exception {
        return (ObjectNode) MAPPER.readTree(outputText(body).get("text").asText());
    }

    private static void replaceStructuredText(
        ObjectNode body,
        ObjectNode structured
    ) throws Exception {
        outputText(body).put("text", MAPPER.writeValueAsString(structured));
    }

    private static ObjectNode message(ObjectNode body) {
        return (ObjectNode) ((ArrayNode) body.get("output")).get(0);
    }

    private static ObjectNode outputText(ObjectNode body) {
        return (ObjectNode) ((ArrayNode) message(body).get("content")).get(0);
    }

    private static void assertFailure(
        ObjectNode body,
        OpenAiResponsesProtocol.Failure expected
    ) throws Exception {
        assertFailure(MAPPER.writeValueAsBytes(body), expected);
    }

    private static void assertFailure(
        byte[] body,
        OpenAiResponsesProtocol.Failure expected
    ) {
        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> decoder().decode(response(200, REQUEST_ID, body), expectations())
        );
        assertEquals(expected, failure.failure());
    }

    private static AiVersionReferences versions() {
        return new AiVersionReferences(
            new ProviderVersion(OpenAiResponsesProtocol.PROVIDER_ID, "responses-v1"),
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
