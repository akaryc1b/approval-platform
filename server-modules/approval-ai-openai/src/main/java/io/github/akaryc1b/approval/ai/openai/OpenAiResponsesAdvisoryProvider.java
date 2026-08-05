package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiCancellation;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderDescriptor;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderType;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * Framework-free production adapter for the one frozen OpenAI Responses profile.
 *
 * <p>The adapter owns no endpoint, Secret, retry, fallback, conversation or command behavior. It
 * delegates request encoding, one transport exchange and strict response decoding to the accepted
 * P6-C/P6-D components.</p>
 */
public final class OpenAiResponsesAdvisoryProvider implements AiAdvisoryProvider {

    public static final String PROMPT_VERSION = "p6-e-v1";
    public static final int MAXIMUM_OUTPUT_TOKENS = 4_096;

    private static final String SUMMARY_INSTRUCTIONS = """
        Produce a bounded approval summary from only the supplied Provider-safe fields. Return
        unverified advisory material for human review. Do not approve, reject, return, transfer,
        withdraw, terminate, migrate, publish, activate, execute commands or claim authority.
        """;
    private static final String MATERIAL_INSTRUCTIONS = """
        Review only the supplied Provider-safe fields for material completeness. Return bounded,
        unverified advisory observations for human review. Do not execute or recommend approval
        commands and do not infer hidden, omitted or attachment content.
        """;
    private static final String RISK_INSTRUCTIONS = """
        Identify bounded review signals from only the supplied Provider-safe fields. Return
        unverified advisory material for human review. Do not make an approval decision, execute a
        command or claim that any observation is verified.
        """;

    private static final AiVersionReferences.ProviderVersion PROVIDER_VERSION =
        new AiVersionReferences.ProviderVersion(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.PROVIDER_VERSION
        );
    private static final AiVersionReferences.ModelVersion MODEL_VERSION =
        new AiVersionReferences.ModelVersion(
            OpenAiResponsesProtocol.PROVIDER_ID,
            OpenAiResponsesProtocol.MODEL_ID,
            OpenAiResponsesProtocol.MODEL_VERSION
        );
    private static final AiProviderDescriptor DESCRIPTOR = new AiProviderDescriptor(
        OpenAiResponsesProtocol.PROVIDER_ID,
        AiProviderType.REMOTE,
        PROVIDER_VERSION,
        Set.of(
            capability(AiCapability.APPROVAL_SUMMARY),
            capability(AiCapability.MATERIAL_COMPLETENESS),
            capability(AiCapability.RISK_SIGNALS)
        ),
        Set.of(MODEL_VERSION)
    );

    private final OpenAiResponsesRequestEncoder encoder;
    private final OpenAiResponsesResponseDecoder decoder;
    private final OpenAiResponsesTransportPort transport;
    private final Map<AiCapability, OpenAiResponsesProtocol.ServerPrompt> prompts;
    private final OpenAiResponsesProtocol.OutputLimits outputLimits;
    private final int maximumOutputTokens;

    public static OpenAiResponsesAdvisoryProvider production(
        OpenAiResponsesTransportPort transport
    ) {
        return new OpenAiResponsesAdvisoryProvider(
            new OpenAiResponsesRequestEncoder(),
            new OpenAiResponsesResponseDecoder(),
            transport,
            productionPrompts(),
            OpenAiResponsesProtocol.OutputLimits.conservativeDefaults(),
            MAXIMUM_OUTPUT_TOKENS
        );
    }

