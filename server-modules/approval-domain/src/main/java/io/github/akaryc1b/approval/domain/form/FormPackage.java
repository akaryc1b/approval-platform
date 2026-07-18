package io.github.akaryc1b.approval.domain.form;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable release identity that binds one exact Form Schema and UI Schema pair. */
public record FormPackage(
    String tenantId,
    String formKey,
    int packageVersion,
    int formVersion,
    String formHash,
    int uiSchemaVersion,
    String uiSchemaHash,
    String packageHash,
    UUID sourceDraftId,
    String publishedBy,
    Instant publishedAt
) {

    public FormPackage {
        tenantId = requireText(tenantId, "tenantId");
        formKey = requireText(formKey, "formKey");
        if (packageVersion < 1 || formVersion < 1 || uiSchemaVersion < 1) {
            throw new IllegalArgumentException("package and schema versions must be positive");
        }
        formHash = requireHash(formHash, "formHash");
        uiSchemaHash = requireHash(uiSchemaHash, "uiSchemaHash");
        packageHash = requireHash(packageHash, "packageHash");
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
