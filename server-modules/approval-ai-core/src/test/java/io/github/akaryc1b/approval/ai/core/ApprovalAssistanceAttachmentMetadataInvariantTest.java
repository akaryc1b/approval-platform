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

class ApprovalAssistanceAttachmentMetadataInvariantTest {

    @Test
    void rejectsBlankAttachmentMetadataText() {
        Map<String, Object> metadata = Map.of(
            "attachmentId", "attachment-1",
            "fileName", "   ",
            "contentType", "application/pdf",
            "sizeBytes", 4096L,
            "sha256", "sha256-invoice"
        );

        assertThrows(IllegalArgumentException.class, () -> projection(metadata));
    }

    @Test
    void rejectsNonLongAttachmentSizeMetadata() {
        Map<String, Object> metadata = Map.of(
            "attachmentId", "attachment-1",
            "fileName", "invoice.pdf",
            "contentType", "application/pdf",
            "sizeBytes", 4096.5,
            "sha256", "sha256-invoice"
        );

        assertThrows(IllegalArgumentException.class, () -> projection(metadata));
    }

    private static ApprovalAssistanceContextProjection projection(
        Map<String, Object> metadata
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext(
                "tenant-a",
                "operator-a",
                "request-1",
                "trace-1"
            ),
            new AiAuthorizedResource(
                "tenant-a",
                AiAuthorizedResource.ResourceType.APPROVAL_TASK,
                "task-1",
                "authz-ref",
                Set.of("attachments")
            ),
            new ProcessSnapshot(
                "purchase-payment",
                2,
                "compiler-1.1.0",
                "definition-hash-v2",
                "purchase-form",
                3,
                5,
                "release-package-hash-v5"
            ),
            new ResourceStateSnapshot(
                "tenant-a",
                "instance-1",
                "task-1",
                "managerApproval",
                ResourceState.TASK_PENDING,
                7,
                Instant.parse("2026-07-31T08:00:00Z")
            ),
            new FormSnapshot(
                "purchase-form",
                3,
                "1.0",
                "form-hash-v3",
                1,
                2,
                "ui-hash-v2",
                "managerApproval",
                4
            ),
            List.of(new InputField(
                "attachments",
                "ATTACHMENT",
                List.of(metadata),
                MaskingDisposition.INCLUDED
            )),
            new ProviderRequirements(
                Set.of(AiCapability.APPROVAL_SUMMARY),
                1,
                255,
                1000,
                8,
                3,
                true,
                true
            ),
            new PolicyVersion("approval-assistance", "v1", "policy-hash-v1"),
            new ProjectionEvidence(1, 1, 0, 0, 1, false)
        );
    }
}
