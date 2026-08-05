package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesAdvisoryProviderTest {

    @Test
    void descriptorIsTheOneFrozenRemoteProfile() {
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                throw new AssertionError("transport must not be called");
            });

        assertEquals(OpenAiResponsesProtocol.PROVIDER_ID, provider.descriptor().providerId());
        assertEquals(AiProviderType.REMOTE, provider.descriptor().providerType());
        assertEquals(OpenAiResponsesAdvisoryProvider.providerVersion(),
            provider.descriptor().providerVersion());
        assertEquals(Set.of(OpenAiResponsesAdvisoryProvider.modelVersion()),
            provider.descriptor().models());
        assertTrue(provider.descriptor().supports(AiCapability.APPROVAL_SUMMARY));
        assertTrue(provider.descriptor().supports(AiCapability.MATERIAL_COMPLETENESS));
        assertTrue(provider.descriptor().supports(AiCapability.RISK_SIGNALS));
    }

    @Test
    void cancellationBeforeEncodingMakesZeroTransportCalls() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                calls.incrementAndGet();
                throw new AssertionError("transport must not be called");
            });

        var outcome = provider.advise(request(), () -> true);

        assertEquals(0, calls.get());
        assertEquals(AiOutcomeClassification.UNKNOWN, outcome.classification());
        assertEquals("AI_OPENAI_CANCELLED", outcome.failure().code());
        assertFalse(outcome.failure().retryable());
    }

    @Test
    void transportTimeoutIsOneAttemptWithoutRetryOrFallback() {
        AtomicInteger calls = new AtomicInteger();
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                calls.incrementAndGet();
                throw new OpenAiResponsesTransportException(
                    OpenAiResponsesTransportException.Failure.TIMEOUT
                );
            });

        var outcome = provider.advise(request(), () -> false);

        assertEquals(1, calls.get());
        assertEquals(AiOutcomeClassification.TIMEOUT, outcome.classification());
        assertEquals("AI_OPENAI_TIMEOUT", outcome.failure().code());
        assertFalse(outcome.failure().retryable());
    }

    @Test
    void malformedProviderBodyFailsClosedAfterExactlyOneExchange() {
        AtomicInteger calls = new AtomicInteger();
        String requestId = "req-p6-e-test";
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                calls.incrementAndGet();
                return new OpenAiResponsesTransportPort.Response(
                    200,
                    requestId,
                    "{}".getBytes(StandardCharsets.UTF_8),
                    OpenAiResponsesTransportPort.TransportEvidence.verified(
                        hash("endpoint"),
                        hash("admission"),
                        hash("dns"),
                        hash("address"),
                        hash("tls"),
                        OpenAiResponsesProtocol.sha256Utf8(requestId),
                        OpenAiResponsesProtocol.sha256("{}".getBytes(StandardCharsets.UTF_8))
                    )
                );
            });

        var outcome = provider.advise(request(), () -> false);

        assertEquals(1, calls.get());
        assertEquals(AiOutcomeClassification.INVALID_OUTPUT, outcome.classification());
        assertEquals("AI_OPENAI_OUTPUT_INVALID", outcome.failure().code());
        assertFalse(outcome.failure().retryable());
    }

    private static AiProviderRequest request() {
        AiCapability capability = AiCapability.APPROVAL_SUMMARY;
        AiVersionReferences versions = new AiVersionReferences(
            OpenAiResponsesAdvisoryProvider.providerVersion(),
            OpenAiResponsesAdvisoryProvider.modelVersion(),
            OpenAiResponsesAdvisoryProvider.promptVersion(capability),
            AiVersionReferences.KnowledgeSourceVersion.none(),
            new AiVersionReferences.PolicyVersion(
                "approval-assistance-production",
                "p6-e-v1",
                hash("policy")
            ),
            OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
        );
        return new AiProviderRequest(
            new AiProviderRequest.AuthorizedContext(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a"
            ),
            new AiProviderRequest.AuthorizedResource(
                "tenant-a",
                "APPROVAL_TASK",
                "task-a",
                hash("authorization")
            ),
            capability,
            Set.of("supplier"),
            List.of(new AiProviderRequest.InputField(
                "supplier",
                "TEXT",
                "masked supplier",
                AiProviderRequest.MaskingDisposition.MASKED
            )),
            versions,
            Duration.ofSeconds(15)
        );
    }

    private static String hash(String value) {
        return OpenAiResponsesProtocol.sha256Utf8(value);
    }
}
