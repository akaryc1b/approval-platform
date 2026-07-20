package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Platform-owned current release selection and immutable activation history facts. */
public record ApprovalEffectiveRelease(
    String tenantId,
    String definitionKey,
    int effectiveReleaseVersion,
    Integer previousReleaseVersion,
    String releasePackageHash,
    int definitionVersion,
    String definitionHash,
    int formPackageVersion,
    String formPackageHash,
    int formSchemaVersion,
    String formSchemaHash,
    int uiSchemaVersion,
    String uiSchemaHash,
    String compilerVersion,
    String compiledArtifactHash,
    String bpmnHash,
    String deploymentMetadataHash,
    String engineDeploymentId,
    String engineDefinitionId,
    int engineVersion,
    Status status,
    long revision,
    String activatedBy,
    Instant activatedAt,
    String changeReason,
    String requestId,
    String traceId
) {

    public ApprovalEffectiveRelease {
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        requirePositive(effectiveReleaseVersion, "effectiveReleaseVersion");
        if (previousReleaseVersion != null) {
            requirePositive(previousReleaseVersion, "previousReleaseVersion");
            if (previousReleaseVersion == effectiveReleaseVersion) {
                throw new IllegalArgumentException(
                    "previousReleaseVersion must differ from effectiveReleaseVersion"
                );
            }
        }
        releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
        requirePositive(definitionVersion, "definitionVersion");
        definitionHash = requireHash(definitionHash, "definitionHash");
        requirePositive(formPackageVersion, "formPackageVersion");
        formPackageHash = requireHash(formPackageHash, "formPackageHash");
        requirePositive(formSchemaVersion, "formSchemaVersion");
        formSchemaHash = requireHash(formSchemaHash, "formSchemaHash");
        requirePositive(uiSchemaVersion, "uiSchemaVersion");
        uiSchemaHash = requireHash(uiSchemaHash, "uiSchemaHash");
        compilerVersion = requireText(compilerVersion, "compilerVersion");
        compiledArtifactHash = requireHash(compiledArtifactHash, "compiledArtifactHash");
        bpmnHash = requireHash(bpmnHash, "bpmnHash");
        deploymentMetadataHash = requireHash(
            deploymentMetadataHash,
            "deploymentMetadataHash"
        );
        engineDeploymentId = requireText(engineDeploymentId, "engineDeploymentId");
        engineDefinitionId = requireText(engineDefinitionId, "engineDefinitionId");
        requirePositive(engineVersion, "engineVersion");
        status = Objects.requireNonNull(status, "status must not be null");
        if (status != Status.ACTIVE) {
            throw new IllegalArgumentException("effective release status must be ACTIVE");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        activatedBy = requireText(activatedBy, "activatedBy");
        activatedAt = Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        changeReason = requireText(changeReason, "changeReason");
        requestId = requireText(requestId, "requestId");
        traceId = normalizeOptional(traceId);
    }

    public enum Status {
        ACTIVE
    }

    public enum Action {
        ACTIVATE,
        ROLLBACK
    }

    public record Activation(
        UUID activationId,
        String tenantId,
        String definitionKey,
        int releaseVersion,
        Integer previousReleaseVersion,
        String releasePackageHash,
        int definitionVersion,
        int formPackageVersion,
        String compilerVersion,
        String engineDeploymentId,
        String engineDefinitionId,
        int engineVersion,
        Action action,
        long revision,
        String activatedBy,
        Instant activatedAt,
        String changeReason,
        String requestId,
        String traceId
    ) {
        public Activation {
            activationId = Objects.requireNonNull(
                activationId,
                "activationId must not be null"
            );
            tenantId = requireText(tenantId, "tenantId");
            definitionKey = requireText(definitionKey, "definitionKey");
            requirePositive(releaseVersion, "releaseVersion");
            if (previousReleaseVersion != null) {
                requirePositive(previousReleaseVersion, "previousReleaseVersion");
                if (previousReleaseVersion == releaseVersion) {
                    throw new IllegalArgumentException(
                        "previousReleaseVersion must differ from releaseVersion"
                    );
                }
            }
            releasePackageHash = requireHash(releasePackageHash, "releasePackageHash");
            requirePositive(definitionVersion, "definitionVersion");
            requirePositive(formPackageVersion, "formPackageVersion");
            compilerVersion = requireText(compilerVersion, "compilerVersion");
            engineDeploymentId = requireText(engineDeploymentId, "engineDeploymentId");
            engineDefinitionId = requireText(engineDefinitionId, "engineDefinitionId");
            requirePositive(engineVersion, "engineVersion");
            action = Objects.requireNonNull(action, "action must not be null");
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be positive");
            }
            activatedBy = requireText(activatedBy, "activatedBy");
            activatedAt = Objects.requireNonNull(
                activatedAt,
                "activatedAt must not be null"
            );
            changeReason = requireText(changeReason, "changeReason");
            requestId = requireText(requestId, "requestId");
            traceId = normalizeOptional(traceId);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
