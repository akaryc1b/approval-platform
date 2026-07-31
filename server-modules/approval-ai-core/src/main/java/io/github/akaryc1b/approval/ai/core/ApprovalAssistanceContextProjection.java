package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-safe approval context assembled only from server-owned identity, authorization,
 * immutable schema metadata and current platform projections.
 */
public record ApprovalAssistanceContextProjection(
    AiServerRequestContext requestContext,
    AiAuthorizedResource authorizedResource,
    ProcessSnapshot process,
    ResourceStateSnapshot resourceState,
    FormSnapshot form,
    List<AiProviderRequest.InputField> providerFields,
    ProviderRequirements providerRequirements,
    AiVersionReferences.PolicyVersion dataPolicyVersion,
    ProjectionEvidence evidence
) {

    public ApprovalAssistanceContextProjection {
        requestContext = Objects.requireNonNull(
            requestContext,
            "requestContext must not be null"
        );
        authorizedResource = Objects.requireNonNull(
            authorizedResource,
            "authorizedResource must not be null"
        );
        process = Objects.requireNonNull(process, "process must not be null");
        resourceState = Objects.requireNonNull(
            resourceState,
            "resourceState must not be null"
        );
        form = Objects.requireNonNull(form, "form must not be null");
        providerFields = providerFields == null ? List.of() : List.copyOf(providerFields);
        providerRequirements = Objects.requireNonNull(
            providerRequirements,
            "providerRequirements must not be null"
        );
        dataPolicyVersion = Objects.requireNonNull(
            dataPolicyVersion,
            "dataPolicyVersion must not be null"
        );
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");

        validateTenantBinding(requestContext, authorizedResource, resourceState);
        validateProcessAndFormBinding(process, form);
        validateResourceBinding(authorizedResource, resourceState, form);

        Set<String> fieldKeys = new HashSet<>();
        for (AiProviderRequest.InputField field : providerFields) {
            if (!fieldKeys.add(field.key())) {
                throw new IllegalArgumentException("provider field keys must be unique");
            }
            if (!authorizedResource.allowedFieldKeys().contains(field.key())) {
                throw new IllegalArgumentException(
                    "provider field is not authorized: " + field.key()
                );
            }
        }
        if (providerFields.size() != evidence.providerFieldCount()) {
            throw new IllegalArgumentException(
                "provider field count does not match projection evidence"
            );
        }
    }

    public record ProcessSnapshot(
        String definitionKey,
        int definitionVersion,
        String compilerVersion,
        String definitionContentHash,
        String formKey,
        int formVersion,
        Integer releaseVersion,
        String releasePackageHash
    ) {
        public ProcessSnapshot {
            definitionKey = requireText(definitionKey, "definitionKey", 160);
            if (definitionVersion < 1) {
                throw new IllegalArgumentException("definitionVersion must be positive");
            }
            compilerVersion = requireText(compilerVersion, "compilerVersion", 120);
            definitionContentHash = requireText(
                definitionContentHash,
                "definitionContentHash",
                160
            );
            formKey = requireText(formKey, "formKey", 160);
            if (formVersion < 1) {
                throw new IllegalArgumentException("formVersion must be positive");
            }
            releasePackageHash = normalizeOptional(
                releasePackageHash,
                "releasePackageHash",
                160
            );
            boolean anyRelease = releaseVersion != null || releasePackageHash != null;
            boolean completeRelease = releaseVersion != null && releasePackageHash != null;
            if (anyRelease && !completeRelease) {
                throw new IllegalArgumentException(
                    "release evidence must be either complete or absent"
                );
            }
            if (releaseVersion != null && releaseVersion < 1) {
                throw new IllegalArgumentException("releaseVersion must be positive");
            }
        }
    }

    public record ResourceStateSnapshot(
        String tenantId,
        String instanceId,
        String taskId,
        String taskDefinitionKey,
        ResourceState state,
        long stateVersion,
        Instant observedAt
    ) {
        public ResourceStateSnapshot {
            tenantId = requireText(tenantId, "tenantId", 120);
            instanceId = requireText(instanceId, "instanceId", 200);
            taskId = normalizeOptional(taskId, "taskId", 200);
            taskDefinitionKey = normalizeOptional(
                taskDefinitionKey,
                "taskDefinitionKey",
                160
            );
            state = Objects.requireNonNull(state, "state must not be null");
            if (stateVersion < 0) {
                throw new IllegalArgumentException("stateVersion must not be negative");
            }
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");

            if (state == ResourceState.TASK_PENDING
                && (taskId == null || taskDefinitionKey == null)) {
                throw new IllegalArgumentException(
                    "pending task state requires task identity and definition key"
                );
            }
            if (state == ResourceState.INSTANCE_RUNNING
                && (taskId != null || taskDefinitionKey != null)) {
                throw new IllegalArgumentException(
                    "instance state must not contain task identity"
                );
            }
        }
    }

    public enum ResourceState {
        TASK_PENDING,
        INSTANCE_RUNNING
    }

    public record FormSnapshot(
        String formKey,
        int formVersion,
        String formSchemaVersion,
        String formContentHash,
        int uiSchemaVersion,
        String uiSchemaHash,
        String contextKey,
        int submissionRevision
    ) {
        public FormSnapshot {
            formKey = requireText(formKey, "formKey", 160);
            if (formVersion < 1) {
                throw new IllegalArgumentException("formVersion must be positive");
            }
            formSchemaVersion = requireText(
                formSchemaVersion,
                "formSchemaVersion",
                120
            );
            formContentHash = requireText(formContentHash, "formContentHash", 160);
            if (uiSchemaVersion < 1) {
                throw new IllegalArgumentException("uiSchemaVersion must be positive");
            }
            uiSchemaHash = requireText(uiSchemaHash, "uiSchemaHash", 160);
            contextKey = requireText(contextKey, "contextKey", 160);
            if (submissionRevision < 0) {
                throw new IllegalArgumentException(
                    "submissionRevision must not be negative"
                );
            }
        }
    }

    public record ProviderRequirements(
        Set<AiCapability> capabilities,
        int maximumInputFields,
        int maximumCollectionSize,
        int maximumDepth,
        boolean structuredOutputRequired,
        boolean attachmentMetadataOnly
    ) {
        public ProviderRequirements {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            if (capabilities.isEmpty() || capabilities.size() > 16) {
                throw new IllegalArgumentException(
                    "provider capabilities must be non-empty and bounded"
                );
            }
            if (maximumInputFields < 1
                || maximumCollectionSize < 1
                || maximumDepth < 1) {
                throw new IllegalArgumentException(
                    "provider input requirements must be positive"
                );
            }
            if (!structuredOutputRequired) {
                throw new IllegalArgumentException(
                    "approval assistance requires structured output"
                );
            }
            if (!attachmentMetadataOnly) {
                throw new IllegalArgumentException(
                    "approval assistance permits attachment metadata only"
                );
            }
        }
    }

    public record ProjectionEvidence(
        int authorizedVisibleFieldCount,
        int providerFieldCount,
        int maskedFieldCount,
        int omittedFieldCount,
        int attachmentMetadataCount,
        boolean attachmentExtractionAttempted
    ) {
        public ProjectionEvidence {
            if (authorizedVisibleFieldCount < 0
                || providerFieldCount < 0
                || maskedFieldCount < 0
                || omittedFieldCount < 0
                || attachmentMetadataCount < 0) {
                throw new IllegalArgumentException("projection counts must not be negative");
            }
            if (providerFieldCount > authorizedVisibleFieldCount) {
                throw new IllegalArgumentException(
                    "provider fields cannot exceed authorized visible fields"
                );
            }
            if (maskedFieldCount > providerFieldCount) {
                throw new IllegalArgumentException(
                    "masked fields cannot exceed provider fields"
                );
            }
            if (attachmentExtractionAttempted) {
                throw new IllegalArgumentException(
                    "attachment extraction is prohibited in M6-E"
                );
            }
        }
    }

    private static void validateTenantBinding(
        AiServerRequestContext requestContext,
        AiAuthorizedResource authorizedResource,
        ResourceStateSnapshot resourceState
    ) {
        if (!requestContext.tenantId().equals(authorizedResource.tenantId())
            || !requestContext.tenantId().equals(resourceState.tenantId())) {
            throw new IllegalArgumentException("projection tenant evidence must match");
        }
    }

    private static void validateProcessAndFormBinding(
        ProcessSnapshot process,
        FormSnapshot form
    ) {
        if (!process.formKey().equals(form.formKey())
            || process.formVersion() != form.formVersion()) {
            throw new IllegalArgumentException(
                "projection process and form evidence must match"
            );
        }
    }

    private static void validateResourceBinding(
        AiAuthorizedResource authorizedResource,
        ResourceStateSnapshot resourceState,
        FormSnapshot form
    ) {
        if (authorizedResource.resourceType()
            == AiAuthorizedResource.ResourceType.APPROVAL_TASK) {
            if (resourceState.state() != ResourceState.TASK_PENDING
                || !authorizedResource.resourceId().equals(resourceState.taskId())
                || !form.contextKey().equals(resourceState.taskDefinitionKey())) {
                throw new IllegalArgumentException(
                    "projection task authorization and state evidence must match"
                );
            }
            return;
        }
        if (authorizedResource.resourceType()
            == AiAuthorizedResource.ResourceType.PROCESS_INSTANCE) {
            if (resourceState.state() != ResourceState.INSTANCE_RUNNING
                || !authorizedResource.resourceId().equals(resourceState.instanceId())) {
                throw new IllegalArgumentException(
                    "projection instance authorization and state evidence must match"
                );
            }
            return;
        }
        throw new IllegalArgumentException(
            "approval assistance projection supports task or instance resources only"
        );
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String normalizeOptional(
        String value,
        String name,
        int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return normalized;
    }
}
