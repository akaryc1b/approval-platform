package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a purpose-specific Provider-safe approval projection. The assembler accepts no browser
 * identity, complete persistence entity, attachment body or executable command.
 */
public final class ApprovalAssistanceContextAssembler {

    private final AiDataMinimizer minimizer;

    public ApprovalAssistanceContextAssembler(AiDataMinimizer minimizer) {
        this.minimizer = Objects.requireNonNull(minimizer, "minimizer must not be null");
    }

    public ApprovalAssistanceContextProjection assemble(ServerOwnedInput input) {
        Objects.requireNonNull(input, "input must not be null");
        validateTenantAndResource(input);
        validateSchemaBinding(input);

        Set<String> schemaFields = indexedFields(input.formDefinition()).keySet();
        validateFieldKeys(input, schemaFields);

        List<AiSourceField> sourceFields = new ArrayList<>();
        int attachmentMetadataCount = 0;
        for (FormDefinition.FormField definition : input.formDefinition().fields()) {
            String key = definition.key();
            FieldAccess access = input.permissions().fieldAccess()
                .getOrDefault(key, FieldAccess.HIDDEN);
            Object value = input.values().get(key);
            if (!input.authorizedResource().allowedFieldKeys().contains(key)
                || access == FieldAccess.HIDDEN
                || value == null) {
                continue;
            }
            if (definition.type() == FormDefinition.FieldType.ATTACHMENT) {
                attachmentMetadataCount += validateAttachmentMetadata(value);
            }
            sourceFields.add(new AiSourceField(
                key,
                definition.type(),
                access,
                true,
                input.sensitiveFieldKeys().contains(key),
                value
            ));
        }
        if (sourceFields.isEmpty()) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_NO_AUTHORIZED_FIELDS",
                "approval assistance has no authorized visible fields"
            );
        }

        List<AiProviderRequest.InputField> providerFields = minimizer.minimize(
            input.authorizedResource().allowedFieldKeys(),
            sourceFields,
            input.dataPolicy()
        );
        if (providerFields.isEmpty()) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_NO_PROVIDER_FIELDS",
                "approval assistance policy omitted every authorized field"
            );
        }

        int maskedFields = (int) providerFields.stream()
            .filter(field -> field.maskingDisposition()
                == AiProviderRequest.MaskingDisposition.MASKED)
            .count();
        int omittedFields = input.formDefinition().fields().size() - providerFields.size();
        AiDataMinimizationPolicy.InputLimits limits = input.dataPolicy().limits();

        return new ApprovalAssistanceContextProjection(
            input.requestContext(),
            input.authorizedResource(),
            input.process(),
            input.resourceState(),
            new FormSnapshot(
                input.formDefinition().formKey(),
                input.formDefinition().version(),
                input.formDefinition().schemaVersion(),
                input.formContentHash(),
                input.uiSchema().version(),
                input.permissions().uiSchemaHash(),
                input.permissions().contextKey(),
                input.submissionRevision()
            ),
            providerFields,
            new ProviderRequirements(
                input.requiredProviderCapabilities(),
                limits.maximumFields(),
                limits.maximumTextCharactersPerValue(),
                limits.maximumTotalTextCharacters(),
                limits.maximumCollectionSize(),
                limits.maximumDepth(),
                true,
                true
            ),
            input.dataPolicy().version(),
            new ProjectionEvidence(
                sourceFields.size(),
                providerFields.size(),
                maskedFields,
                omittedFields,
                attachmentMetadataCount,
                false
            )
        );
    }

    private static void validateTenantAndResource(ServerOwnedInput input) {
        String tenantId = input.requestContext().tenantId();
        if (!tenantId.equals(input.authorizedResource().tenantId())
            || !tenantId.equals(input.resourceState().tenantId())) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_CROSS_TENANT_CONTEXT",
                "approval assistance tenant evidence does not match"
            );
        }
        if (input.authorizedResource().resourceType()
            == AiAuthorizedResource.ResourceType.APPROVAL_TASK) {
            if (input.resourceState().state() != ResourceState.TASK_PENDING
                || !input.authorizedResource().resourceId()
                    .equals(input.resourceState().taskId())) {
                throw new AiPolicyViolationException(
                    "AI_ASSISTANCE_TASK_STATE_MISMATCH",
                    "authorized task does not match the current pending task state"
                );
            }
            if (!input.permissions().contextKey()
                .equals(input.resourceState().taskDefinitionKey())) {
                throw new AiPolicyViolationException(
                    "AI_ASSISTANCE_PERMISSION_CONTEXT_MISMATCH",
                    "field permissions do not match the current task definition"
                );
            }
            return;
        }
        if (input.authorizedResource().resourceType()
            == AiAuthorizedResource.ResourceType.PROCESS_INSTANCE) {
            if (input.resourceState().state() != ResourceState.INSTANCE_RUNNING
                || !input.authorizedResource().resourceId()
                    .equals(input.resourceState().instanceId())) {
                throw new AiPolicyViolationException(
                    "AI_ASSISTANCE_INSTANCE_STATE_MISMATCH",
                    "authorized instance does not match the current running state"
                );
            }
            return;
        }
        throw new AiPolicyViolationException(
            "AI_ASSISTANCE_RESOURCE_TYPE_UNSUPPORTED",
            "approval assistance supports task or process-instance resources only"
        );
    }

    private static void validateSchemaBinding(ServerOwnedInput input) {
        FormDefinition form = input.formDefinition();
        UiSchemaDefinition uiSchema = input.uiSchema();
        ProcessSnapshot process = input.process();
        ResolvedFieldPermissions permissions = input.permissions();
        if (!process.formKey().equals(form.formKey())
            || process.formVersion() != form.version()) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_PROCESS_FORM_MISMATCH",
                "process snapshot does not bind the supplied Form Schema"
            );
        }
        if (!form.formKey().equals(uiSchema.formKey())
            || form.version() != uiSchema.formVersion()) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_FORM_UI_SCHEMA_MISMATCH",
                "UI Schema does not bind the supplied Form Schema"
            );
        }
        if (uiSchema.version() != permissions.uiSchemaVersion()) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_UI_SCHEMA_VERSION_MISMATCH",
                "resolved permissions use a different UI Schema version"
            );
        }
        boolean contextExists = uiSchema.nodePermissions().stream()
            .anyMatch(item -> item.contextKey().equals(permissions.contextKey()));
        if (!contextExists) {
            throw new AiPolicyViolationException(
                "AI_ASSISTANCE_PERMISSION_CONTEXT_MISSING",
                "exact UI Schema permission context is required"
            );
        }
    }

    private static Map<String, FormDefinition.FormField> indexedFields(
        FormDefinition form
    ) {
        Map<String, FormDefinition.FormField> fields = new LinkedHashMap<>();
        for (FormDefinition.FormField field : form.fields()) {
            if (fields.put(field.key(), field) != null) {
                throw new AiPolicyViolationException(
                    "AI_ASSISTANCE_DUPLICATE_FORM_FIELD",
                    "Form Schema contains duplicate field keys"
                );
            }
        }
        return Map.copyOf(fields);
    }

    private static void validateFieldKeys(
        ServerOwnedInput input,
        Set<String> schemaFields
    ) {
        rejectUnknown(input.values().keySet(), schemaFields, "AI_ASSISTANCE_UNKNOWN_VALUE_FIELD");
        rejectUnknown(
            input.permissions().fieldAccess().keySet(),
            schemaFields,
            "AI_ASSISTANCE_UNKNOWN_PERMISSION_FIELD"
        );
        rejectUnknown(
            input.sensitiveFieldKeys(),
            schemaFields,
            "AI_ASSISTANCE_UNKNOWN_SENSITIVE_FIELD"
        );
        rejectUnknown(
            input.authorizedResource().allowedFieldKeys(),
            schemaFields,
            "AI_ASSISTANCE_UNKNOWN_AUTHORIZED_FIELD"
        );
    }

    private static void rejectUnknown(
        Set<String> supplied,
        Set<String> schemaFields,
        String code
    ) {
        Set<String> unknown = new HashSet<>(supplied);
        unknown.removeAll(schemaFields);
        if (!unknown.isEmpty()) {
            throw new AiPolicyViolationException(
                code,
                "approval assistance field evidence is not present in the Form Schema"
            );
        }
    }

    private static int validateAttachmentMetadata(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new AiPolicyViolationException(
                "AI_ATTACHMENT_CONTENT_NOT_ALLOWED",
                "attachment values must be metadata collections"
            );
        }
        for (Object entry : collection) {
            if (!(entry instanceof AiSourceField.AttachmentMetadata)) {
                throw new AiPolicyViolationException(
                    "AI_ATTACHMENT_CONTENT_NOT_ALLOWED",
                    "raw attachment identity or content is prohibited"
                );
            }
        }
        return collection.size();
    }

    public record ResolvedFieldPermissions(
        String contextKey,
        int uiSchemaVersion,
        String uiSchemaHash,
        Map<String, FieldAccess> fieldAccess
    ) {
        public ResolvedFieldPermissions {
            contextKey = requireText(contextKey, "contextKey", 160);
            if (uiSchemaVersion < 1) {
                throw new IllegalArgumentException("uiSchemaVersion must be positive");
            }
            uiSchemaHash = requireText(uiSchemaHash, "uiSchemaHash", 160);
            fieldAccess = fieldAccess == null ? Map.of() : Map.copyOf(fieldAccess);
        }
    }

    public record ServerOwnedInput(
        AiServerRequestContext requestContext,
        AiAuthorizedResource authorizedResource,
        ProcessSnapshot process,
        ResourceStateSnapshot resourceState,
        FormDefinition formDefinition,
        String formContentHash,
        UiSchemaDefinition uiSchema,
        ResolvedFieldPermissions permissions,
        Map<String, Object> values,
        Set<String> sensitiveFieldKeys,
        AiDataMinimizationPolicy dataPolicy,
        Set<AiCapability> requiredProviderCapabilities,
        int submissionRevision
    ) {
        public ServerOwnedInput {
            requestContext = Objects.requireNonNull(requestContext, "requestContext must not be null");
            authorizedResource = Objects.requireNonNull(
                authorizedResource,
                "authorizedResource must not be null"
            );
            process = Objects.requireNonNull(process, "process must not be null");
            resourceState = Objects.requireNonNull(resourceState, "resourceState must not be null");
            formDefinition = Objects.requireNonNull(
                formDefinition,
                "formDefinition must not be null"
            );
            formContentHash = requireText(formContentHash, "formContentHash", 160);
            uiSchema = Objects.requireNonNull(uiSchema, "uiSchema must not be null");
            permissions = Objects.requireNonNull(permissions, "permissions must not be null");
            values = values == null ? Map.of() : Map.copyOf(values);
            sensitiveFieldKeys = sensitiveFieldKeys == null
                ? Set.of()
                : Set.copyOf(sensitiveFieldKeys);
            dataPolicy = Objects.requireNonNull(dataPolicy, "dataPolicy must not be null");
            requiredProviderCapabilities = requiredProviderCapabilities == null
                ? Set.of()
                : Set.copyOf(requiredProviderCapabilities);
            if (submissionRevision < 0) {
                throw new IllegalArgumentException("submissionRevision must not be negative");
            }
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
}
