package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable form data snapshots created when a published form starts or revises a workflow.
 */
public interface ApprovalFormSubmissionStore {

    void save(FormSubmission submission);

    Optional<FormSubmission> findByInstance(String tenantId, UUID instanceId);

    Optional<FormSubmission> findByBusinessKey(String tenantId, String businessKey);

    default void lockInstance(String tenantId, UUID instanceId) {
        throw new UnsupportedOperationException("form submission locking is not supported");
    }

    default void saveRevision(FormSubmissionRevision revision) {
        throw new UnsupportedOperationException("form submission revisions are not supported");
    }

    default Optional<FormSubmissionRevision> findLatestRevision(
        String tenantId,
        UUID instanceId
    ) {
        return Optional.empty();
    }

    record FormSubmission(
        UUID submissionId,
        String tenantId,
        String formKey,
        int formVersion,
        String schemaHash,
        Integer uiSchemaVersion,
        String uiSchemaHash,
        String businessKey,
        Map<String, Object> values,
        Map<String, Object> startParameters,
        UUID instanceId,
        String submittedBy,
        Instant submittedAt,
        String requestHash
    ) {
        public FormSubmission {
            submissionId = Objects.requireNonNull(submissionId, "submissionId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            formKey = requireText(formKey, "formKey");
            if (formVersion < 1) {
                throw new IllegalArgumentException("formVersion must be positive");
            }
            schemaHash = requireText(schemaHash, "schemaHash");
            if ((uiSchemaVersion == null) != (uiSchemaHash == null)) {
                throw new IllegalArgumentException(
                    "uiSchemaVersion and uiSchemaHash must either both be present or both be absent"
                );
            }
            if (uiSchemaVersion != null && uiSchemaVersion < 1) {
                throw new IllegalArgumentException("uiSchemaVersion must be positive");
            }
            if (uiSchemaHash != null) {
                uiSchemaHash = requireText(uiSchemaHash, "uiSchemaHash");
            }
            businessKey = requireText(businessKey, "businessKey");
            values = values == null ? Map.of() : Map.copyOf(values);
            startParameters = startParameters == null ? Map.of() : Map.copyOf(startParameters);
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            submittedBy = requireText(submittedBy, "submittedBy");
            submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
            requestHash = requireText(requestHash, "requestHash");
        }

        public FormSubmission(
            UUID submissionId,
            String tenantId,
            String formKey,
            int formVersion,
            String schemaHash,
            String businessKey,
            Map<String, Object> values,
            Map<String, Object> startParameters,
            UUID instanceId,
            String submittedBy,
            Instant submittedAt,
            String requestHash
        ) {
            this(
                submissionId,
                tenantId,
                formKey,
                formVersion,
                schemaHash,
                null,
                null,
                businessKey,
                values,
                startParameters,
                instanceId,
                submittedBy,
                submittedAt,
                requestHash
            );
        }
    }

    record FormSubmissionRevision(
        UUID revisionId,
        String tenantId,
        UUID instanceId,
        int revisionNumber,
        Map<String, Object> values,
        String modifiedBy,
        Instant modifiedAt,
        String requestHash
    ) {
        public FormSubmissionRevision {
            revisionId = Objects.requireNonNull(revisionId, "revisionId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            if (revisionNumber < 1) {
                throw new IllegalArgumentException("revisionNumber must be positive");
            }
            values = values == null ? Map.of() : Map.copyOf(values);
            modifiedBy = requireText(modifiedBy, "modifiedBy");
            modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt must not be null");
            requestHash = requireText(requestHash, "requestHash");
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
}
