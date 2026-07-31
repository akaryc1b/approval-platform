package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    private static final Set<String> ATTACHMENT_METADATA_KEYS = Set.of(
        "attachmentId",
        "fileName",
        "contentType",
        "sizeBytes",
        "sha256"
    );

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
        ProviderValueEvidence valueEvidence = validateProviderFields(
            providerFields,
            authorizedResource,
            providerRequirements
        );
        validateEvidence(providerFields, evidence, valueEvidence);
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
        int maximumTextCharactersPerValue,
        int maximumTotalTextCharacters,
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
                || maximumTextCharactersPerValue < 1
                || maximumTotalTextCharacters < 1
                || maximumCollectionSize < 1
                || maximumDepth < 1) {
                throw new IllegalArgumentException(
                    "provider input requirements must be positive"
                );
            }
            if (maximumTextCharactersPerValue > maximumTotalTextCharacters) {
                throw new IllegalArgumentException(
                    "per-value text limit cannot exceed total text limit"
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

    private static ProviderValueEvidence validateProviderFields(
        List<AiProviderRequest.InputField> providerFields,
        AiAuthorizedResource authorizedResource,
        ProviderRequirements requirements
    ) {
        if (providerFields.size() > requirements.maximumInputFields()) {
            throw new IllegalArgumentException(
                "provider fields exceed the declared input-field limit"
            );
        }
        Set<String> fieldKeys = new HashSet<>();
        TextBudget budget = new TextBudget(requirements.maximumTotalTextCharacters());
        int maskedFieldCount = 0;
        int attachmentMetadataCount = 0;
        for (AiProviderRequest.InputField field : providerFields) {
            if (!fieldKeys.add(field.key())) {
                throw new IllegalArgumentException("provider field keys must be unique");
            }
            if (!authorizedResource.allowedFieldKeys().contains(field.key())) {
                throw new IllegalArgumentException(
                    "provider field is not authorized: " + field.key()
                );
            }
            if (field.maskingDisposition()
                == AiProviderRequest.MaskingDisposition.MASKED) {
                maskedFieldCount++;
            }
            if ("ATTACHMENT".equals(field.type())) {
                attachmentMetadataCount += validateAttachmentValue(
                    field.value(),
                    requirements,
                    budget
                );
            } else {
                validateProviderValue(field.value(), 1, requirements, budget);
            }
        }
        return new ProviderValueEvidence(maskedFieldCount, attachmentMetadataCount);
    }

    private static int validateAttachmentValue(
        Object value,
        ProviderRequirements requirements,
        TextBudget budget
    ) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException(
                "attachment provider value must be a metadata collection"
            );
        }
        validateCollectionSize(collection.size(), requirements);
        for (Object entry : collection) {
            if (!(entry instanceof Map<?, ?> metadata)
                || metadata.size() != ATTACHMENT_METADATA_KEYS.size()
                || !metadata.keySet().equals(ATTACHMENT_METADATA_KEYS)) {
                throw new IllegalArgumentException(
                    "attachment provider value contains non-metadata content"
                );
            }
            validateProviderValue(metadata, 2, requirements, budget);
        }
        return collection.size();
    }

    private static void validateProviderValue(
        Object value,
        int depth,
        ProviderRequirements requirements,
        TextBudget budget
    ) {
        Objects.requireNonNull(value, "provider value must not be null");
        if (depth > requirements.maximumDepth()) {
            throw new IllegalArgumentException(
                "provider value exceeds the declared nesting-depth limit"
            );
        }
        if (value instanceof String text) {
            validateText(text, requirements, budget);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            String rendered = value.toString();
            validateText(rendered, requirements, budget);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            validateCollectionSize(map.size(), requirements);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                        "provider map keys must be strings"
                    );
                }
                validateText(key, requirements, budget);
                validateProviderValue(
                    entry.getValue(),
                    depth + 1,
                    requirements,
                    budget
                );
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            validateCollectionSize(collection.size(), requirements);
            for (Object entry : collection) {
                validateProviderValue(entry, depth + 1, requirements, budget);
            }
            return;
        }
        throw new IllegalArgumentException(
            "provider value contains an unsupported runtime type"
        );
    }

    private static void validateCollectionSize(
        int size,
        ProviderRequirements requirements
    ) {
        if (size > requirements.maximumCollectionSize()) {
            throw new IllegalArgumentException(
                "provider value exceeds the declared collection-size limit"
            );
        }
    }

    private static void validateText(
        String text,
        ProviderRequirements requirements,
        TextBudget budget
    ) {
        if (text.length() > requirements.maximumTextCharactersPerValue()) {
            throw new IllegalArgumentException(
                "provider text exceeds the declared per-value character limit"
            );
        }
        budget.add(text.length());
    }

    private static void validateEvidence(
        List<AiProviderRequest.InputField> providerFields,
        ProjectionEvidence evidence,
        ProviderValueEvidence valueEvidence
    ) {
        if (providerFields.size() != evidence.providerFieldCount()) {
            throw new IllegalArgumentException(
                "provider field count does not match projection evidence"
            );
        }
        if (valueEvidence.maskedFieldCount() != evidence.maskedFieldCount()) {
            throw new IllegalArgumentException(
                "masked field count does not match projection evidence"
            );
        }
        if (valueEvidence.attachmentMetadataCount()
            != evidence.attachmentMetadataCount()) {
            throw new IllegalArgumentException(
                "attachment metadata count does not match projection evidence"
            );
        }
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

    private record ProviderValueEvidence(
        int maskedFieldCount,
        int attachmentMetadataCount
    ) {
    }

    private static final class TextBudget {

        private final int maximum;
        private int used;

        private TextBudget(int maximum) {
            this.maximum = maximum;
        }

        private void add(int amount) {
            used += amount;
            if (used > maximum) {
                throw new IllegalArgumentException(
                    "provider values exceed the declared total character limit"
                );
            }
        }
    }
}
