package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable platform evidence binding a runtime instance to one exact release package. */
public record ApprovalRuntimeBinding(
    String tenantId,
    UUID approvalInstanceId,
    String businessKey,
    String engineInstanceId,
    String definitionKey,
    int releaseVersion,
    String releasePackageHash,
    int definitionVersion,
    String definitionHash,
    int formPackageVersion,
    String formPackageHash,
    int formVersion,
    String formHash,
    int uiSchemaVersion,
    String uiSchemaHash,
    String compilerVersion,
    String compiledArtifactHash,
    String bpmnHash,
    String deploymentMetadataHash,
    String engineDeploymentId,
    String engineDefinitionId,
    int engineVersion,
    String bindingEvidenceHash,
    String boundBy,
    Instant boundAt,
    String requestId,
    String traceId,
    String auditChainReference
) {

    public ApprovalRuntimeBinding {
        tenantId = requireText(tenantId, "tenantId");
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        businessKey = requireBoundedText(businessKey, "businessKey", 256);
        engineInstanceId = requireBoundedText(engineInstanceId, "engineInstanceId", 256);
        definitionKey = requireBoundedText(definitionKey, "definitionKey", 64);
        requirePositive(releaseVersion, "releaseVersion");
        releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
        requirePositive(definitionVersion, "definitionVersion");
        definitionHash = requireHash(definitionHash, "definitionHash");
        requirePositive(formPackageVersion, "formPackageVersion");
        formPackageHash = requireHash(formPackageHash, "formPackageHash");
        requirePositive(formVersion, "formVersion");
        formHash = requireHash(formHash, "formHash");
        requirePositive(uiSchemaVersion, "uiSchemaVersion");
        uiSchemaHash = requireHash(uiSchemaHash, "uiSchemaHash");
        compilerVersion = requireBoundedText(compilerVersion, "compilerVersion", 64);
        compiledArtifactHash = requireHash(compiledArtifactHash, "compiledArtifactHash");
        bpmnHash = requireHash(bpmnHash, "bpmnHash");
        deploymentMetadataHash = requireHash(
            deploymentMetadataHash,
            "deploymentMetadataHash"
        );
        engineDeploymentId = requireBoundedText(
            engineDeploymentId,
            "engineDeploymentId",
            128
        );
        engineDefinitionId = requireBoundedText(
            engineDefinitionId,
            "engineDefinitionId",
            256
        );
        requirePositive(engineVersion, "engineVersion");
        bindingEvidenceHash = requireHash(bindingEvidenceHash, "bindingEvidenceHash");
        boundBy = requireBoundedText(boundBy, "boundBy", 256);
        boundAt = Objects.requireNonNull(boundAt, "boundAt must not be null");
        requestId = requireBoundedText(requestId, "requestId", 256);
        traceId = normalizeOptional(traceId, 256, "traceId");
        auditChainReference = requireBoundedText(
            auditChainReference,
            "auditChainReference",
            256
        );
    }

    public boolean binds(ApprovalReleasePackage releasePackage) {
        Objects.requireNonNull(releasePackage, "releasePackage must not be null");
        return tenantId.equals(releasePackage.tenantId())
            && definitionKey.equals(releasePackage.definitionKey())
            && releaseVersion == releasePackage.releaseVersion()
            && releasePackageHash.equals(releasePackage.packageHash())
            && definitionVersion == releasePackage.definitionVersion()
            && definitionHash.equals(releasePackage.definitionHash())
            && formPackageVersion == releasePackage.formPackageVersion()
            && formPackageHash.equals(releasePackage.formPackageHash())
            && formVersion == releasePackage.formVersion()
            && formHash.equals(releasePackage.formHash())
            && uiSchemaVersion == releasePackage.uiSchemaVersion()
            && uiSchemaHash.equals(releasePackage.uiSchemaHash())
            && compilerVersion.equals(releasePackage.compilerVersion())
            && compiledArtifactHash.equals(releasePackage.compiledArtifactHash())
            && bpmnHash.equals(releasePackage.bpmnHash())
            && deploymentMetadataHash.equals(releasePackage.deploymentMetadataHash());
    }

    public boolean binds(
        ApprovalReleasePackage releasePackage,
        ApprovalReleaseDeployment deployment
    ) {
        Objects.requireNonNull(deployment, "deployment must not be null");
        return binds(releasePackage)
            && deployment.status() == ApprovalReleaseDeployment.Status.DEPLOYED
            && tenantId.equals(deployment.tenantId())
            && definitionKey.equals(deployment.definitionKey())
            && releaseVersion == deployment.releaseVersion()
            && releasePackageHash.equals(deployment.releasePackageHash())
            && engineDeploymentId.equals(deployment.engineDeploymentId())
            && engineDefinitionId.equals(deployment.engineDefinitionId())
            && engineVersion == deployment.engineVersion();
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireBoundedText(String value, String name, int maximum) {
        String normalized = requireText(value, name);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximum);
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
