package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenAiResponsesProductionFaultMatrixTest {

    private static final String SAFE_FAILURE_MESSAGE =
        "AI advisory generation did not produce trusted output";

    @Test
    void everyTransportFailureIsSingleAttemptStableAndNonRetryable() {
        for (OpenAiResponsesTransportException.Failure failure
            : OpenAiResponsesTransportException.Failure.values()) {
            AtomicInteger calls = new AtomicInteger();
            OpenAiResponsesAdvisoryProvider provider =
                OpenAiResponsesAdvisoryProvider.production(request -> {
                    calls.incrementAndGet();
                    throw new OpenAiResponsesTransportException(failure);
                });

            var outcome = provider.advise(request(), () -> false);

            assertEquals(1, calls.get(), failure.name());
            assertEquals(expectedClassification(failure), outcome.classification(), failure.name());
            assertEquals(expectedCode(failure), outcome.failure().code(), failure.name());
            assertEquals(SAFE_FAILURE_MESSAGE, outcome.failure().message(), failure.name());
            assertFalse(outcome.failure().retryable(), failure.name());
            assertFalse(outcome.hasAdvisoryResult(), failure.name());
        }
    }

    @Test
    void providerHttpFailureMatrixUsesOneExchangeAndNeverLeaksBody() {
        for (int statusCode : List.of(401, 403, 429, 500, 503)) {
            AtomicInteger calls = new AtomicInteger();
            String requestId = "req-p6-f-http-" + statusCode;
            String body = "provider-sensitive-body-" + statusCode;
            OpenAiResponsesAdvisoryProvider provider =
                OpenAiResponsesAdvisoryProvider.production(request -> {
                    calls.incrementAndGet();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    return new OpenAiResponsesTransportPort.Response(
                        statusCode,
                        requestId,
                        bytes,
                        OpenAiResponsesTransportPort.TransportEvidence.verified(
                            hash("endpoint-" + statusCode),
                            hash("admission-" + statusCode),
                            hash("dns-" + statusCode),
                            hash("address-" + statusCode),
                            hash("tls-" + statusCode),
                            OpenAiResponsesProtocol.sha256Utf8(requestId),
                            OpenAiResponsesProtocol.sha256(bytes)
                        )
                    );
                });

            var outcome = provider.advise(request(), () -> false);

            assertEquals(1, calls.get(), Integer.toString(statusCode));
            assertEquals(
                AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                outcome.classification(),
                Integer.toString(statusCode)
            );
            assertEquals(
                "AI_OPENAI_PROVIDER_UNAVAILABLE",
                outcome.failure().code(),
                Integer.toString(statusCode)
            );
            assertEquals(SAFE_FAILURE_MESSAGE, outcome.failure().message());
            assertFalse(outcome.failure().retryable());
            assertFalse(outcome.toString().contains(body));
            assertFalse(outcome.toString().contains(requestId));
        }
    }

    @Test
    void unexpectedRuntimeFailureRemainsUnknownBodyFreeAndSingleAttempt() {
        AtomicInteger calls = new AtomicInteger();
        String sensitive = "provider-internal-sensitive-detail";
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                calls.incrementAndGet();
                throw new IllegalStateException(sensitive);
            });

        var outcome = provider.advise(request(), () -> false);

        assertEquals(1, calls.get());
        assertEquals(AiOutcomeClassification.UNKNOWN, outcome.classification());
        assertEquals("AI_OPENAI_UNKNOWN", outcome.failure().code());
        assertEquals(SAFE_FAILURE_MESSAGE, outcome.failure().message());
        assertFalse(outcome.failure().retryable());
        assertFalse(outcome.toString().contains(sensitive));
    }

    private static AiOutcomeClassification expectedClassification(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return switch (failure) {
            case KILL_SWITCH_DISABLED -> AiOutcomeClassification.DISABLED;
            case KILL_SWITCH_DRIFT, CIRCUIT_OPEN, RATE_LIMITED,
                 COST_POLICY_STALE, COST_LIMIT_EXCEEDED ->
                AiOutcomeClassification.POLICY_BLOCKED;
            case CANCELLED, TIMEOUT -> AiOutcomeClassification.TIMEOUT;
            case REQUEST_INVALID, ENDPOINT_REJECTED -> AiOutcomeClassification.REJECTED;
            case DNS_FAILURE, DNS_EMPTY, DNS_UNSAFE, DNS_DRIFT, CONNECTION_DRIFT,
                 TLS_FAILURE, TLS_HOSTNAME_MISMATCH, TLS_CHAIN_INVALID,
                 TLS_CERTIFICATE_EXPIRED, SECRET_UNAVAILABLE, HTTP_PROTOCOL_INVALID,
                 REDIRECT_REJECTED, RESPONSE_TOO_LARGE, IO_FAILURE ->
                AiOutcomeClassification.PROVIDER_UNAVAILABLE;
            case UNKNOWN -> AiOutcomeClassification.UNKNOWN;
        };
    }

    private static String expectedCode(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return switch (expectedClassification(failure)) {
            case DISABLED -> "AI_OPENAI_DISABLED";
            case POLICY_BLOCKED -> "AI_OPENAI_POLICY_BLOCKED";
            case TIMEOUT -> "AI_OPENAI_TIMEOUT";
            case REJECTED -> "AI_OPENAI_REQUEST_REJECTED";
            case PROVIDER_UNAVAILABLE -> "AI_OPENAI_PROVIDER_UNAVAILABLE";
            case UNKNOWN -> "AI_OPENAI_UNKNOWN";
            default -> throw new AssertionError("unexpected failure classification");
        };
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
