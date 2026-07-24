package io.github.akaryc1b.approval.application.port;

import java.util.Objects;
/** Server-owned resolver for one exact immutable tenant-local Form Package evidence record. */
@FunctionalInterface
public interface ProcessTemplateFormPackageEvidenceResolver {

    FormPackageEvidence resolve(FormPackageEvidenceRequest request);

    record FormPackageEvidenceRequest(
        String tenantId,
        String definitionKey,
        int packageVersion
    ) {
        public FormPackageEvidenceRequest {
            tenantId = requireKey(tenantId, "formPackageRequest.tenantId");
            definitionKey = requireKey(definitionKey, "formPackageRequest.definitionKey");
            if (packageVersion < 1) {
                throw new IllegalArgumentException("formPackageRequest.packageVersion must be positive");
            }
        }
    }

    record FormPackageEvidence(
        String tenantId,
        String definitionKey,
        int packageVersion,
        int formVersion,
        String formHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String packageHash,
        String contentHash
    ) {
        public FormPackageEvidence {
            tenantId = requireKey(tenantId, "formPackageEvidence.tenantId");
            definitionKey = requireKey(definitionKey, "formPackageEvidence.definitionKey");
            if (packageVersion < 1 || formVersion < 1 || uiSchemaVersion < 1) {
                throw new IllegalArgumentException("Form Package evidence versions must be positive");
            }
            formHash = requireHash(formHash, "formPackageEvidence.formHash");
            uiSchemaHash = requireHash(uiSchemaHash, "formPackageEvidence.uiSchemaHash");
            packageHash = requireHash(packageHash, "formPackageEvidence.packageHash");
            contentHash = requireHash(contentHash, "formPackageEvidence.contentHash");
        }
    }

    private static String requireKey(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(name + " is not a safe key");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return value;
    }
}
