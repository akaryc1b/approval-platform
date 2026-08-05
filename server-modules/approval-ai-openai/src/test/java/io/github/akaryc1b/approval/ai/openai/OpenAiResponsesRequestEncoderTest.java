package io.github.akaryc1b.approval.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesRequestEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exactProfileEncodesCanonicalStatelessIdentityFreeRequest() throws Exception {
        OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

        OpenAiResponsesTransportPort.Request first = encoder.encode(
            request(versions(), Duration.ofSeconds(10), providerFields()),
            prompt(),
            limits(),
            2_048
        );
        OpenAiResponsesTransportPort.Request second = encoder.encode(
            request(versions(), Duration.ofSeconds(10), providerFields()),
            prompt(),
            limits(),
            2_048
        );

        assertArrayEquals(first.bodyCopy(), second.bodyCopy());
        assertEquals(first.bodyHash(), second.bodyHash());
        assertEquals(Duration.ofSeconds(2), first.connectTimeout());
        assertEquals(Duration.ofSeconds(10), first.totalTimeout());

        String json = new String(first.bodyCopy(), StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(json);
        assertEquals(OpenAiResponsesProtocol.MODEL_SNAPSHOT, root.get("model").asText());
        assertFalse(root.get("store").asBoolean());
        assertFalse(root.get("background").asBoolean());
        assertFalse(root.get("stream").asBoolean());
        assertTrue(root.get("tools").isEmpty());
        assertEquals("none", root.get("tool_choice").asText());
        assertEquals("disabled", root.get("truncation").asText());
        assertEquals(
            OpenAiResponsesProtocol.RESPONSE_FORMAT_NAME,
            root.get("text").get("format").get("name").asText()
        );
        assertTrue(root.get("text").get("format").get("strict").asBoolean());
        assertEquals(
            "json_schema",
            root.get("text").get("format").get("type").asText()
        );

        for (String forbidden : List.of(
            "tenant-secret",
            "operator-secret",
            "task-secret",
            "instance-secret",
            "authorization-secret"
        )) {
            assertFalse(json.contains(forbidden));
        }
        for (String absent : List.of(
            "previous_response_id",
            "conversation",
            "prompt_cache_retention",
            "\"metadata\""
        )) {
            assertFalse(json.contains(absent));
        }

        String inputText = root.get("input").get(0).get("content").get(0)
            .get("text").asText();
        JsonNode input = MAPPER.readTree(inputText);
        assertEquals("APPROVAL_SUMMARY", input.get("capability").asText());
        assertEquals("amount", input.get("fields").get(0).get("key").asText());
        assertEquals("details", input.get("fields").get(1).get("key").asText());
        assertFalse(first.toString().contains(json));
    }

    @Test
    void profilePromptAndTimeoutDriftFailClosed() {
        OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

        OpenAiResponsesProtocol.ProtocolException model = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> encoder.encode(
                request(modelDrift(), Duration.ofSeconds(10), providerFields()),
                prompt(),
                limits(),
                2_048
            )
        );
        assertEquals(OpenAiResponsesProtocol.Failure.REQUEST_INVALID, model.failure());

        OpenAiResponsesProtocol.ProtocolException timeout = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> encoder.encode(
                request(versions(), Duration.ofSeconds(16), providerFields()),
                prompt(),
                limits(),
                2_048
            )
        );
        assertEquals(OpenAiResponsesProtocol.Failure.REQUEST_INVALID, timeout.failure());

        OpenAiResponsesProtocol.ProtocolException tokenBudget = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> encoder.encode(
                request(versions(), Duration.ofSeconds(10), providerFields()),
                prompt(),
                limits(),
                16_385
            )
        );
        assertEquals(
            OpenAiResponsesProtocol.Failure.REQUEST_INVALID,
            tokenBudget.failure()
        );
    }

    @Test
    void unsupportedAndNonFiniteProviderValuesFailClosed() {
        OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

        for (Object value : List.of(Double.NaN, Double.POSITIVE_INFINITY, new Object())) {
            OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
                OpenAiResponsesProtocol.ProtocolException.class,
                () -> encoder.encode(
                    request(
                        versions(),
                        Duration.ofSeconds(10),
                        List.of(new InputField(
                            "amount",
                            "NUMBER",
                            value,
                            MaskingDisposition.INCLUDED
                        ))
                    ),
                    prompt(),
                    limits(),
                    2_048
                )
            );
            assertEquals(
                OpenAiResponsesProtocol.Failure.REQUEST_INVALID,
                failure.failure()
            );
        }
    }

    @Test
    void requestBodyBudgetIsEnforcedBeforeTransport() {
        OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
        String oversized = "x".repeat(OpenAiResponsesProtocol.MAXIMUM_REQUEST_BYTES);

        OpenAiResponsesProtocol.ProtocolException failure = assertThrows(
            OpenAiResponsesProtocol.ProtocolException.class,
            () -> encoder.encode(
                request(
                    versions(),
                    Duration.ofSeconds(10),
                    List.of(new InputField(
                        "amount",
                        "TEXT",
                        oversized,
                        MaskingDisposition.INCLUDED
                    ))
                ),
                prompt(),
                limits(),
                2_048
            )
        );
        assertEquals(
            OpenAiResponsesProtocol.Failure.REQUEST_TOO_LARGE,
            failure.failure()
        );
    }

    private static List<InputField> providerFields() {
        return List.of(
            new InputField(
                "details",
                "OBJECT",
                Map.of("z", "last", "a", List.of("first", true)),
                MaskingDisposition.MASKED
            ),
            new InputField(
                "amount",
                "NUMBER",
                "1000.00",
                MaskingDisposition.INCLUDED
            )
        );
    }

    private static AiProviderRequest request(
        AiVersionReferences versions,
        Duration timeout,
        List<InputField> fields
    ) {
        return new AiProviderRequest(
            new AuthorizedContext(
                "tenant-secret",
                "operator-secret",
                "request-secret",
                "trace-secret"
            ),
            new AuthorizedResource(
                "tenant-secret",
                "APPROVAL_TASK",
                "task-secret",
                "authorization-secret"
            ),
            AiCapability.APPROVAL_SUMMARY,
            fields.stream().map(InputField::key).collect(java.util.stream.Collectors.toSet()),
            fields,
            versions,
            timeout
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

    private static AiVersionReferences modelDrift() {
        AiVersionReferences source = versions();
        return new AiVersionReferences(
            source.provider(),
            new ModelVersion(
                OpenAiResponsesProtocol.PROVIDER_ID,
                OpenAiResponsesProtocol.MODEL_ID,
                "floating"
            ),
            source.promptTemplate(),
            source.knowledgeSource(),
            source.policy(),
            source.outputSchema()
        );
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
