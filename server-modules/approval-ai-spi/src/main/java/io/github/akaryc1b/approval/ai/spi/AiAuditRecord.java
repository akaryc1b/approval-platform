package io.github.akaryc1b.approval.ai.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Hash-only audit contract for one advisory attempt and later human-decision linkage. */
public record AiAuditRecord(
    String requestEvidenceHash,
    String subjectEvidenceHash,
    String resourceEvidenceHash,
    AiCapability capability,
    AiVersionReferences.PolicyVersion inputPolicyVersion,
    AiVersionReferences versions,
    AiOutcomeClassification resultClassification,
    String humanDecisionEvidenceHash,
    String auditEvidenceHash
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiAuditRecord {
        requestEvidenceHash = requireSha256(requestEvidenceHash, "requestEvidenceHash");
        subjectEvidenceHash = requireSha256(subjectEvidenceHash, "subjectEvidenceHash");
        resourceEvidenceHash = requireSha256(resourceEvidenceHash, "resourceEvidenceHash");
        capability = Objects.requireNonNull(capability, "capability must not be null");
        inputPolicyVersion = Objects.requireNonNull(
            inputPolicyVersion,
            "inputPolicyVersion must not be null"
        );
        versions = Objects.requireNonNull(versions, "versions must not be null");
        if (!inputPolicyVersion.equals(versions.policy())) {
            throw new IllegalArgumentException(
                "inputPolicyVersion must match the version references policy"
            );
        }
        resultClassification = Objects.requireNonNull(
            resultClassification,
            "resultClassification must not be null"
        );
        humanDecisionEvidenceHash = normalizeOptionalSha256(
            humanDecisionEvidenceHash,
            "humanDecisionEvidenceHash"
        );
        auditEvidenceHash = requireSha256(auditEvidenceHash, "auditEvidenceHash");
        String expected = computeAuditEvidenceHash(
            requestEvidenceHash,
            subjectEvidenceHash,
            resourceEvidenceHash,
            capability,
            versions,
            resultClassification,
            humanDecisionEvidenceHash
        );
        if (!auditEvidenceHash.equals(expected)) {
            throw new IllegalArgumentException("auditEvidenceHash must match canonical evidence");
        }
    }

    public static AiAuditRecord create(
        AiProviderRequest request,
        AiOutcomeClassification resultClassification
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(
            resultClassification,
            "resultClassification must not be null"
        );
        String requestHash = hash(
            "request",
            request.context().tenantId(),
            request.context().requestId(),
            nullable(request.context().traceId())
        );
        String subjectHash = hash(
            "subject",
            request.context().tenantId(),
            request.context().operatorId()
        );
        String resourceHash = hash(
            "resource",
            request.resource().tenantId(),
            request.resource().resourceType(),
            request.resource().resourceId(),
            request.resource().authorizationReference()
        );
        String evidenceHash = computeAuditEvidenceHash(
            requestHash,
            subjectHash,
            resourceHash,
            request.capability(),
            request.versions(),
            resultClassification,
            null
        );
        return new AiAuditRecord(
            requestHash,
            subjectHash,
            resourceHash,
            request.capability(),
            request.versions().policy(),
            request.versions(),
            resultClassification,
            null,
            evidenceHash
        );
    }

    public AiAuditRecord withHumanDecisionReference(String reference) {
        String decisionHash = reference == null || reference.isBlank()
            ? null
            : hash("human-decision", requireText(reference, "reference", 200));
        String evidenceHash = computeAuditEvidenceHash(
            requestEvidenceHash,
            subjectEvidenceHash,
            resourceEvidenceHash,
            capability,
            versions,
            resultClassification,
            decisionHash
        );
        return new AiAuditRecord(
            requestEvidenceHash,
            subjectEvidenceHash,
            resourceEvidenceHash,
            capability,
            inputPolicyVersion,
            versions,
            resultClassification,
            decisionHash,
            evidenceHash
        );
    }

    private static String computeAuditEvidenceHash(
        String requestHash,
        String subjectHash,
        String resourceHash,
        AiCapability capability,
        AiVersionReferences versions,
        AiOutcomeClassification classification,
        String humanDecisionHash
    ) {
        return hash(
            "audit",
            requestHash,
            subjectHash,
            resourceHash,
            capability.name(),
            versionKey(versions),
            classification.name(),
            nullable(humanDecisionHash)
        );
    }

    private static String versionKey(AiVersionReferences versions) {
        return frame(
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

    private static String hash(String... values) {
        String canonical = frame(values);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String frame(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            String normalized = value == null ? "" : value;
            canonical.append(normalized.length())
                .append(':')
                .append(normalized);
        }
        return canonical.toString();
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase();
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        }
        return normalized;
    }

    private static String normalizeOptionalSha256(String value, String name) {
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

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}
