package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable deployable package binding exact source, form, UI and compiler artifacts. */
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
    String compilerVersion,
    String bpmnResourceName,
    String bpmnArtifact,
    String compiledArtifactHash,
    String bpmnHash,
    String dmnArtifact,
    String dmnHash,
    String deploymentMetadataHash,
    String packageHash,
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
        compilerVersion = requireText(compilerVersion, "compilerVersion");
        bpmnResourceName = requireText(bpmnResourceName, "bpmnResourceName");
        bpmnArtifact = requireArtifact(bpmnArtifact, "bpmnArtifact");
        compiledArtifactHash = requireHash(
            compiledArtifactHash,
            "compiledArtifactHash"
        );
        bpmnHash = requireHash(bpmnHash, "bpmnHash");
        dmnArtifact = normalizeArtifact(dmnArtifact);
        dmnHash = normalizeOptional(dmnHash);
        if ((dmnArtifact == null) != (dmnHash == null)) {
            throw new IllegalArgumentException(
                "DMN artifact and hash must either both be present or absent"
            );
        }
        if (dmnHash != null) {
            dmnHash = requireHash(dmnHash, "dmnHash");
        }
        deploymentMetadataHash = requireHash(
            deploymentMetadataHash,
            "deploymentMetadataHash"
        );
        packageHash = requireHash(packageHash, "packageHash");
        sourceDraftId = Objects.requireNonNull(sourceDraftId, "sourceDraftId must not be null");
        publishedBy = requireText(publishedBy, "publishedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    private static String requireArtifact(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
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

    private static String normalizeArtifact(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
