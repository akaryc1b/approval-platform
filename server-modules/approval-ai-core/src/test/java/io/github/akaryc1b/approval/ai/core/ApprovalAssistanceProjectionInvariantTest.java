package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.FormSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProcessSnapshot;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProjectionEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ProviderRequirements;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceState;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceContextProjection.ResourceStateSnapshot;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences.PolicyVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalAssistanceProjectionInvariantTest {

    @Test
    void rejectsDirectProcessAndFormMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1"),
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("other-form", 3, "managerApproval")
        ));
    }

    @Test
    void rejectsDirectTaskIdentityMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1"),
            taskState("task-2", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "managerApproval")
        ));
    }

    @Test
    void rejectsDirectTaskPermissionContextMismatch() {
        assertThrows(IllegalArgumentException.class, () -> projection(
            taskResource("task-1"),
            taskState("task-1", "managerApproval"),
            process("purchase-form", 3),
            form("purchase-form", 3, "financeApproval")
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
            form("purchase-form", 3, "managerApproval")
        ));
    }

    private static ApprovalAssistanceContextProjection projection(
        AiAuthorizedResource resource,
        ResourceStateSnapshot state,
        ProcessSnapshot process,
        FormSnapshot form
    ) {
        return new ApprovalAssistanceContextProjection(
            new AiServerRequestContext("tenant-a", "operator-a", "request-1", "trace-1"),
            resource,
            process,
            state,
            form,
            List.of(),
            new ProviderRequirements(
                Set.of(AiCapability.APPROVAL_SUMMARY),
                8,
                8,
                3,
                true,
                true
            ),
            new PolicyVersion("approval-assistance", "v1", "policy-hash-v1"),
            new ProjectionEvidence(0, 0, 0, 0, 0, false)
        );
    }

    private static AiAuthorizedResource taskResource(String taskId) {
        return new AiAuthorizedResource(
            "tenant-a",
            AiAuthorizedResource.ResourceType.APPROVAL_TASK,
            taskId,
            "authz-ref",
            Set.of()
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
