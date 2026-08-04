package io.github.akaryc1b.approval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.AssertionStatus;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.Authority;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed, low-information P6-E generation API contract. */
public final class ApprovalAssistanceGenerationContracts {

    private static final Set<String> REQUEST_FIELDS = Set.of("useCase");

    private ApprovalAssistanceGenerationContracts() {
    }

    public static GenerationRequest parseRequest(JsonNode body) {
        if (!(body instanceof ObjectNode object)
            || object.size() != 1
            || !object.fieldNames().next().equals("useCase")) {
            throw new InvalidGenerationRequestException();
        }
        JsonNode useCase = object.get("useCase");
        if (useCase == null || !useCase.isTextual()) {
            throw new InvalidGenerationRequestException();
        }
        try {
            return new GenerationRequest(UseCase.valueOf(useCase.textValue()));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidGenerationRequestException();
        }
    }

    public record GenerationRequest(UseCase useCase) {
        public GenerationRequest {
            useCase = Objects.requireNonNull(useCase, "useCase must not be null");
        }
    }

    public enum ResultStatus {
        SUCCESS,
        LOW_CONFIDENCE,
        DISABLED,
        NOT_FOUND,
        STALE_TASK,
        POLICY_BLOCKED,
        PROVIDER_UNAVAILABLE,
        TIMEOUT,
        INVALID_OUTPUT,
        UNKNOWN,
        EVIDENCE_CONFLICT,
        EVIDENCE_UNAVAILABLE,
        INVALID_REQUEST
    }

    public record GenerationView(
        ResultStatus status,
        String code,
        Authority authority,
        AssertionStatus assertionStatus,
        boolean needsHumanReview,
        boolean commandAvailable,
        boolean providerSelectable,
        boolean retryAttempted,
        boolean fallbackAttempted,
        UUID evidenceId,
        AdvisoryView advisoryResult
    ) {
        public GenerationView {
            status = Objects.requireNonNull(status, "status must not be null");
            code = requireText(code, "code", 128);
            authority = Objects.requireNonNull(authority, "authority must not be null");
            assertionStatus = Objects.requireNonNull(
                assertionStatus,
                "assertionStatus must not be null"
            );
            if (authority != Authority.ADVISORY
                || assertionStatus != AssertionStatus.UNVERIFIED_ADVISORY
                || !needsHumanReview
                || commandAvailable
                || providerSelectable
                || retryAttempted
                || fallbackAttempted) {
                throw new IllegalArgumentException(
                    "generation view must remain advisory-only without retry or fallback"
                );
            }
            boolean successful = status == ResultStatus.SUCCESS
                || status == ResultStatus.LOW_CONFIDENCE;
            if (successful != (advisoryResult != null && evidenceId != null)) {
                throw new IllegalArgumentException(
                    "successful generation requires advisory and durable evidence"
                );
            }
            if (!successful && (advisoryResult != null || evidenceId != null)) {
                throw new IllegalArgumentException(
                    "failure response must not expose partial Provider or evidence details"
                );
            }
        }

        public static GenerationView success(
            boolean lowConfidence,
            UUID evidenceId,
            AiAdvisoryResult advisory
        ) {
            return new GenerationView(
                lowConfidence ? ResultStatus.LOW_CONFIDENCE : ResultStatus.SUCCESS,
                lowConfidence
                    ? "AI_ASSISTANCE_LOW_CONFIDENCE"
                    : "AI_ASSISTANCE_GENERATED",
                Authority.ADVISORY,
                AssertionStatus.UNVERIFIED_ADVISORY,
                true,
                false,
                false,
                false,
                false,
                Objects.requireNonNull(evidenceId, "evidenceId must not be null"),
                AdvisoryView.from(advisory)
            );
        }

        public static GenerationView failure(ResultStatus status, String code) {
            if (status == ResultStatus.SUCCESS || status == ResultStatus.LOW_CONFIDENCE) {
                throw new IllegalArgumentException("failure status must not be successful");
            }
            return new GenerationView(
                status,
                code,
                Authority.ADVISORY,
                AssertionStatus.UNVERIFIED_ADVISORY,
                true,
                false,
                false,
                false,
                false,
                null,
                null
            );
        }
    }

