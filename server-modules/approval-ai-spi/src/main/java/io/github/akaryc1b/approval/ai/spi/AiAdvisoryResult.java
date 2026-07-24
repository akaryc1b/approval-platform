package io.github.akaryc1b.approval.ai.spi;

import java.util.List;
import java.util.Objects;

/**
 * Structured advisory output.
 *
 * <p>The first safe slice has no decision or command field. Every statement is explicitly
 * unverified advisory material and every result requires human review.</p>
 */
public record AiAdvisoryResult(
    String summary,
    List<Observation> observations,
    List<RiskSignal> riskSignals,
    List<MissingMaterial> missingMaterials,
    List<Recommendation> recommendations,
    List<EvidenceReference> evidenceReferences,
    Confidence confidence,
    List<String> limitations,
    boolean needsHumanReview,
    AiVersionReferences versions,
    Authority authority,
    AssertionStatus assertionStatus
) {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_EVIDENCE_ITEMS = 200;
    private static final int MAX_LIMITATIONS = 20;

    public AiAdvisoryResult {
        summary = requireText(summary, "summary", 4_000);
        observations = boundedList(observations, "observations", MAX_ITEMS);
        riskSignals = boundedList(riskSignals, "riskSignals", MAX_ITEMS);
        missingMaterials = boundedList(missingMaterials, "missingMaterials", MAX_ITEMS);
        recommendations = boundedList(recommendations, "recommendations", MAX_ITEMS);
        evidenceReferences = boundedList(
            evidenceReferences,
            "evidenceReferences",
            MAX_EVIDENCE_ITEMS
        );
        confidence = Objects.requireNonNull(confidence, "confidence must not be null");
        limitations = limitations == null
            ? List.of()
            : limitations.stream()
                .map(value -> requireText(value, "limitation", 1_000))
                .toList();
        if (limitations.isEmpty() || limitations.size() > MAX_LIMITATIONS) {
            throw new IllegalArgumentException("limitations must be non-empty and bounded");
        }
        if (!needsHumanReview) {
            throw new IllegalArgumentException(
                "M6-D first-slice advisory results must require human review"
            );
        }
        versions = Objects.requireNonNull(versions, "versions must not be null");
        authority = authority == null ? Authority.ADVISORY : authority;
        if (authority != Authority.ADVISORY) {
            throw new IllegalArgumentException("AI result authority must remain ADVISORY");
        }
        assertionStatus = assertionStatus == null
            ? AssertionStatus.UNVERIFIED_ADVISORY
            : assertionStatus;
        if (assertionStatus != AssertionStatus.UNVERIFIED_ADVISORY) {
            throw new IllegalArgumentException(
                "AI result statements must remain UNVERIFIED_ADVISORY"
            );
        }
    }

    public record Observation(String id, String text, List<String> evidenceReferenceIds) {
        public Observation {
            id = requireText(id, "observation.id", 120);
            text = requireText(text, "observation.text", 2_000);
            evidenceReferenceIds = boundedIds(evidenceReferenceIds);
        }
    }

    public record RiskSignal(
        String id,
        RiskSeverity severity,
        String text,
        List<String> evidenceReferenceIds
    ) {
        public RiskSignal {
            id = requireText(id, "riskSignal.id", 120);
            severity = Objects.requireNonNull(severity, "risk severity must not be null");
            text = requireText(text, "riskSignal.text", 2_000);
            evidenceReferenceIds = boundedIds(evidenceReferenceIds);
        }
    }

    public record MissingMaterial(String id, String materialType, String reason) {
        public MissingMaterial {
            id = requireText(id, "missingMaterial.id", 120);
            materialType = requireText(materialType, "missingMaterial.materialType", 160);
            reason = requireText(reason, "missingMaterial.reason", 2_000);
        }
    }

    public record Recommendation(
        String id,
        RecommendationType type,
        String text,
        List<String> evidenceReferenceIds
    ) {
        public Recommendation {
            id = requireText(id, "recommendation.id", 120);
            type = Objects.requireNonNull(type, "recommendation type must not be null");
            text = requireText(text, "recommendation.text", 2_000);
            evidenceReferenceIds = boundedIds(evidenceReferenceIds);
        }
    }

    public record EvidenceReference(
        String id,
        String fieldKey,
        String description
    ) {
        public EvidenceReference {
            id = requireText(id, "evidenceReference.id", 120);
            fieldKey = requireText(fieldKey, "evidenceReference.fieldKey", 160);
            description = requireText(description, "evidenceReference.description", 1_000);
        }
    }

    public record Confidence(double score, ConfidenceBand band) {
        public Confidence {
            if (Double.isNaN(score) || score < 0.0d || score > 1.0d) {
                throw new IllegalArgumentException("confidence score must be between 0 and 1");
            }
            band = Objects.requireNonNull(band, "confidence band must not be null");
        }
    }

    public enum RiskSeverity {
        INFO,
        LOW,
        MEDIUM,
        HIGH
    }

    public enum RecommendationType {
        REQUEST_INFORMATION,
        VERIFY_EVIDENCE,
        REVIEW_RISK,
        SEEK_SPECIALIST_REVIEW,
        NO_ACTION_SUGGESTED
    }

    public enum ConfidenceBand {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum Authority {
        ADVISORY
    }

    public enum AssertionStatus {
        UNVERIFIED_ADVISORY
    }

    private static List<String> boundedIds(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_EVIDENCE_ITEMS) {
            throw new IllegalArgumentException("evidence reference IDs must be bounded");
        }
        return values.stream()
            .map(value -> requireText(value, "evidenceReferenceId", 120))
            .toList();
    }

    private static <T> List<T> boundedList(List<T> values, String name, int maximumSize) {
        List<T> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > maximumSize) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return copy;
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