    OpenAiResponsesAdvisoryProvider(
        OpenAiResponsesRequestEncoder encoder,
        OpenAiResponsesResponseDecoder decoder,
        OpenAiResponsesTransportPort transport,
        Map<AiCapability, OpenAiResponsesProtocol.ServerPrompt> prompts,
        OpenAiResponsesProtocol.OutputLimits outputLimits,
        int maximumOutputTokens
    ) {
        this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
        this.decoder = Objects.requireNonNull(decoder, "decoder must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        EnumMap<AiCapability, OpenAiResponsesProtocol.ServerPrompt> exact =
            new EnumMap<>(AiCapability.class);
        if (prompts != null) {
            exact.putAll(prompts);
        }
        if (!exact.keySet().equals(Set.of(
            AiCapability.APPROVAL_SUMMARY,
            AiCapability.MATERIAL_COMPLETENESS,
            AiCapability.RISK_SIGNALS
        ))) {
            throw new IllegalArgumentException("exactly three closed P6-E Prompts are required");
        }
        this.prompts = Map.copyOf(exact);
        this.outputLimits = Objects.requireNonNull(
            outputLimits,
            "outputLimits must not be null"
        );
        if (maximumOutputTokens < 1 || maximumOutputTokens > MAXIMUM_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive and bounded");
        }
        this.maximumOutputTokens = maximumOutputTokens;
    }

    @Override
    public AiProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AiProviderOutcome advise(
        AiProviderRequest request,
        AiCancellation cancellation
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        try {
            cancellation.throwIfCancellationRequested();
            OpenAiResponsesProtocol.ServerPrompt prompt = prompts.get(request.capability());
            if (prompt == null) {
                return failure(
                    AiOutcomeClassification.UNSUPPORTED,
                    "AI_OPENAI_CAPABILITY_UNSUPPORTED"
                );
            }
            OpenAiResponsesTransportPort.Request encoded = encoder.encode(
                request,
                prompt,
                outputLimits,
                maximumOutputTokens
            );
            OpenAiResponsesTransportPort.Request cancellable =
                new OpenAiResponsesTransportPort.Request(
                    encoded.bodyCopy(),
                    encoded.bodyHash(),
                    encoded.connectTimeout(),
                    encoded.totalTimeout(),
                    cancellation::isCancellationRequested
                );
            OpenAiResponsesTransportPort.Response response = transport.exchange(cancellable);
            if (!response.transportEvidence().verified()) {
                return failure(
                    AiOutcomeClassification.UNKNOWN,
                    "AI_OPENAI_TRANSPORT_EVIDENCE_MISSING"
                );
            }
            OpenAiResponsesProtocol.DecodedResponse decoded = decoder.decode(
                response,
                new OpenAiResponsesProtocol.DecodeExpectations(
                    request.versions(),
                    outputLimits,
                    request.inputFields().stream()
                        .map(AiProviderRequest.InputField::key)
                        .collect(Collectors.toUnmodifiableSet()),
                    response.transportEvidence().clientRequestIdHash()
                )
            );
            cancellation.throwIfCancellationRequested();
            return AiProviderOutcome.success(decoded.advisory());
        } catch (CancellationException exception) {
            return failure(AiOutcomeClassification.UNKNOWN, "AI_OPENAI_CANCELLED");
        } catch (OpenAiResponsesProtocol.ProtocolException exception) {
            return protocolFailure(exception.failure());
        } catch (OpenAiResponsesTransportException exception) {
            return transportFailure(exception.failure());
        } catch (RuntimeException exception) {
            return failure(AiOutcomeClassification.UNKNOWN, "AI_OPENAI_UNKNOWN");
        }
    }

    public static AiVersionReferences.ProviderVersion providerVersion() {
        return PROVIDER_VERSION;
    }

    public static AiVersionReferences.ModelVersion modelVersion() {
        return MODEL_VERSION;
    }

    public static AiVersionReferences.OutputSchemaVersion outputSchemaVersion() {
        return new AiVersionReferences.OutputSchemaVersion(
            OpenAiResponsesProtocol.OUTPUT_SCHEMA_ID,
            OpenAiResponsesProtocol.OUTPUT_SCHEMA_VERSION
        );
    }

    public static AiVersionReferences.PromptTemplateVersion promptVersion(
        AiCapability capability
    ) {
        OpenAiResponsesProtocol.ServerPrompt prompt = productionPrompts().get(
            Objects.requireNonNull(capability, "capability must not be null")
        );
        if (prompt == null) {
            throw new IllegalArgumentException("unsupported approval-assistance capability");
        }
        return new AiVersionReferences.PromptTemplateVersion(
            prompt.templateId(),
            prompt.version(),
            prompt.contentHash()
        );
    }

    public static Map<AiCapability, OpenAiResponsesProtocol.ServerPrompt> productionPrompts() {
        return Map.of(
            AiCapability.APPROVAL_SUMMARY,
            prompt("approval-summary", SUMMARY_INSTRUCTIONS),
            AiCapability.MATERIAL_COMPLETENESS,
            prompt("approval-material-completeness", MATERIAL_INSTRUCTIONS),
            AiCapability.RISK_SIGNALS,
            prompt("approval-risk-review", RISK_INSTRUCTIONS)
        );
    }

    private static OpenAiResponsesProtocol.ServerPrompt prompt(
        String templateId,
        String instructions
    ) {
        return new OpenAiResponsesProtocol.ServerPrompt(
            templateId,
            PROMPT_VERSION,
            OpenAiResponsesProtocol.sha256Utf8(instructions),
            instructions
        );
    }

    private static AiProviderDescriptor.CapabilityDescriptor capability(
        AiCapability capability
    ) {
        return new AiProviderDescriptor.CapabilityDescriptor(
            capability,
            true,
            400_000,
            5_000,
            32,
            false,
            false
        );
    }

    private static AiProviderOutcome protocolFailure(
        OpenAiResponsesProtocol.Failure failure
    ) {
        return switch (failure) {
            case REFUSAL -> failure(
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_OPENAI_REFUSAL"
            );
            case HTTP_STATUS_REJECTED, PROVIDER_ERROR -> failure(
                AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                "AI_OPENAI_PROVIDER_UNAVAILABLE"
            );
            case REQUEST_INVALID, REQUEST_TOO_LARGE -> failure(
                AiOutcomeClassification.REJECTED,
                "AI_OPENAI_REQUEST_REJECTED"
            );
            default -> failure(
                AiOutcomeClassification.INVALID_OUTPUT,
                "AI_OPENAI_OUTPUT_INVALID"
            );
        };
    }

    private static AiProviderOutcome transportFailure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return switch (failure) {
            case KILL_SWITCH_DISABLED -> failure(
                AiOutcomeClassification.DISABLED,
                "AI_OPENAI_DISABLED"
            );
            case KILL_SWITCH_DRIFT, CIRCUIT_OPEN, RATE_LIMITED,
                 COST_POLICY_STALE, COST_LIMIT_EXCEEDED -> failure(
                AiOutcomeClassification.POLICY_BLOCKED,
                "AI_OPENAI_POLICY_BLOCKED"
            );
            case CANCELLED, TIMEOUT -> failure(
                AiOutcomeClassification.TIMEOUT,
                "AI_OPENAI_TIMEOUT"
            );
            case REQUEST_INVALID, ENDPOINT_REJECTED -> failure(
                AiOutcomeClassification.REJECTED,
                "AI_OPENAI_REQUEST_REJECTED"
            );
            case DNS_FAILURE, DNS_EMPTY, DNS_UNSAFE, DNS_DRIFT, CONNECTION_DRIFT,
                 TLS_FAILURE, TLS_HOSTNAME_MISMATCH, TLS_CHAIN_INVALID,
                 TLS_CERTIFICATE_EXPIRED, SECRET_UNAVAILABLE, HTTP_PROTOCOL_INVALID,
                 REDIRECT_REJECTED, RESPONSE_TOO_LARGE, IO_FAILURE -> failure(
                AiOutcomeClassification.PROVIDER_UNAVAILABLE,
                "AI_OPENAI_PROVIDER_UNAVAILABLE"
            );
            case UNKNOWN -> failure(
                AiOutcomeClassification.UNKNOWN,
                "AI_OPENAI_UNKNOWN"
            );
        };
    }

    private static AiProviderOutcome failure(
        AiOutcomeClassification classification,
        String code
    ) {
        return AiProviderOutcome.failure(
            classification,
            code,
            "AI advisory generation did not produce trusted output",
            false
        );
    }
}
