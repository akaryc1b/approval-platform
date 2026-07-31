package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.InputField;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest.MaskingDisposition;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssistanceProjectionInvariantTest {

    @Test
    void rejectsDirectProcessAndFormMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1", Set.of()),
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("other-form", 3, "managerApproval"),
            List.of(),
            requirements(8, 100, 1000),
            evidence(0, 0, 0)
        ));
    }

    @Test
    void rejectsDirectTaskIdentityMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1", Set.of()),
            taskState("task-2", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "managerApproval"),
            List.of(),
            requirements(8, 100, 1000),
            evidence(0, 0, 0)
        ));
    }

    @Test
    void rejectsDirectTaskPermissionContextMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1", Set.of()),
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "financeApproval"),
            List.of(),
            requirements(8, 100, 1000),
            evidence(0, 0, 0)
        ));
    }

    @Test
    void rejectsUnsupportedFormSubmissionAuthority() {
        AiAuthorizedResource formSubmission = new AiAuthorizedResource(
            "tenant-a",
            AiAuthorizedResource.ResourceType.FORM_SUBMISSION,
            "submission-1",
            "authz-ref",
            Set.of()
        );
        assertThrows(IllegalArgumentException.class, () -> projection(
            formSubmission,
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "managerApproval"),
            List.of(),
            requirements(8, 100, 1000),
            evidence(0, 0, 0)
        ));
    }

    @Test
    void rejectsFieldsBeyondDeclaredProviderLimit() {
        List<InputField> fields = List.of(
            new InputField("summary", "TEXT", "one", MaskingDisposition.INCLUDED),
            new InputField("supplier", "TEXT", "two", MaskingDisposition.INCLUDED)
        );
        assertThrows(IllegalArgumentException.class, () -> validProjection(
            Set.of("summary", "supplier"),
            fields,
            requirements(1, 100, 1000),
            evidence(2, 0, 0)
        ));
    }

    @Test
    void rejectsTextBeyondDeclaredCharacterBudget() {
        List<InputField> fields = List.of(
            new InputField("summary", "TEXT", "four", MaskingDisposition.INCLUDED)
        );
        assertThrows(IllegalArgumentException.class, () -> validProjection(
            Set.of("summary"),
            fields,
            requirements(1, 3, 10),
            evidence(1, 0, 0)
        ));
    }

    @Test
    void rejectsAttachmentValueContainingRawContent() {
        Map<String, Object> unsafe = Map.of(
            "attachmentId", "attachment-1",
            "fileName", "invoice.pdf",
            "contentType", "application/pdf",
            "sizeBytes", 4096L,
            "sha256", "sha256-invoice",
            "content", "raw-content"
        );
        List<InputField> fields = List.of(
            new InputField(
                "attachments",
                "ATTACHMENT",
                List.of(unsafe),
                MaskingDisposition.INCLUDED
            )
        );
        assertThrows(IllegalArgumentException.class, () -> validProjection(
            Set.of("attachments"),
            fields,
            requirements(1, 100, 1000),
            evidence(1, 0, 1)
        ));
    }

    @Test
    void rejectsMaskedAndAttachmentEvidenceMismatch() {
        Map<String, Object> metadata = Map.of(
            "attachmentId", "attachment-1",
            "fileName", "invoice.pdf",
            "contentType", "application/pdf",
            "sizeBytes", 4096L,
            "sha256", "sha256-invoice"
        );
        List<InputField> fields = List.of(
            new InputField("supplier", "TEXT", "***", MaskingDisposition.MASKED),
            new InputField(
                "attachments",
                "ATTACHMENT",
                List.of(metadata),
                MaskingDisposition.INCLUDED
            )
        );
        assertThrows(IllegalArgumentException.class, () -> validProjection(
            Set.of("supplier", "attachments"),
            fields,
            requirements(2, 100, 1000),
            evidence(2, 0, 0)
        ));
    }

    private static ApprovalAssistanceContextProjection validProjection(
        Set<String> allowedFields,
        List<InputField> fields,
        ProviderRequirements requirements,
        ProjectionEvidence evidence
    ) {
        return projection(
            taskResource("task-1", allowedFields),
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "managerApproval"),
            fields,
            requirements,
            evidence
        );
    }

    private static ApprovalAssistanceContextProjection projection(
        AiAuthorizedResource resource,
        ResourceStateSnapshot state,
        ProcessSnapshot process,
        FormSnapshot form,
        List<InputField> providerFields,
        ProviderRequirements requirements,
        ProjectionEvidence evidence
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext("tenant-a", "operator-a", "request-1", "trace-1"),
            resource,
            process,
            state,
            form,
            providerFields,
            requirements,
            new PolicyVersion("approval-assistance", "v1", "policy-hash-v1"),
            evidence
        );
    }

    private static ProviderRequirements requirements(
        int maximumFields,
        int maximumTextCharactersPerValue,
        int maximumTotalTextCharacters
    ) {
        return new ProviderRequirements(
            Set.of(AiCapability.APPROVAL_SUMMARY),
            maximumFields,
            maximumTextCharactersPerValue,
            maximumTotalTextCharacters,
            8,
            3,
            true,
            true
        );
    }

    private static ProjectionEvidence evidence(
        int providerFields,
        int maskedFields,
        int attachmentMetadata
    ) {
        return new ProjectionEvidence(
            providerFields,
            providerFields,
            maskedFields,
            0,
            attachmentMetadata,
            false
        );
    }

    private static AiAuthorizedResource taskResource(
        String taskId,
        Set<String> allowedFields
    ) {
        return new AiAuthorizedResource(
            "tenant-a",
            AiAuthorizedResource.ResourceType.APPROVAL_TASK,
            taskId,
            "authz-ref",
            allowedFields
        );
    }

    private static ResourceStateSnapshot taskState(
        String taskId,
        String taskDefinitionKey
    ) {
        return new ResourceStateSnapshot(
            "tenant-a",
            "instance-1",
            taskId,
            taskDefinitionKey,
            ResourceState.TASK_PENDING,
            7,
            Instant.parse("2026-07-31T08:00:00Z")
        );
    }

    private static ProcessSnapshot process(String formKey, int formVersion) {
        return new ProcessSnapshot(
            "purchase-payment",
            2,
            "compiler-1.1.0",
            "definition-hash-v2",
            formKey,
            formVersion,
            5,
            "release-package-hash-v5"
        );
    }

    private static FormSnapshot form(
        String formKey,
        int formVersion,
        String contextKey
    ) {
        return new FormSnapshot(
            formKey,
            formVersion,
            "1.0",
            "form-hash-v3",
            2,
            "ui-hash-v2",
            contextKey,
            4
        );
    }
}
