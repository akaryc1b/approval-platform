package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextAssembler.ResolvedFieldPermissions;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextAssembler.ServerOwnedInput;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAssistanceContextAssemblerTest {

    private static final String TENANT = "tenant-a";
    private static final String TASK_ID = "task-100";
    private static final String INSTANCE_ID = "instance-100";
    private static final String TASK_CONTEXT = "managerApproval";

    private final ApprovalAssistanceContextAssembler assembler =
        new ApprovalAssistanceContextAssembler(new AiDataMinimizer());

    @Test
    void assemblesOnlyAuthorizedVisibleMinimizedFields() {
        ApprovalAssistanceContextProjection projection = assembler.assemble(baseInput());

        assertEquals(TENANT, projection.requestContext().tenantId());
        assertEquals(TASK_ID, projection.authorizedResource().resourceId());
        assertEquals(7, projection.resourceState().stateVersion());
        assertEquals("purchase-payment", projection.process().definitionKey());
        assertEquals(2, projection.process().definitionVersion());
        assertEquals("purchase-payment-form", projection.form().formKey());
        assertEquals(3, projection.form().formVersion());
        assertEquals(4, projection.form().schemaFieldCount());
        assertEquals(2, projection.form().uiSchemaVersion());
        assertEquals(TASK_CONTEXT, projection.form().contextKey());
        assertEquals(4, projection.form().submissionRevision());

        assertEquals(
            List.of("amount", "supplier", "attachments"),
            projection.providerFields().stream()
                .map(AiProviderRequest.InputField::key)
                .toList()
        );
        AiProviderRequest.InputField supplier = projection.providerFields().get(1);
        assertEquals("***", supplier.value());
        assertEquals(
            AiProviderRequest.MaskingDisposition.MASKED,
            supplier.maskingDisposition()
        );
        assertFalse(projection.providerFields().stream()
            .anyMatch(field -> field.key().equals("internalNote")));

        Object attachmentValue = projection.providerFields().get(2).value();
        assertTrue(attachmentValue instanceof List<?>);
        Object firstAttachment = ((List<?>) attachmentValue).getFirst();
        assertTrue(firstAttachment instanceof Map<?, ?>);
        Map<?, ?> metadata = (Map<?, ?>) firstAttachment;
        assertEquals("invoice.pdf", metadata.get("fileName"));
        assertEquals("application/pdf", metadata.get("contentType"));
        assertFalse(metadata.containsKey("content"));

        assertEquals(3, projection.evidence().authorizedVisibleFieldCount());
        assertEquals(3, projection.evidence().providerFieldCount());
        assertEquals(1, projection.evidence().maskedFieldCount());
        assertEquals(1, projection.evidence().omittedFieldCount());
        assertEquals(1, projection.evidence().attachmentMetadataCount());
        assertFalse(projection.evidence().attachmentExtractionAttempted());
        assertTrue(projection.providerRequirements().attachmentMetadataOnly());
        assertTrue(projection.providerRequirements().structuredOutputRequired());
        assertEquals(
            Set.of(AiCapability.APPROVAL_SUMMARY, AiCapability.MATERIAL_COMPLETENESS),
            projection.providerRequirements().capabilities()
        );
    }

    @Test
    void omittedAttachmentDoesNotInflateProviderEvidence() {
        ServerOwnedInput input = baseInput();
        AiDataMinimizationPolicy policy = new AiDataMinimizationPolicy(
            input.dataPolicy().version(),
            Map.of(
                "supplier", AiDataMinimizationPolicy.FieldRule.MASK,
                "attachments", AiDataMinimizationPolicy.FieldRule.OMIT
            ),
            input.dataPolicy().limits(),
            true
        );

        ApprovalAssistanceContextProjection projection = assembler.assemble(
            copy(input, policy)
        );

        assertEquals(
            List.of("amount", "supplier"),
            projection.providerFields().stream()
                .map(AiProviderRequest.InputField::key)
                .toList()
        );
        assertEquals(3, projection.evidence().authorizedVisibleFieldCount());
        assertEquals(2, projection.evidence().providerFieldCount());
        assertEquals(2, projection.evidence().omittedFieldCount());
        assertEquals(0, projection.evidence().attachmentMetadataCount());
    }

    @Test
    void rejectsCrossTenantContextBeforeProjection() {
        ServerOwnedInput input = baseInput();
        AiAuthorizedResource forged = new AiAuthorizedResource(
            "tenant-b",
            AiAuthorizedResource.ResourceType.APPROVAL_TASK,
            TASK_ID,
            "authz-ref",
            input.authorizedResource().allowedFieldKeys()
        );

        AiPolicyViolationException failure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(copy(input, forged, input.resourceState(), input.permissions()))
        );
        assertEquals("AI_ASSISTANCE_CROSS_TENANT_CONTEXT", failure.code());
    }

    @Test
    void rejectsTaskStateAndPermissionContextMismatch() {
        ServerOwnedInput input = baseInput();
        ResourceStateSnapshot stale = new ResourceStateSnapshot(
            TENANT,
            INSTANCE_ID,
            "task-other",
            TASK_CONTEXT,
            ResourceState.TASK_PENDING,
            8,
            Instant.parse("2026-07-31T08:00:00Z")
        );

        AiPolicyViolationException stateFailure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(copy(
                input,
                input.authorizedResource(),
                stale,
                input.permissions()
            ))
        );
        assertEquals("AI_ASSISTANCE_TASK_STATE_MISMATCH", stateFailure.code());

        ResolvedFieldPermissions wrongContext = new ResolvedFieldPermissions(
            "financeApproval",
            2,
            "ui-hash-v2",
            input.permissions().fieldAccess()
        );
        AiPolicyViolationException permissionFailure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(copy(
                input,
                input.authorizedResource(),
                input.resourceState(),
                wrongContext
            ))
        );
        assertEquals(
            "AI_ASSISTANCE_PERMISSION_CONTEXT_MISMATCH",
            permissionFailure.code()
        );
    }

    @Test
    void rejectsUnknownFieldsAndRawAttachmentIdentity() {
        ServerOwnedInput input = baseInput();
        Map<String, Object> unknownValues = new LinkedHashMap<>(input.values());
        unknownValues.put("notInSchema", "forged");
        ServerOwnedInput unknown = copy(input, unknownValues, input.sensitiveFieldKeys());

        AiPolicyViolationException unknownFailure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(unknown)
        );
        assertEquals("AI_ASSISTANCE_UNKNOWN_VALUE_FIELD", unknownFailure.code());

        Map<String, Object> rawAttachment = new LinkedHashMap<>(input.values());
        rawAttachment.put("attachments", List.of("attachment-id-only"));
        ServerOwnedInput raw = copy(input, rawAttachment, input.sensitiveFieldKeys());
        AiPolicyViolationException attachmentFailure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(raw)
        );
        assertEquals("AI_ATTACHMENT_CONTENT_NOT_ALLOWED", attachmentFailure.code());
    }

    @Test
    void rejectsMismatchedFormAndUiSchemaEvidence() {
        ServerOwnedInput input = baseInput();
        UiSchemaDefinition wrongUi = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            "other-form",
            3,
            2,
            "Wrong UI",
            input.uiSchema().sections(),
            input.uiSchema().nodePermissions()
        );
        ServerOwnedInput mismatch = new ServerOwnedInput(
            input.requestContext(),
            input.authorizedResource(),
            input.process(),
            input.resourceState(),
            input.formDefinition(),
            input.formContentHash(),
            wrongUi,
            input.permissions(),
            input.values(),
            input.sensitiveFieldKeys(),
            input.dataPolicy(),
            input.requiredProviderCapabilities(),
            input.submissionRevision()
        );

        AiPolicyViolationException failure = assertThrows(
            AiPolicyViolationException.class,
            () -> assembler.assemble(mismatch)
        );
        assertEquals("AI_ASSISTANCE_FORM_UI_SCHEMA_MISMATCH", failure.code());
    }

    @Test
    void supportsRunningInstanceWithoutTaskAuthority() {
        ServerOwnedInput input = baseInput();
        AiAuthorizedResource instanceResource = new AiAuthorizedResource(
            TENANT,
            AiAuthorizedResource.ResourceType.PROCESS_INSTANCE,
            INSTANCE_ID,
            "instance-authz-ref",
            input.authorizedResource().allowedFieldKeys()
        );
        ResourceStateSnapshot instanceState = new ResourceStateSnapshot(
            TENANT,
            INSTANCE_ID,
            null,
            null,
            ResourceState.INSTANCE_RUNNING,
            12,
            Instant.parse("2026-07-31T08:00:00Z")
        );

        ApprovalAssistanceContextProjection projection = assembler.assemble(copy(
            input,
            instanceResource,
            instanceState,
            input.permissions()
        ));
        assertEquals(ResourceState.INSTANCE_RUNNING, projection.resourceState().state());
        assertEquals(INSTANCE_ID, projection.authorizedResource().resourceId());
        assertEquals(12, projection.resourceState().stateVersion());
    }

    private static ServerOwnedInput baseInput() {
        FormDefinition form = form();
        UiSchemaDefinition uiSchema = uiSchema(form);
        Map<String, FieldAccess> permissions = Map.of(
            "amount", FieldAccess.READONLY,
            "supplier", FieldAccess.READONLY,
            "internalNote", FieldAccess.HIDDEN,
            "attachments", FieldAccess.READONLY
        );
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("amount", new BigDecimal("1250.00"));
        values.put("supplier", "Sensitive Supplier");
        values.put("internalNote", "must never reach Provider mapping");
        values.put("attachments", List.of(new AiSourceField.AttachmentMetadata(
            "attachment-1",
            "invoice.pdf",
            "application/pdf",
            4096,
            "sha256-invoice"
        )));

        return new ServerOwnedInput(
            new AiServerRequestContext(TENANT, "manager-a", "request-1", "trace-1"),
            new AiAuthorizedResource(
                TENANT,
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                TASK_ID,
                "authz-ref",
                Set.of("amount", "supplier", "internalNote", "attachments")
            ),
            new ProcessSnapshot(
                "purchase-payment",
                2,
                "compiler-1.1.0",
                "definition-hash-v2",
                form.formKey(),
                form.version(),
                5,
                "release-package-hash-v5"
            ),
            new ResourceStateSnapshot(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                TASK_CONTEXT,
                ResourceState.TASK_PENDING,
                7,
                Instant.parse("2026-07-31T08:00:00Z")
            ),
            form,
            "form-hash-v3",
            uiSchema,
            new ResolvedFieldPermissions(
                TASK_CONTEXT,
                uiSchema.version(),
                "ui-hash-v2",
                permissions
            ),
            values,
            Set.of("supplier"),
            new AiDataMinimizationPolicy(
                new PolicyVersion("approval-assistance", "v1", "policy-hash-v1"),
                Map.of("supplier", AiDataMinimizationPolicy.FieldRule.MASK),
                new AiDataMinimizationPolicy.InputLimits(16, 1000, 4000, 8, 3),
                true
            ),
            Set.of(AiCapability.APPROVAL_SUMMARY, AiCapability.MATERIAL_COMPLETENESS),
            4
        );
    }

    private static FormDefinition form() {
        return new FormDefinition(
            FormDefinition.CURRENT_SCHEMA_VERSION,
            "purchase-payment-form",
            3,
            "Purchase payment",
            List.of(
                new FormDefinition.FormField(
                    "amount",
                    FormDefinition.FieldType.MONEY,
                    "Amount",
                    true,
                    FormDefinition.FieldConstraints.none()
                ),
                new FormDefinition.FormField(
                    "supplier",
                    FormDefinition.FieldType.TEXT,
                    "Supplier",
                    true,
                    FormDefinition.FieldConstraints.text(200)
                ),
                new FormDefinition.FormField(
                    "internalNote",
                    FormDefinition.FieldType.TEXTAREA,
                    "Internal note",
                    false,
                    FormDefinition.FieldConstraints.text(1000)
                ),
                new FormDefinition.FormField(
                    "attachments",
                    FormDefinition.FieldType.ATTACHMENT,
                    "Attachments",
                    false,
                    FormDefinition.FieldConstraints.attachments(0, true)
                )
            )
        );
    }

    private static UiSchemaDefinition uiSchema(FormDefinition form) {
        List<FieldLayout> layouts = form.fields().stream()
            .map(field -> new FieldLayout(field.key(), null, null, 24))
            .toList();
        List<FieldPermission> permissions = List.of(
            new FieldPermission("amount", FieldAccess.READONLY),
            new FieldPermission("supplier", FieldAccess.READONLY),
            new FieldPermission("internalNote", FieldAccess.HIDDEN),
            new FieldPermission("attachments", FieldAccess.READONLY)
        );
        return new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            form.formKey(),
            form.version(),
            2,
            "Approval UI",
            List.of(new Section("main", "Main", null, false, layouts)),
            List.of(
                new NodePermissions(TASK_CONTEXT, permissions),
                new NodePermissions("instanceSummary", permissions)
            )
        );
    }

    private static ServerOwnedInput copy(
        ServerOwnedInput input,
        AiAuthorizedResource resource,
        ResourceStateSnapshot state,
        ResolvedFieldPermissions permissions
    ) {
        return new ServerOwnedInput(
            input.requestContext(),
            resource,
            input.process(),
            state,
            input.formDefinition(),
            input.formContentHash(),
            input.uiSchema(),
            permissions,
            input.values(),
            input.sensitiveFieldKeys(),
            input.dataPolicy(),
            input.requiredProviderCapabilities(),
            input.submissionRevision()
        );
    }

    private static ServerOwnedInput copy(
        ServerOwnedInput input,
        Map<String, Object> values,
        Set<String> sensitiveFields
    ) {
        return new ServerOwnedInput(
            input.requestContext(),
            input.authorizedResource(),
            input.process(),
            input.resourceState(),
            input.formDefinition(),
            input.formContentHash(),
            input.uiSchema(),
            input.permissions(),
            values,
            sensitiveFields,
            input.dataPolicy(),
            input.requiredProviderCapabilities(),
            input.submissionRevision()
        );
    }

    private static ServerOwnedInput copy(
        ServerOwnedInput input,
        AiDataMinimizationPolicy policy
    ) {
        return new ServerOwnedInput(
            input.requestContext(),
            input.authorizedResource(),
            input.process(),
            input.resourceState(),
            input.formDefinition(),
            input.formContentHash(),
            input.uiSchema(),
            input.permissions(),
            input.values(),
            input.sensitiveFieldKeys(),
            policy,
            input.requiredProviderCapabilities(),
            input.submissionRevision()
        );
    }
}
