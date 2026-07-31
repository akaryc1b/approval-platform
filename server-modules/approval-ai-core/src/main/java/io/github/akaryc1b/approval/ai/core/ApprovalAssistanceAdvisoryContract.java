package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bounded approval-assistance request and result contract.
 *
 * <p>This type validates structure only. It performs no Provider invocation, persistence,
 * application command or workflow-engine operation.</p>
 */
public final class ApprovalAssistanceAdvisoryContract {

    private static final double MEDIUM_CONFIDENCE_MINIMUM = 0.50d;
    private static final double HIGH_CONFIDENCE_MINIMUM = 0.80d;
    private static final String OUTPUT_SCHEMA_ID = "approval-assistance";

    private ApprovalAssistanceAdvisoryContract() {
    }

    public enum UseCase {
        SUMMARY(AiCapability.APPROVAL_SUMMARY, "approval-summary"),
        MATERIAL_COMPLETENESS(
            AiCapability.MATERIAL_COMPLETENESS,
            "approval-material-completeness"
        ),
        RISK_REVIEW(AiCapability.RISK_SIGNALS, "approval-risk-review");

        private final AiCapability capability;
        private final String promptTemplateId;

        UseCase(AiCapability capability, String promptTemplateId) {
            this.capability = capability;
            this.promptTemplateId = promptTemplateId;
        }

        public AiCapability capability() {
            return capability;
        }

        public String promptTemplateId() {
            return promptTemplateId;
        }
    }

    public record ResultLimits(
        int maximumObservations,
        int maximumRiskSignals,
        int maximumMissingMaterials,
        int maximumRecommendations,
        int maximumEvidenceReferences,
        int maximumLimitations
    ) {
        private static final int P2_MAXIMUM_ITEM_LIMIT = 25;
        private static final int P2_MAXIMUM_EVIDENCE_LIMIT = 64;
        private static final int P2_MAXIMUM_LIMITATION_LIMIT = 12;

        public ResultLimits {
            requirePositive(maximumObservations, "maximumObservations");
            requirePositive(maximumRiskSignals, "maximumRiskSignals");
            requirePositive(maximumMissingMaterials, "maximumMissingMaterials");
            requirePositive(maximumRecommendations, "maximumRecommendations");
            requirePositive(maximumEvidenceReferences, "maximumEvidenceReferences");
            requirePositive(maximumLimitations, "maximumLimitations");
            if (maximumObservations > P2_MAXIMUM_ITEM_LIMIT
                || maximumRiskSignals > P2_MAXIMUM_ITEM_LIMIT
                || maximumMissingMaterials > P2_MAXIMUM_ITEM_LIMIT
                || maximumRecommendations > P2_MAXIMUM_ITEM_LIMIT
                || maximumEvidenceReferences > P2_MAXIMUM_EVIDENCE_LIMIT
                || maximumLimitations > P2_MAXIMUM_LIMITATION_LIMIT) {
                throw new IllegalArgumentException(
                    "approval-assistance result limits exceed the P2 advisory bounds"
                );
            }
        }

        public static ResultLimits conservativeDefaults() {
            return new ResultLimits(25, 25, 25, 25, 64, 12);
        }
    }

    public record ProjectionProvenance(
        long resourceStateVersion,
        Instant resourceObservedAt,
        String formContentHash,
        String uiSchemaHash,
        int submissionRevision,
        AiVersionReferences.PolicyVersion dataPolicyVersion
    ) {
        public ProjectionProvenance {
            if (resourceStateVersion < 0) {
                throw new IllegalArgumentException(
                    "resourceStateVersion must not be negative"
                );
            }
            resourceObservedAt = Objects.requireNonNull(
                resourceObservedAt,
                "resourceObservedAt must not be null"
            );
            formContentHash = requireText(formContentHash, "formContentHash", 160);
            uiSchemaHash = requireText(uiSchemaHash, "uiSchemaHash", 160);
            if (submissionRevision < 0) {
                throw new IllegalArgumentException(
                    "submissionRevision must not be negative"
                );
            }
            dataPolicyVersion = Objects.requireNonNull(
                dataPolicyVersion,
                "dataPolicyVersion must not be null"
            );
        }

        public static ProjectionProvenance from(
            ApprovalAssistanceContextProjection projection
        ) {
            Objects.requireNonNull(projection, "projection must not be null");
            return new ProjectionProvenance(
                projection.resourceState().stateVersion(),
                projection.resourceState().observedAt(),
                projection.form().formContentHash(),
                projection.form().uiSchemaHash(),
                projection.form().submissionRevision(),
                projection.dataPolicyVersion()
            );
        }
    }

