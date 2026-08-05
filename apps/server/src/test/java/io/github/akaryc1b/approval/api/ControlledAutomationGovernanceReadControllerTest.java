package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlledAutomationGovernanceReadControllerTest {

    @Test
    void endpointReturnsNoStoreReadOnlyGovernanceSnapshot() {
        OperationsView expected = OperationsView.disabled(
            Instant.parse("2026-08-05T09:10:00Z"),
            inventory()
        );
        ControlledAutomationGovernanceReadController controller =
            new ControlledAutomationGovernanceReadController(() -> expected);

        var response = controller.snapshot("tenant-a");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
    }

    @Test
    void endpointRequiresCanonicalTenantAndDeclaresOnlyGetMapping() {
        ControlledAutomationGovernanceReadController controller =
            new ControlledAutomationGovernanceReadController(
                () -> OperationsView.disabled(Instant.EPOCH, inventory())
            );

        assertThrows(IllegalArgumentException.class, () -> controller.snapshot(" "));
        assertThrows(IllegalArgumentException.class, () -> controller.snapshot(" tenant-a"));
        assertNotNull(
            ControlledAutomationGovernanceReadController.class
                .getDeclaredMethod("snapshot", String.class)
                .getAnnotation(GetMapping.class)
        );
        assertFalse(
            List.of(ControlledAutomationGovernanceReadController.class.getDeclaredMethods())
                .stream()
                .anyMatch(method -> method.getAnnotation(PostMapping.class) != null)
        );
    }

    @Test
    void endpointRequiresTenantScopedManagementReadPermission() {
        ApprovalManagementPermission permission =
            ControlledAutomationGovernanceReadController.class.getAnnotation(
                ApprovalManagementPermission.class
            );

        assertNotNull(permission);
        assertEquals(ApprovalManagementPermission.Requirement.READ, permission.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            permission.resourceScope()
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
