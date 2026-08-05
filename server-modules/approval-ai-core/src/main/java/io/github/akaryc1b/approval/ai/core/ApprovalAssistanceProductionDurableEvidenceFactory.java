package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.Request;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence.AdvisoryCounts;
import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * P6-E production factory for the already accepted P4 hash-only record.
 *
 * <p>The P3 deterministic factory remains unchanged. This factory repeats the frozen P4 canonical
 * domains so a production outcome is stored by the same JDBC store without persisting Provider-safe
 * values, Prompt text, advisory text, request/response JSON, Secret or raw request identifiers.</p>
 */
public final class ApprovalAssistanceProductionDurableEvidenceFactory {

    private ApprovalAssistanceProductionDurableEvidenceFactory() {
    }

    public static ApprovalAssistanceDurableEvidence create(
        UUID evidenceId,
        ApprovalAssistanceProductionOrchestrator.Outcome outcome,
        Instant recordedAt,
        Instant retentionUntil
    ) {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(retentionUntil, "retentionUntil must not be null");
        Request request = outcome.request();
        AiAdvisoryExecutionEvidence execution = outcome.executionEvidence();
        AiAdvisoryResult advisory = outcome.acceptedResult() == null
            ? null
            : outcome.acceptedResult().advisory();
        AdvisoryCounts counts = AdvisoryCounts.from(advisory);
        String projectionHash = projectionEvidenceHash(request);
        String versionsHash = versionEvidenceHash(request.expectedVersions());
        String outcomeHash = outcomeEvidenceHash(outcome, advisory, counts);
        String tenantId = request.projection().requestContext().tenantId();
        Double confidenceScore = advisory == null ? null : advisory.confidence().score();
        AiAdvisoryResult.ConfidenceBand confidenceBand = advisory == null
            ? null
            : advisory.confidence().band();
        String canonical = computeEvidenceHash(
            evidenceId,
            tenantId,
            execution.requestEvidenceHash(),
            execution.subjectEvidenceHash(),
            execution.resourceEvidenceHash(),
            projectionHash,
            execution.evidenceHash(),
            execution.routeEvidenceHash(),
            versionsHash,
            outcomeHash,
            request.useCase(),
            outcome.coordinated().outcome().classification(),
            outcome.providerAttempts(),
            outcome.coordinated().providerInvocationStarted(),
            outcome.retryAttempted(),
            outcome.coordinated().postInvocationFallbackAttempted(),
            outcome.killSwitchGeneration(),
            advisory != null,
            counts,
            confidenceScore,
            confidenceBand,
            request.requestedAt(),
            recordedAt,
            retentionUntil
        );
        return new ApprovalAssistanceDurableEvidence(
            evidenceId,
            tenantId,
            execution.requestEvidenceHash(),
            execution.subjectEvidenceHash(),
            execution.resourceEvidenceHash(),
            projectionHash,
            execution.evidenceHash(),
            execution.routeEvidenceHash(),
            versionsHash,
            outcomeHash,
            request.useCase(),
            outcome.coordinated().outcome().classification(),
            request.expectedVersions(),
            outcome.providerAttempts(),
            outcome.coordinated().providerInvocationStarted(),
            outcome.retryAttempted(),
            outcome.coordinated().postInvocationFallbackAttempted(),
            outcome.killSwitchGeneration(),
            advisory != null,
            counts,
            confidenceScore,
            confidenceBand,
            request.requestedAt(),
            recordedAt,
            retentionUntil,
            canonical
        );
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
        updateFramed(digest, Integer.toString(requirements.maximumTextCharactersPerValue()));
        updateFramed(digest, Integer.toString(requirements.maximumTotalTextCharacters()));
        updateFramed(digest, Integer.toString(requirements.maximumCollectionSize()));
        updateFramed(digest, Integer.toString(requirements.maximumDepth()));
        updateFramed(digest, Boolean.toString(requirements.structuredOutputRequired()));
        updateFramed(digest, Boolean.toString(requirements.attachmentMetadataOnly()));
        ApprovalAssistanceContextProjection.ProjectionEvidence evidence = projection.evidence();
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
        ApprovalAssistanceProductionOrchestrator.Outcome outcome,
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
        updateFramed(digest, countsCanonical(counts));
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
        ApprovalAssistanceAdvisoryContract.UseCase useCase,
        io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification classification,
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
            countsCanonical(counts),
            nullable(confidenceScore),
            confidenceBand == null ? "" : confidenceBand.name(),
            requestedAt.toString(),
            recordedAt.toString(),
            retentionUntil.toString()
        );
    }

    private static String countsCanonical(AdvisoryCounts counts) {
        return String.join(
            "/",
            Integer.toString(counts.observationCount()),
            Integer.toString(counts.riskSignalCount()),
            Integer.toString(counts.missingMaterialCount()),
            Integer.toString(counts.recommendationCount()),
            Integer.toString(counts.evidenceReferenceCount()),
            Integer.toString(counts.limitationCount())
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

    private static String nullable(Object value) {
        return value == null ? "" : value.toString();
    }
}