    public record Request(
        ApprovalAssistanceContextProjection projection,
        UseCase useCase,
        AiVersionReferences expectedVersions,
        ResultLimits resultLimits,
        ProjectionProvenance provenance,
        Instant requestedAt
    ) {
        public Request {
            projection = Objects.requireNonNull(projection, "projection must not be null");
            useCase = Objects.requireNonNull(useCase, "useCase must not be null");
            expectedVersions = Objects.requireNonNull(
                expectedVersions,
                "expectedVersions must not be null"
            );
            resultLimits = Objects.requireNonNull(
                resultLimits,
                "resultLimits must not be null"
            );
            provenance = Objects.requireNonNull(provenance, "provenance must not be null");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");

            Set<AiCapability> expectedCapabilities = Set.of(useCase.capability());
            if (!projection.providerRequirements().capabilities().equals(expectedCapabilities)) {
                throw new IllegalArgumentException(
                    "approval assistance requires exactly one use-case capability"
                );
            }
            if (projection.providerFields().isEmpty()) {
                throw new IllegalArgumentException(
                    "approval assistance requires at least one Provider-safe field"
                );
            }
            if (!expectedVersions.policy().equals(projection.dataPolicyVersion())) {
                throw new IllegalArgumentException(
                    "expected policy version must match the projection data policy"
                );
            }
            if (!expectedVersions.knowledgeSource().equals(
                AiVersionReferences.KnowledgeSourceVersion.none()
            )) {
                throw new IllegalArgumentException(
                    "P2 approval assistance does not permit a knowledge source"
                );
            }
            if (!expectedVersions.promptTemplate().templateId().equals(
                useCase.promptTemplateId()
            )) {
                throw new IllegalArgumentException(
                    "prompt template must match the exact approval-assistance use case"
                );
            }
            if (!expectedVersions.outputSchema().schemaId().equals(OUTPUT_SCHEMA_ID)) {
                throw new IllegalArgumentException(
                    "output schema must use the approval-assistance contract"
                );
            }
            if (!provenance.equals(ProjectionProvenance.from(projection))) {
                throw new IllegalArgumentException(
                    "request provenance must match the exact context projection"
                );
            }
            if (requestedAt.isBefore(provenance.resourceObservedAt())) {
                throw new IllegalArgumentException(
                    "request time must not precede the observed resource state"
                );
            }
        }
    }

    public record Result(Request request, AiAdvisoryResult advisory) {
        public Result {
            request = Objects.requireNonNull(request, "request must not be null");
            advisory = Objects.requireNonNull(advisory, "advisory must not be null");
            validateResult(request, advisory);
        }
    }

    private static void validateResult(Request request, AiAdvisoryResult advisory) {
        if (!advisory.needsHumanReview()
            || advisory.authority() != AiAdvisoryResult.Authority.ADVISORY
            || advisory.assertionStatus()
                != AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY) {
            throw new IllegalArgumentException(
                "approval assistance must remain unverified advisory material"
            );
        }
        if (!advisory.versions().equals(request.expectedVersions())) {
            throw new IllegalArgumentException(
                "advisory result versions must match the exact request versions"
            );
        }

        ResultLimits limits = request.resultLimits();
        requireSize(advisory.observations(), limits.maximumObservations(), "observations");
        requireSize(advisory.riskSignals(), limits.maximumRiskSignals(), "riskSignals");
        requireSize(
            advisory.missingMaterials(),
            limits.maximumMissingMaterials(),
            "missingMaterials"
        );
        requireSize(
            advisory.recommendations(),
            limits.maximumRecommendations(),
            "recommendations"
        );
        requireSize(
            advisory.evidenceReferences(),
            limits.maximumEvidenceReferences(),
            "evidenceReferences"
        );
        requireSize(advisory.limitations(), limits.maximumLimitations(), "limitations");

        validateConfidence(advisory.confidence());
        requireUniqueText(advisory.limitations(), "limitations");
        validateItemIds(advisory);
        validateEvidence(request, advisory);
    }