    public record AdvisoryView(
        String summary,
        List<ObservationView> observations,
        List<RiskSignalView> riskSignals,
        List<MissingMaterialView> missingMaterials,
        List<RecommendationView> recommendations,
        List<EvidenceReferenceView> evidenceReferences,
        ConfidenceView confidence,
        List<String> limitations
    ) {
        public AdvisoryView {
            summary = requireText(summary, "summary", 4_000);
            observations = immutable(observations);
            riskSignals = immutable(riskSignals);
            missingMaterials = immutable(missingMaterials);
            recommendations = immutable(recommendations);
            evidenceReferences = immutable(evidenceReferences);
            confidence = Objects.requireNonNull(confidence, "confidence must not be null");
            limitations = immutable(limitations);
            if (limitations.isEmpty()) {
                throw new IllegalArgumentException("limitations must not be empty");
            }
        }

        public static AdvisoryView from(AiAdvisoryResult result) {
            Objects.requireNonNull(result, "result must not be null");
            if (!result.needsHumanReview()
                || result.authority() != Authority.ADVISORY
                || result.assertionStatus() != AssertionStatus.UNVERIFIED_ADVISORY) {
                throw new IllegalArgumentException("only unverified advisory output is exposable");
            }
            return new AdvisoryView(
                result.summary(),
                result.observations().stream().map(ObservationView::from).toList(),
                result.riskSignals().stream().map(RiskSignalView::from).toList(),
                result.missingMaterials().stream().map(MissingMaterialView::from).toList(),
                result.recommendations().stream().map(RecommendationView::from).toList(),
                result.evidenceReferences().stream().map(EvidenceReferenceView::from).toList(),
                new ConfidenceView(
                    result.confidence().score(),
                    result.confidence().band().name()
                ),
                result.limitations()
            );
        }
    }

    public record ObservationView(
        String id,
        String text,
        List<String> evidenceReferenceIds
    ) {
        static ObservationView from(AiAdvisoryResult.Observation value) {
            return new ObservationView(value.id(), value.text(), value.evidenceReferenceIds());
        }

        public ObservationView {
            id = requireText(id, "observation.id", 120);
            text = requireText(text, "observation.text", 2_000);
            evidenceReferenceIds = immutable(evidenceReferenceIds);
        }
    }

    public record RiskSignalView(
        String id,
        String severity,
        String text,
        List<String> evidenceReferenceIds
    ) {
        static RiskSignalView from(AiAdvisoryResult.RiskSignal value) {
            return new RiskSignalView(
                value.id(),
                value.severity().name(),
                value.text(),
                value.evidenceReferenceIds()
            );
        }

        public RiskSignalView {
            id = requireText(id, "riskSignal.id", 120);
            severity = requireText(severity, "riskSignal.severity", 32);
            text = requireText(text, "riskSignal.text", 2_000);
            evidenceReferenceIds = immutable(evidenceReferenceIds);
        }
    }

    public record MissingMaterialView(String id, String materialType, String reason) {
        static MissingMaterialView from(AiAdvisoryResult.MissingMaterial value) {
            return new MissingMaterialView(value.id(), value.materialType(), value.reason());
        }

        public MissingMaterialView {
            id = requireText(id, "missingMaterial.id", 120);
            materialType = requireText(materialType, "missingMaterial.materialType", 160);
            reason = requireText(reason, "missingMaterial.reason", 2_000);
        }
    }

    public record RecommendationView(
        String id,
        String type,
        String text,
        List<String> evidenceReferenceIds
    ) {
        static RecommendationView from(AiAdvisoryResult.Recommendation value) {
            return new RecommendationView(
                value.id(),
                value.type().name(),
                value.text(),
                value.evidenceReferenceIds()
            );
        }

        public RecommendationView {
            id = requireText(id, "recommendation.id", 120);
            type = requireText(type, "recommendation.type", 64);
            text = requireText(text, "recommendation.text", 2_000);
            evidenceReferenceIds = immutable(evidenceReferenceIds);
        }
    }

    public record EvidenceReferenceView(String id, String fieldKey, String description) {
        static EvidenceReferenceView from(AiAdvisoryResult.EvidenceReference value) {
            return new EvidenceReferenceView(value.id(), value.fieldKey(), value.description());
        }

        public EvidenceReferenceView {
            id = requireText(id, "evidenceReference.id", 120);
            fieldKey = requireText(fieldKey, "evidenceReference.fieldKey", 160);
            description = requireText(description, "evidenceReference.description", 1_000);
        }
    }

    public record ConfidenceView(double score, String band) {
        public ConfidenceView {
            if (Double.isNaN(score) || score < 0.0d || score > 1.0d) {
                throw new IllegalArgumentException("confidence score must be bounded");
            }
            band = requireText(band, "confidence.band", 32);
        }
    }

    public static final class InvalidGenerationRequestException extends RuntimeException {

        public InvalidGenerationRequestException() {
            super("AI_ASSISTANCE_REQUEST_INVALID");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
