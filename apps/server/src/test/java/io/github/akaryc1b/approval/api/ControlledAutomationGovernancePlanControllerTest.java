package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Operation;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernancePlanContracts.Status;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernancePlanControllerTest {

    @Test
    void endpointReturnsNoStoreNonExecutablePlan() {
        ControlledAutomationGovernancePlanController controller = controller();

        var response = controller.preview("tenant-a", Operation.CANARY);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNotNull(response.getBody());
        assertEquals(Operation.CANARY, response.getBody().operation());
        assertEquals(Status.BLOCKED, response.getBody().status());
        assertFalse(response.getBody().applyAuthorized());
        assertFalse(response.getBody().trafficMutationAuthorized());
        assertFalse(response.getBody().providerInvocationAuthorized());
    }

    @Test
    void endpointRequiresCanonicalTenantAndDeclaresOnlyGetMapping() throws Exception {
        ControlledAutomationGovernancePlanController controller = controller();

        assertThrows(
            IllegalArgumentException.class,
            () -> controller.preview(" ", Operation.ROLLBACK)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> controller.preview(" tenant-a", Operation.ROLLBACK)
        );
        var method = ControlledAutomationGovernancePlanController.class
            .getDeclaredMethod("preview", String.class, Operation.class);
        assertNotNull(method.getAnnotation(GetMapping.class));
        assertFalse(
            List.of(ControlledAutomationGovernancePlanController.class.getDeclaredMethods())
                .stream()
                .anyMatch(candidate -> candidate.getAnnotation(PostMapping.class) != null
                    || candidate.getAnnotation(PutMapping.class) != null
                    || candidate.getAnnotation(PatchMapping.class) != null
                    || candidate.getAnnotation(DeleteMapping.class) != null)
        );
    }

    @Test
    void endpointRequiresTenantScopedManagementReadPermission() {
        ApprovalManagementPermission permission =
            ControlledAutomationGovernancePlanController.class.getAnnotation(
                ApprovalManagementPermission.class
            );

        assertNotNull(permission);
        assertEquals(ApprovalManagementPermission.Requirement.READ, permission.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            permission.resourceScope()
        );
    }

    private static ControlledAutomationGovernancePlanController controller() {
        return new ControlledAutomationGovernancePlanController(
            () -> OperationsView.disabled(
                Instant.parse("2026-08-05T10:00:00Z"),
                inventory()
            )
        );
    }

    private static List<InventoryEntry> inventory() {
        AiVersionReferences.PolicyVersion policy = new AiVersionReferences.PolicyVersion(
            "approval-assistance-production",
            "p6-e-v1",
            OpenAiResponsesProtocol.sha256Utf8(
                "approval-assistance-production-policy/p6-e-v1/advisory-only"
            )
        );
        return List.of(
            entry(AiCapability.APPROVAL_SUMMARY, policy),
            entry(AiCapability.MATERIAL_COMPLETENESS, policy),
            entry(AiCapability.RISK_SIGNALS, policy)
        );
    }

    private static InventoryEntry entry(
        AiCapability capability,
        AiVersionReferences.PolicyVersion policy
    ) {
        return new InventoryEntry(
            capability,
            new AiVersionReferences(
                OpenAiResponsesAdvisoryProvider.providerVersion(),
                OpenAiResponsesAdvisoryProvider.modelVersion(),
                OpenAiResponsesAdvisoryProvider.promptVersion(capability),
                AiVersionReferences.KnowledgeSourceVersion.none(),
                policy,
                OpenAiResponsesAdvisoryProvider.outputSchemaVersion()
            )
        );
    }
}
