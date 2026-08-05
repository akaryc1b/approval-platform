package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed P6-C protocol vocabulary for deterministic OpenAI Responses encoding and decoding. */
public final class OpenAiResponsesProtocol {

    public static final String PROVIDER_ID = "openai-responses";
    public static final String PROVIDER_VERSION = "responses-v1";
    public static final String MODEL_ID = "gpt-5-mini";
    public static final String MODEL_VERSION = "2025-08-07";
    public static final String MODEL_SNAPSHOT = "gpt-5-mini-2025-08-07";
    public static final String OUTPUT_SCHEMA_ID = "approval-assistance";
    public static final int OUTPUT_SCHEMA_VERSION = 1;
    public static final String RESPONSE_FORMAT_NAME = "approval_assistance_v1";
    public static final int MAXIMUM_REQUEST_BYTES = 262_144;
    public static final int MAXIMUM_RESPONSE_BYTES = 262_144;
    public static final int MAXIMUM_TRANSPORT_RESPONSE_BYTES = 524_288;
    public static final int MAXIMUM_PROMPT_CHARACTERS = 12_000;
    public static final int MAXIMUM_STRUCTURED_OUTPUT_CHARACTERS = 240_000;

    private static final double MEDIUM_CONFIDENCE_MINIMUM = 0.50d;
    private static final double HIGH_CONFIDENCE_MINIMUM = 0.80d;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> PROMPT_TEMPLATE_IDS = Set.of(
        "approval-summary",
        "approval-material-completeness",
        "approval-risk-review"
    );

    private OpenAiResponsesProtocol() {
    }

    public record ServerPrompt(
        String templateId,
        String version,
        String contentHash,
        String instructions
    ) {
        public ServerPrompt {
            templateId = requireText(templateId, "templateId", 160);
            if (!PROMPT_TEMPLATE_IDS.contains(templateId)) {
                throw new IllegalArgumentException(
                    "templateId must use one accepted approval-assistance Prompt"
                );
            }
            version = requireText(version, "version", 120);
            contentHash = requireText(contentHash, "contentHash", 160);
            instructions = requireText(
                instructions,
                "instructions",
                MAXIMUM_PROMPT_CHARACTERS
            );
        }
    }

    public record OutputLimits(
        int maximumObservations,
        int maximumRiskSignals,
        int maximumMissingMaterials,
        int maximumRecommendations,
        int maximumEvidenceReferences,
        int maximumLimitations
    ) {
        private static final int MAXIMUM_ITEMS = 25;
        private static final int MAXIMUM_EVIDENCE = 64;
        private static final int MAXIMUM_LIMITATIONS = 12;

        public OutputLimits {
            requirePositive(maximumObservations, "maximumObservations");
            requirePositive(maximumRiskSignals, "maximumRiskSignals");
            requirePositive(maximumMissingMaterials, "maximumMissingMaterials");
            requirePositive(maximumRecommendations, "maximumRecommendations");
            requirePositive(maximumEvidenceReferences, "maximumEvidenceReferences");
            requirePositive(maximumLimitations, "maximumLimitations");
            if (maximumObservations > MAXIMUM_ITEMS
                || maximumRiskSignals > MAXIMUM_ITEMS
                || maximumMissingMaterials > MAXIMUM_ITEMS
                || maximumRecommendations > MAXIMUM_ITEMS
                || maximumEvidenceReferences > MAXIMUM_EVIDENCE
                || maximumLimitations > MAXIMUM_LIMITATIONS) {
                throw new IllegalArgumentException(
                    "output limits exceed the accepted P2 bounds"
                );
            }
        }

        public static OutputLimits conservativeDefaults() {
            return new OutputLimits(25, 25, 25, 25, 64, 12);
        }
    }

    public record DecodeExpectations(
        AiVersionReferences versions,
        OutputLimits limits,
        Set<String> providerFieldKeys,
        String admittedRequestIdHash
    ) {
        public DecodeExpectations {
            versions = Objects.requireNonNull(versions, "versions must not be null");
            limits = Objects.requireNonNull(limits, "limits must not be null");
            providerFieldKeys = providerFieldKeys == null
                ? Set.of()
                : Set.copyOf(providerFieldKeys);
            if (providerFieldKeys.isEmpty() || providerFieldKeys.size() > 500) {
                throw new IllegalArgumentException(
                    "providerFieldKeys must be non-empty and bounded"
                );
            }
            admittedRequestIdHash = requireSha256(
                admittedRequestIdHash,
                "admittedRequestIdHash"
            );
        }
    }

    public record Usage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long cachedInputTokens,
        long reasoningTokens
    ) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || cachedInputTokens < 0 || reasoningTokens < 0) {
                throw new IllegalArgumentException("usage counts must not be negative");
            }
            if (totalTokens != inputTokens + outputTokens) {
                throw new IllegalArgumentException(
                    "totalTokens must equal inputTokens plus outputTokens"
                );
            }
            if (cachedInputTokens > inputTokens || reasoningTokens > outputTokens) {
                throw new IllegalArgumentException(
                    "usage detail counts must fit their parent counts"
                );
            }
        }
    }

    public record DecodedResponse(
        AiAdvisoryResult advisory,
        Usage usage,
        String requestIdHash,
        String responseIdHash
    ) {
        public DecodedResponse {
            advisory = Objects.requireNonNull(advisory, "advisory must not be null");
            if (!confidenceBandMatches(advisory.confidence())) {
                throw failure(Failure.RESULT_INVALID);
            }
            usage = Objects.requireNonNull(usage, "usage must not be null");
            requestIdHash = requireSha256(requestIdHash, "requestIdHash");
            responseIdHash = requireSha256(responseIdHash, "responseIdHash");
        }
    }

    public enum Failure {
        REQUEST_INVALID,
        REQUEST_TOO_LARGE,
        HTTP_STATUS_REJECTED,
        REQUEST_ID_MISSING,
        REQUEST_ID_MISMATCH,
        RESPONSE_TOO_LARGE,
        INVALID_UTF8,
        MALFORMED_JSON,
        DUPLICATE_PROPERTY,
        UNKNOWN_PROPERTY,
        RESPONSE_STATUS_REJECTED,
        PROVIDER_ERROR,
        REFUSAL,
        OUTPUT_NOT_EXACT,
        MODEL_MISMATCH,
        SCHEMA_MISMATCH,
        VERSION_MISMATCH,
        USAGE_INVALID,
        RESULT_INVALID
    }

    public static final class ProtocolException extends RuntimeException {

        private final Failure failure;

        public ProtocolException(Failure failure) {
            super(Objects.requireNonNull(failure, "failure must not be null").name());
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }

    public static String sha256(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 must be available", failure);
        }
    }

    public static String sha256Utf8(String value) {
        return sha256(requireText(value, "value", 1_000_000)
            .getBytes(StandardCharsets.UTF_8));
    }

    static ProtocolException failure(Failure failure) {
        return new ProtocolException(failure);
    }

    static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    private static boolean confidenceBandMatches(AiAdvisoryResult.Confidence confidence) {
        double score = confidence.score();
        return switch (confidence.band()) {
            case LOW -> score < MEDIUM_CONFIDENCE_MINIMUM;
            case MEDIUM -> score >= MEDIUM_CONFIDENCE_MINIMUM
                && score < HIGH_CONFIDENCE_MINIMUM;
            case HIGH -> score >= HIGH_CONFIDENCE_MINIMUM;
        };
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
