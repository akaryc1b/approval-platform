package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceSynchronousOrchestrator.Outcome;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Hash-only durable evidence for one bounded approval-assistance outcome.
 *
 * <p>No Provider-safe field value, Prompt body, summary, observation, risk text, recommendation,
 * limitation, failure message or raw Provider response is retained by this contract.</p>
 */
public record ApprovalAssistanceDurableEvidence(
    UUID evidenceId,
    String tenantId,
    String requestEvidenceHash,
    String subjectEvidenceHash,
    String resourceEvidenceHash,
    String projectionEvidenceHash,
    String executionEvidenceHash,
    String routeEvidenceHash,
    String versionEvidenceHash,
    String outcomeEvidenceHash,
    UseCase useCase,
    AiOutcomeClassification classification,
    AiVersionReferences versions,
    int providerAttempts,
    boolean providerInvocationStarted,
    boolean retryAttempted,
    boolean postInvocationFallbackAttempted,
    long killSwitchGeneration,
    boolean advisoryResultPresent,
    AdvisoryCounts advisoryCounts,
    Double confidenceScore,
    AiAdvisoryResult.ConfidenceBand confidenceBand,
    Instant requestedAt,
    Instant recordedAt,
    Instant retentionUntil,
    String evidenceHash
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Duration MAXIMUM_RETENTION = Duration.ofDays(3_650);

    public ApprovalAssistanceDurableEvidence {
        evidenceId = Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        tenantId = requireText(tenantId, "tenantId", 128);
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        subjectEvidenceHash = requireSha256(subjectEvidenceHash, "subjectEvidenceHash");
        resourceEvidenceHash = requireSha256(resourceEvidenceHash, "resourceEvidenceHash");
        projectionEvidenceHash = requireSha256(
            projectionEvidenceHash,
            "projectionEvidenceHash"
        );
        executionEvidenceHash = requireSha256(
            executionEvidenceHash,
            "executionEvidenceHash"
        );
        routeEvidenceHash = optionalSha256(routeEvidenceHash, "routeEvidenceHash");
        versionEvidenceHash = requireSha256(versionEvidenceHash, "versionEvidenceHash");
        outcomeEvidenceHash = requireSha256(outcomeEvidenceHash, "outcomeEvidenceHash");
        useCase = Objects.requireNonNull(useCase, "useCase must not be null");
        classification = Objects.requireNonNull(
            classification,
            "classification must not be null"
        );
        versions = Objects.requireNonNull(versions, "versions must not be null");
        advisoryCounts = Objects.requireNonNull(
            advisoryCounts,
            "advisoryCounts must not be null"
        );
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        retentionUntil = Objects.requireNonNull(
            retentionUntil,
            "retentionUntil must not be null"
        );
        evidenceHash = requireSha256(evidenceHash, "evidenceHash");

        requireVersionEvidence(versions, versionEvidenceHash);
        requireInvocationEvidence(
            providerAttempts,
            providerInvocationStarted,
            retryAttempted,
            postInvocationFallbackAttempted,
            killSwitchGeneration,
            routeEvidenceHash
        );
        requireResultEvidence(
            classification,
            advisoryResultPresent,
            advisoryCounts,
            confidenceScore,
            confidenceBand
        );
        requireRetention(requestedAt, recordedAt, retentionUntil);

        String expected = computeEvidenceHash(
            evidenceId,
            tenantId,
            requestEvidenceHash,
            subjectEvidenceHash,
            resourceEvidenceHash,
            projectionEvidenceHash,
            executionEvidenceHash,
            routeEvidenceHash,
            versionEvidenceHash,
            outcomeEvidenceHash,
            useCase,
            classification,
            providerAttempts,
            providerInvocationStarted,
            retryAttempted,
            postInvocationFallbackAttempted,
            killSwitchGeneration,
            advisoryResultPresent,
            advisoryCounts,
            confidenceScore,
            confidenceBand,
            requestedAt,
            recordedAt,
            retentionUntil
        );
        if (!evidenceHash.equals(expected)) {
            throw new IllegalArgumentException(
                "evidenceHash must match canonical P4 durable evidence"
            );
        }
    }

    public static ApprovalAssistanceDurableEvidence create(
        UUID evidenceId,
        Outcome outcome,
        AiAdvisoryExecutionEvidence executionEvidence,
        Instant recordedAt,
        Instant retentionUntil
    ) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(
            executionEvidence,
            "executionEvidence must not be null"
        );
        Request request = outcome.request();
        AiOutcomeClassification classification = outcome.coordinated()
            .outcome().classification();
        requireExecutionMatch(request, outcome, executionEvidence, classification);

        AiAdvisoryResult advisory = outcome.acceptedResult() == null
            ? null
            : outcome.acceptedResult().advisory();
        AdvisoryCounts counts = AdvisoryCounts.from(advisory);
        Double score = advisory == null ? null : advisory.confidence().score();
        AiAdvisoryResult.ConfidenceBand band = advisory == null
            ? null
            : advisory.confidence().band();
        String projectionHash = projectionEvidenceHash(request);
        String versionsHash = versionEvidenceHash(request.expectedVersions());
        String outcomeHash = outcomeEvidenceHash(outcome, advisory, counts);
        String tenantId = request.projection().requestContext().tenantId();
        String canonicalHash = computeEvidenceHash(
            evidenceId,
            tenantId,
            executionEvidence.requestEvidenceHash(),
            executionEvidence.subjectEvidenceHash(),
            executionEvidence.resourceEvidenceHash(),
            projectionHash,
            executionEvidence.evidenceHash(),
            executionEvidence.routeEvidenceHash(),
            versionsHash,
            outcomeHash,
            request.useCase(),
            classification,
            outcome.providerAttempts(),
            outcome.coordinated().providerInvocationStarted(),
            outcome.retryAttempted(),
            outcome.coordinated().postInvocationFallbackAttempted(),
            outcome.killSwitchGeneration(),
            advisory != null,
            counts,
            score,
            band,
            request.requestedAt(),
            recordedAt,
            retentionUntil
        );
        return new ApprovalAssistanceDurableEvidence(
            evidenceId,
            tenantId,
            executionEvidence.requestEvidenceHash(),
            executionEvidence.subjectEvidenceHash(),
            executionEvidence.resourceEvidenceHash(),
            projectionHash,
            executionEvidence.evidenceHash(),
            executionEvidence.routeEvidenceHash(),
            versionsHash,
            outcomeHash,
            request.useCase(),
            classification,
            request.expectedVersions(),
            outcome.providerAttempts(),
            outcome.coordinated().providerInvocationStarted(),
            outcome.retryAttempted(),
            outcome.coordinated().postInvocationFallbackAttempted(),
            outcome.killSwitchGeneration(),
            advisory != null,
            counts,
            score,
            band,
            request.requestedAt(),
            recordedAt,
            retentionUntil,
            canonicalHash
        );
    }

    private static void requireExecutionMatch(
        Request request,
        Outcome outcome,
        AiAdvisoryExecutionEvidence execution,
        AiOutcomeClassification classification
    ) {
        if (execution.capability() != request.useCase().capability()
            || execution.resultClassification() != classification
            || execution.providerInvocationStarted()
                != outcome.coordinated().providerInvocationStarted()
            || execution.postInvocationFallbackAttempted()
                != outcome.coordinated().postInvocationFallbackAttempted()) {
            throw new IllegalArgumentException(
                "execution evidence must match the exact P3 outcome"
            );
        }
        if (execution.versions() != null
            && !execution.versions().equals(request.expectedVersions())) {
            throw new IllegalArgumentException(
                "execution evidence versions must match the exact P2 request"
            );
        }
    }

    private static void requireVersionEvidence(
        AiVersionReferences versions,
        String versionsHash
    ) {
        if (!versions.knowledgeSource().equals(
            AiVersionReferences.KnowledgeSourceVersion.none()
        )) {
            throw new IllegalArgumentException(
                "P4 durable evidence does not permit customer knowledge metadata"
            );
        }
        if (!versionsHash.equals(versionEvidenceHash(versions))) {
            throw new IllegalArgumentException(
                "versionEvidenceHash must match exact Provider/model/Prompt/policy/schema versions"
            );
        }
    }

    private static void requireInvocationEvidence(
        int attempts,
        boolean invocationStarted,
        boolean retryAttempted,
        boolean fallbackAttempted,
        long killSwitchGeneration,
        String routeHash
    ) {
        if (attempts < 0 || attempts > 1 || invocationStarted != (attempts == 1)) {
            throw new IllegalArgumentException(
                "Provider attempts must be zero or one and match invocation evidence"
            );
        }
        if (retryAttempted || fallbackAttempted) {
            throw new IllegalArgumentException(
                "P4 evidence cannot represent retry or post-invocation fallback"
            );
        }
        if (killSwitchGeneration < 1) {
            throw new IllegalArgumentException("killSwitchGeneration must be positive");
        }
        if (invocationStarted && routeHash == null) {
            throw new IllegalArgumentException(
                "started Provider invocation requires exact route evidence"
            );
        }
    }

    private static void requireResultEvidence(
        AiOutcomeClassification classification,
        boolean resultPresent,
        AdvisoryCounts counts,
        Double confidenceScore,
        AiAdvisoryResult.ConfidenceBand confidenceBand
    ) {
        boolean resultClassification = classification == AiOutcomeClassification.SUCCESS
            || classification == AiOutcomeClassification.LOW_CONFIDENCE;
        if (resultPresent != resultClassification) {
            throw new IllegalArgumentException(
                "advisoryResultPresent must match the final result classification"
            );
        }
        if (resultPresent) {
            if (confidenceScore == null
                || confidenceBand == null
                || Double.isNaN(confidenceScore)
                || confidenceScore < 0.0d
                || confidenceScore > 1.0d
                || counts.evidenceReferenceCount() < 1) {
                throw new IllegalArgumentException(
                    "advisory result evidence requires bounded confidence and evidence metadata"
                );
            }
        } else if (!counts.empty()
            || confidenceScore != null
            || confidenceBand != null) {
            throw new IllegalArgumentException(
                "failure evidence must not manufacture advisory result metadata"
            );
        }
    }

    private static void requireRetention(
        Instant requestedAt,
        Instant recordedAt,
        Instant retentionUntil
    ) {
        if (recordedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException(
                "recordedAt must not precede the approval-assistance request"
            );
        }
        Duration retention = Duration.between(recordedAt, retentionUntil);
        if (retention.isZero()
            || retention.isNegative()
            || retention.compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalArgumentException(
                "retentionUntil must be after recordedAt and within ten years"
            );
        }
    }

    private static String projectionEvidenceHash(Request request) {
        ApprovalAssistanceContextProjection projection = request.projection();
        MessageDigest digest = digest();
        updateFramed(digest, "M6-E-P4-PROJECTION-EVIDENCE-V1");
        updateFramed(digest, Long.toString(request.provenance().resourceStateVersion()));
        updateFramed(digest, request.provenance().resourceObservedAt().toString());
        updateFramed(digest, request.provenance().formContentHash());
        updateFramed(digest, request.provenance().uiSchemaHash());
        updateFramed(digest, Integer.toString(request.provenance().submissionRevision()));
        updateFramed(digest, policyKey(request.provenance().dataPolicyVersion()));
        updateFramed(digest, projection.authorizedResource().resourceType().name());
        updateFramed(digest, projection.process().definitionKey());
        updateFramed(digest, Integer.toString(projection.process().definitionVersion()));
        updateFramed(digest, Integer.toString(projection.process().releaseVersion()));
        updateFramed(digest, projection.resourceState().state().name());
        projection.providerFields().stream()
            .sorted(Comparator.comparing(AiProviderRequest.InputField::key))
            .forEach(field -> {
                updateFramed(digest, field.key());
                updateFramed(digest, field.type());
                updateFramed(digest, field.maskingDisposition().name());
                updateFramed(digest, valueEvidenceHash(field.value()));
            });
        ApprovalAssistanceContextProjection.ProviderRequirements requirements =
            projection.providerRequirements();
        updateFramed(digest, Integer.toString(requirements.maximumInputFields()));
        updateFramed(
            digest,
            Integer.toString(requirements.maximumTextCharactersPerValue())
        );
        updateFramed(
            digest,
            Integer.toString(requirements.maximumTotalTextCharacters())
        );
        updateFramed(digest, Integer.toString(requirements.maximumCollectionSize()));
        updateFramed(digest, Integer.toString(requirements.maximumDepth()));
        updateFramed(digest, Boolean.toString(requirements.structuredOutputRequired()));
        updateFramed(digest, Boolean.toString(requirements.attachmentMetadataOnly()));
        ApprovalAssistanceContextProjection.ProjectionEvidence evidence =
            projection.evidence();
        updateFramed(digest, Integer.toString(evidence.authorizedVisibleFieldCount()));
        updateFramed(digest, Integer.toString(evidence.providerFieldCount()));
        updateFramed(digest, Integer.toString(evidence.maskedFieldCount()));
        updateFramed(digest, Integer.toString(evidence.omittedFieldCount()));
        updateFramed(digest, Integer.toString(evidence.attachmentMetadataCount()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String valueEvidenceHash(Object value) {
        MessageDigest digest = digest();
        updateFramed(digest, "M6-E-P4-PROVIDER-VALUE-EVIDENCE-V1");
        updateCanonicalValue(digest, value);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateCanonicalValue(MessageDigest digest, Object value) {
        Objects.requireNonNull(value, "Provider-safe value must not be null");
        if (value instanceof String text) {
            updateFramed(digest, "STRING");
            updateFramed(digest, text);
            return;
        }
        if (value instanceof Number number) {
            updateFramed(digest, "NUMBER");
            updateFramed(digest, number.toString());
            return;
        }
        if (value instanceof Boolean flag) {
            updateFramed(digest, "BOOLEAN");
            updateFramed(digest, flag.toString());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            updateFramed(digest, "MAP");
            updateFramed(digest, Integer.toString(map.size()));
            map.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException(
                            "Provider-safe map key must be a string"
                        );
                    }
                    updateFramed(digest, key);
                    updateCanonicalValue(digest, entry.getValue());
                });
            return;
        }
        if (value instanceof Collection<?> collection) {
            updateFramed(digest, "COLLECTION");
            updateFramed(digest, Integer.toString(collection.size()));
            collection.forEach(item -> updateCanonicalValue(digest, item));
            return;
        }
        throw new IllegalArgumentException(
            "Provider-safe value contains an unsupported runtime type"
        );
    }

    private static String versionEvidenceHash(AiVersionReferences versions) {
        return hash(
            "M6-E-P4-VERSION-EVIDENCE-V1",
            versions.provider().providerId(),
            versions.provider().version(),
            versions.model().providerId(),
            versions.model().modelId(),
            versions.model().version(),
            versions.promptTemplate().templateId(),
            versions.promptTemplate().version(),
            versions.promptTemplate().contentHash(),
            versions.knowledgeSource().sourceId(),
            versions.knowledgeSource().version(),
            versions.knowledgeSource().contentHash(),
            Boolean.toString(versions.knowledgeSource().containsCustomerData()),
            versions.policy().policyId(),
            versions.policy().version(),
            versions.policy().contentHash(),
            versions.outputSchema().schemaId(),
            Integer.toString(versions.outputSchema().version())
        );
    }

    private static String outcomeEvidenceHash(
        Outcome outcome,
        AiAdvisoryResult advisory,
        AdvisoryCounts counts
    ) {
        MessageDigest digest = digest();
        updateFramed(digest, "M6-E-P4-OUTCOME-EVIDENCE-V1");
        updateFramed(digest, outcome.coordinated().outcome().classification().name());
        if (advisory == null) {
            updateFramed(digest, outcome.coordinated().outcome().failure().code());
            updateFramed(digest, outcome.coordinated().outcome().failure().message());
            updateFramed(
                digest,
                Boolean.toString(outcome.coordinated().outcome().failure().retryable())
            );
            return HexFormat.of().formatHex(digest.digest());
        }
        updateFramed(digest, advisory.summary());
        updateFramed(digest, counts.canonical());
        updateFramed(digest, Double.toString(advisory.confidence().score()));
        updateFramed(digest, advisory.confidence().band().name());
        updateFramed(digest, Boolean.toString(advisory.needsHumanReview()));
        updateFramed(digest, advisory.authority().name());
        updateFramed(digest, advisory.assertionStatus().name());
        advisory.observations().stream()
            .sorted(Comparator.comparing(AiAdvisoryResult.Observation::id))
            .forEach(item -> {
                updateFramed(digest, item.id());
                updateFramed(digest, item.text());
                item.evidenceReferenceIds().stream().sorted()
                    .forEach(id -> updateFramed(digest, id));
            });
        advisory.riskSignals().stream()
            .sorted(Comparator.comparing(AiAdvisoryResult.RiskSignal::id))
            .forEach(item -> {
                updateFramed(digest, item.id());
                updateFramed(digest, item.severity().name());
                updateFramed(digest, item.text());
                item.evidenceReferenceIds().stream().sorted()
                    .forEach(id -> updateFramed(digest, id));
            });
        advisory.missingMaterials().stream()
            .sorted(Comparator.comparing(AiAdvisoryResult.MissingMaterial::id))
            .forEach(item -> {
                updateFramed(digest, item.id());
                updateFramed(digest, item.materialType());
                updateFramed(digest, item.reason());
            });
        advisory.recommendations().stream()
            .sorted(Comparator.comparing(AiAdvisoryResult.Recommendation::id))
            .forEach(item -> {
                updateFramed(digest, item.id());
                updateFramed(digest, item.type().name());
                updateFramed(digest, item.text());
                item.evidenceReferenceIds().stream().sorted()
                    .forEach(id -> updateFramed(digest, id));
            });
        advisory.evidenceReferences().stream()
            .sorted(Comparator.comparing(AiAdvisoryResult.EvidenceReference::id))
            .forEach(item -> {
                updateFramed(digest, item.id());
                updateFramed(digest, item.fieldKey());
                updateFramed(digest, item.description());
            });
        advisory.limitations().stream().sorted()
            .forEach(value -> updateFramed(digest, value));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String computeEvidenceHash(
        UUID evidenceId,
        String tenantId,
        String requestHash,
        String subjectHash,
        String resourceHash,
        String projectionHash,
        String executionHash,
        String routeHash,
        String versionsHash,
        String outcomeHash,
        UseCase useCase,
        AiOutcomeClassification classification,
        int providerAttempts,
        boolean invocationStarted,
        boolean retryAttempted,
        boolean fallbackAttempted,
        long killSwitchGeneration,
        boolean resultPresent,
        AdvisoryCounts counts,
        Double confidenceScore,
        AiAdvisoryResult.ConfidenceBand confidenceBand,
        Instant requestedAt,
        Instant recordedAt,
        Instant retentionUntil
    ) {
        return hash(
            "M6-E-P4-DURABLE-EVIDENCE-V1",
            evidenceId.toString(),
            tenantId,
            requestHash,
            subjectHash,
            resourceHash,
            projectionHash,
            executionHash,
            nullable(routeHash),
            versionsHash,
            outcomeHash,
            useCase.name(),
            classification.name(),
            Integer.toString(providerAttempts),
            Boolean.toString(invocationStarted),
            Boolean.toString(retryAttempted),
            Boolean.toString(fallbackAttempted),
            Long.toString(killSwitchGeneration),
            Boolean.toString(resultPresent),
            counts.canonical(),
            nullable(confidenceScore),
            confidenceBand == null ? "" : confidenceBand.name(),
            requestedAt.toString(),
            recordedAt.toString(),
            retentionUntil.toString()
        );
    }

    private static String policyKey(AiVersionReferences.PolicyVersion policy) {
        return String.join(
            "/",
            policy.policyId(),
            policy.version(),
            policy.contentHash()
        );
    }

    private static String hash(String domain, String... values) {
        MessageDigest digest = digest();
        updateFramed(digest, domain);
        for (String value : values) {
            updateFramed(digest, nullable(value));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void updateFramed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String name) {
        return value == null || value.isBlank() ? null : requireSha256(value, name);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String nullable(Object value) {
        return value == null ? "" : value.toString();
    }

    public record AdvisoryCounts(
        int observationCount,
        int riskSignalCount,
        int missingMaterialCount,
        int recommendationCount,
        int evidenceReferenceCount,
        int limitationCount
    ) {
        public AdvisoryCounts {
            requireBounded(observationCount, "observationCount", 25);
            requireBounded(riskSignalCount, "riskSignalCount", 25);
            requireBounded(missingMaterialCount, "missingMaterialCount", 25);
            requireBounded(recommendationCount, "recommendationCount", 25);
            requireBounded(evidenceReferenceCount, "evidenceReferenceCount", 64);
            requireBounded(limitationCount, "limitationCount", 12);
        }

        public static AdvisoryCounts from(AiAdvisoryResult advisory) {
            if (advisory == null) {
                return emptyCounts();
            }
            return new AdvisoryCounts(
                advisory.observations().size(),
                advisory.riskSignals().size(),
                advisory.missingMaterials().size(),
                advisory.recommendations().size(),
                advisory.evidenceReferences().size(),
                advisory.limitations().size()
            );
        }

        public static AdvisoryCounts emptyCounts() {
            return new AdvisoryCounts(0, 0, 0, 0, 0, 0);
        }

        public boolean empty() {
            return observationCount == 0
                && riskSignalCount == 0
                && missingMaterialCount == 0
                && recommendationCount == 0
                && evidenceReferenceCount == 0
                && limitationCount == 0;
        }

        private String canonical() {
            return String.join(
                "/",
                Integer.toString(observationCount),
                Integer.toString(riskSignalCount),
                Integer.toString(missingMaterialCount),
                Integer.toString(recommendationCount),
                Integer.toString(evidenceReferenceCount),
                Integer.toString(limitationCount)
            );
        }

        private static void requireBounded(int value, String name, int maximum) {
            if (value < 0 || value > maximum) {
                throw new IllegalArgumentException(name + " must be bounded");
            }
        }
    }
}