    private static void validateConfidence(AiAdvisoryResult.Confidence confidence) {
        AiAdvisoryResult.ConfidenceBand expectedBand;
        if (confidence.score() >= HIGH_CONFIDENCE_MINIMUM) {
            expectedBand = AiAdvisoryResult.ConfidenceBand.HIGH;
        } else if (confidence.score() >= MEDIUM_CONFIDENCE_MINIMUM) {
            expectedBand = AiAdvisoryResult.ConfidenceBand.MEDIUM;
        } else {
            expectedBand = AiAdvisoryResult.ConfidenceBand.LOW;
        }
        if (confidence.band() != expectedBand) {
            throw new IllegalArgumentException(
                "confidence band must match the bounded approval-assistance thresholds"
            );
        }
    }

    private static void validateItemIds(AiAdvisoryResult advisory) {
        Set<String> ids = new HashSet<>();
        advisory.observations().forEach(item -> addUnique(ids, item.id(), "advisory item"));
        advisory.riskSignals().forEach(item -> addUnique(ids, item.id(), "advisory item"));
        advisory.missingMaterials().forEach(item -> addUnique(ids, item.id(), "advisory item"));
        advisory.recommendations().forEach(item -> addUnique(ids, item.id(), "advisory item"));
    }

    private static void validateEvidence(Request request, AiAdvisoryResult advisory) {
        Set<String> providerFieldKeys = request.projection().providerFields().stream()
            .map(field -> field.key())
            .collect(Collectors.toUnmodifiableSet());

        Map<String, AiAdvisoryResult.EvidenceReference> evidenceById;
        try {
            evidenceById = advisory.evidenceReferences().stream().collect(Collectors.toUnmodifiableMap(
                AiAdvisoryResult.EvidenceReference::id,
                Function.identity()
            ));
        } catch (IllegalStateException duplicate) {
            throw new IllegalArgumentException(
                "evidence reference IDs must be unique",
                duplicate
            );
        }
        if (evidenceById.isEmpty()) {
            throw new IllegalArgumentException(
                "approval assistance requires Provider-safe evidence references"
            );
        }

        for (AiAdvisoryResult.EvidenceReference evidence : evidenceById.values()) {
            if (!providerFieldKeys.contains(evidence.fieldKey())) {
                throw new IllegalArgumentException(
                    "evidence reference field is not present in the Provider-safe projection"
                );
            }
        }

        Set<String> usedEvidenceIds = new HashSet<>();
        advisory.observations().forEach(item -> requireEvidence(
            item.evidenceReferenceIds(),
            evidenceById,
            usedEvidenceIds
        ));
        advisory.riskSignals().forEach(item -> requireEvidence(
            item.evidenceReferenceIds(),
            evidenceById,
            usedEvidenceIds
        ));
        advisory.recommendations().forEach(item -> requireEvidence(
            item.evidenceReferenceIds(),
            evidenceById,
            usedEvidenceIds
        ));
        if (!usedEvidenceIds.equals(evidenceById.keySet())) {
            throw new IllegalArgumentException(
                "every declared evidence reference must support an advisory item"
            );
        }
    }

    private static void requireEvidence(
        List<String> evidenceReferenceIds,
        Map<String, AiAdvisoryResult.EvidenceReference> evidenceById,
        Set<String> usedEvidenceIds
    ) {
        if (evidenceReferenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                "observations, risk signals and recommendations require evidence"
            );
        }
        Set<String> local = new HashSet<>();
        for (String id : evidenceReferenceIds) {
            if (!local.add(id)) {
                throw new IllegalArgumentException(
                    "one advisory item must not repeat an evidence reference ID"
                );
            }
            if (!evidenceById.containsKey(id)) {
                throw new IllegalArgumentException(
                    "advisory item contains an unresolved evidence reference ID"
                );
            }
            usedEvidenceIds.add(id);
        }
    }

    private static void requireUniqueText(List<String> values, String name) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicates");
        }
    }

    private static void addUnique(Set<String> values, String value, String name) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(name + " IDs must be globally unique");
        }
    }

    private static void requireSize(List<?> values, int maximum, String name) {
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " exceed the request result limit");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
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
