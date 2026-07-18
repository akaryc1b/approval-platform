package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable release identity binding exact Approval DSL, Form/UI package,
 * compiled process artifact and engine deployment metadata.
 */
public record ApprovalReleasePackage(
    String tenantId,
    String definitionKey,
    int releaseVersion,
    int definitionVersion,
    String definitionHash,
    int formPackageVersion,
    String formPackageHash,
    int formVersion,
    String formHash,
    int uiSchemaVersion,
    String uiSchemaHash,
    String compiledArtifactType,
    String compiledArtifactHash,
    String deploymentId,
    String releaseHash,
    UUID sourceDraftId,
    String publishedBy,
    Instant publishedAt
) {

    public ApprovalReleasePackage {
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        if (releaseVersion < 1
            || definitionVersion < 1
            || formPackageVersion < 1
            || formVersion < 1
            || uiSchemaVersion < 1) {
            throw new IllegalArgumentException("release and artifact versions must be positive");
        }
        definitionHash = requireHash(definitionHash, "definitionHash");
        formPackageHash = requireHash(formPackageHash, "formPackageHash");
        formHash = requireHash(formHash, "formHash");
        uiSchemaHash = requireHash(uiSchemaHash, "uiSchemaHash");
        compiledArtifactType = requireText(compiledArtifactType, "compiledArtifactType");
        compiledArtifactHash = requireHash(compiledArtifactHash, "compiledArtifactHash");
        deploymentId = requireText(deploymentId, "deploymentId");
        releaseHash = requireHash(releaseHash, "releaseHash");
        sourceDraftId = Objects.requireNonNull(sourceDraftId, "sourceDraftId must not be null");
        publishedBy = requireText(publishedBy, "publishedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
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
}
