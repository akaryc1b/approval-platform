package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable form data snapshots created when a published form starts a workflow.
 */
public interface ApprovalFormSubmissionStore {

    void save(FormSubmission submission);

    Optional<FormSubmission> findByInstance(String tenantId, UUID instanceId);

    Optional<FormSubmission> findByBusinessKey(String tenantId, String businessKey);

    record FormSubmission(
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
        public FormSubmission {
            submissionId = Objects.requireNonNull(submissionId, "submissionId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            formKey = requireText(formKey, "formKey");
            if (formVersion < 1) {
                throw new IllegalArgumentException("formVersion must be positive");
            }
            schemaHash = requireText(schemaHash, "schemaHash");
            businessKey = requireText(businessKey, "businessKey");
            values = values == null ? Map.of() : Map.copyOf(values);
            startParameters = startParameters == null ? Map.of() : Map.copyOf(startParameters);
            instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
            submittedBy = requireText(submittedBy, "submittedBy");
            submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
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
