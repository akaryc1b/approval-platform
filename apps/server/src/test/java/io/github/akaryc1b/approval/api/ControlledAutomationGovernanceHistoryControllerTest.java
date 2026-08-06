package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistoryWindow;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProtocol;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceHistoryContracts.HistoryView;
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

class ControlledAutomationGovernanceHistoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-06T01:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(3_600);

    @Test
    void endpointReturnsNoStoreTenantHistory() {
        HistoryView expected = emptyView("tenant-a", FROM, NOW);
        var controller = new ControlledAutomationGovernanceHistoryController(
            (tenant, from, to) -> expected
        );

        var response = controller.history(
            "tenant-a",
            FROM.toString(),
            NOW.toString()
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(expected, response.getBody());
    }

    @Test
    void endpointRejectsNonCanonicalTenantAndInstantAndDeclaresOnlyGet() throws Exception {
        var controller = new ControlledAutomationGovernanceHistoryController(
            (tenant, from, to) -> emptyView(tenant, from, to)
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> controller.history(" tenant-a", FROM.toString(), NOW.toString())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> controller.history("tenant-a", "2026-08-06T00:00:00.000Z", NOW.toString())
        );
        assertNotNull(
            ControlledAutomationGovernanceHistoryController.class
                .getDeclaredMethod("history", String.class, String.class, String.class)
                .getAnnotation(GetMapping.class)
        );
        assertFalse(
            List.of(
                ControlledAutomationGovernanceHistoryController.class.getDeclaredMethods()
            ).stream().anyMatch(method -> method.getAnnotation(PostMapping.class) != null)
        );
    }

    @Test
    void endpointRequiresTenantScopedManagementReadPermission() {
        ApprovalManagementPermission permission =
            ControlledAutomationGovernanceHistoryController.class.getAnnotation(
                ApprovalManagementPermission.class
            );

        assertNotNull(permission);
        assertEquals(ApprovalManagementPermission.Requirement.READ, permission.value());
        assertEquals(
            ApprovalManagementPermission.ResourceScope.TENANT,
            permission.resourceScope()
        );
    }

    private static HistoryView emptyView(
        String tenant,
        Instant from,
        Instant to
    ) {
        OperationsView snapshot = OperationsView.disabled(NOW, inventory());
        return HistoryView.from(
            snapshot,
            HistorySummary.empty(new HistoryWindow(tenant, from, to, NOW))
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
